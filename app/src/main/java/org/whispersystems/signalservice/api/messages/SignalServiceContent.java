/*
 * Copyright (C) 2014-2016 Open Whisper Systems
 *
 * Licensed according to the LICENSE file in this repository.
 */

package org.whispersystems.signalservice.api.messages;

import android.text.TextUtils;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.message.DecryptionErrorMessage;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.MessageSender;
import org.whispersystems.signalservice.api.InvalidMessageStructureException;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Sticker;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.BusyMessage;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.OpaqueMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ConfigurationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ContactsMessage;
import org.whispersystems.signalservice.api.messages.multidevice.KeysMessage;
import org.whispersystems.signalservice.api.messages.multidevice.MessageRequestResponseMessage;
import org.whispersystems.signalservice.api.messages.multidevice.OutgoingPaymentMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ViewOnceOpenMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ViewedMessage;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.payments.Money;
import org.whispersystems.signalservice.api.push.ServiceId;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.storage.StorageKey;
import org.whispersystems.signalservice.api.util.AttachmentPointerUtil;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.UnsupportedDataMessageException;
import org.whispersystems.signalservice.internal.push.UnsupportedDataMessageProtocolVersionException;
import org.whispersystems.signalservice.internal.serialize.SignalServiceAddressProtobufSerializer;
import org.whispersystems.signalservice.internal.serialize.SignalServiceMetadataProtobufSerializer;
import org.whispersystems.signalservice.internal.serialize.protos.SignalServiceContentProto;
import org.whispersystems.util.FlagUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SignalServiceContent {

  private static final String TAG = Log.tag(SignalServiceContent.class);

  private final SignalServiceAddress      sender;
  private final int                       senderDevice;
  private final long                      timestamp;
  private final long                      serverReceivedTimestamp;
  private final long                      serverDeliveredTimestamp;
  private final boolean                   needsReceipt;
  private final SignalServiceContentProto serializedState;
  private final String                    serverUuid;
  private final Optional<byte[]>          groupId;

  private final Optional<SignalServiceDataMessage>     message;
  private final Optional<SignalServiceSyncMessage>     synchronizeMessage;
  private final Optional<SignalServiceCallMessage>     callMessage;
  private final Optional<SignalServiceReceiptMessage>  readMessage;
  private final Optional<SignalServiceTypingMessage>   typingMessage;
  private final Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage;
  private final Optional<DecryptionErrorMessage>       decryptionErrorMessage;
  private final Optional<SignalServiceStoryMessage>    storyMessage;

  private final Optional<SignalServiceProtos.Envelope>  envelope;//AA+

  private SignalServiceContent(SignalServiceDataMessage message,
                               Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                               SignalServiceProtos.Envelope envelope)//AA+
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.ofNullable(message);
    this.synchronizeMessage           = Optional.empty();
    this.callMessage                  = Optional.empty();
    this.readMessage                  = Optional.empty();
    this.typingMessage                = Optional.empty();
    this.senderKeyDistributionMessage = senderKeyDistributionMessage;
    this.decryptionErrorMessage       = Optional.empty();
    this.storyMessage                 = Optional.empty();

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  private SignalServiceContent(SignalServiceSyncMessage synchronizeMessage,
                               Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                               SignalServiceProtos.Envelope envelope)
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.empty();
    this.synchronizeMessage           = Optional.ofNullable(synchronizeMessage);
    this.callMessage                  = Optional.empty();
    this.readMessage                  = Optional.empty();
    this.typingMessage                = Optional.empty();
    this.senderKeyDistributionMessage = senderKeyDistributionMessage;
    this.decryptionErrorMessage       = Optional.empty();
    this.storyMessage                 = Optional.empty();

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  private SignalServiceContent(SignalServiceCallMessage callMessage,
                               Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                               SignalServiceProtos.Envelope envelope)
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.empty();
    this.synchronizeMessage           = Optional.empty();
    this.callMessage                  = Optional.of(callMessage);
    this.readMessage                  = Optional.empty();
    this.typingMessage                = Optional.empty();
    this.senderKeyDistributionMessage = senderKeyDistributionMessage;
    this.decryptionErrorMessage       = Optional.empty();
    this.storyMessage                 = Optional.empty();

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  private SignalServiceContent(SignalServiceReceiptMessage receiptMessage,
                               Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                               SignalServiceProtos.Envelope envelope)
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.empty();
    this.synchronizeMessage           = Optional.empty();
    this.callMessage                  = Optional.empty();
    this.readMessage                  = Optional.of(receiptMessage);
    this.typingMessage                = Optional.empty();
    this.senderKeyDistributionMessage = senderKeyDistributionMessage;
    this.decryptionErrorMessage       = Optional.empty();
    this.storyMessage                 = Optional.empty();

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  private SignalServiceContent(DecryptionErrorMessage errorMessage,
                               Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                               SignalServiceProtos.Envelope envelope)
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.empty();
    this.synchronizeMessage           = Optional.empty();
    this.callMessage                  = Optional.empty();
    this.readMessage                  = Optional.empty();
    this.typingMessage                = Optional.empty();
    this.senderKeyDistributionMessage = senderKeyDistributionMessage;
    this.decryptionErrorMessage       = Optional.of(errorMessage);
    this.storyMessage                 = Optional.empty();

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  private SignalServiceContent(SignalServiceTypingMessage typingMessage,
                               Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                                SignalServiceProtos.Envelope envelope)
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.empty();
    this.synchronizeMessage           = Optional.empty();
    this.callMessage                  = Optional.empty();
    this.readMessage                  = Optional.empty();
    this.typingMessage                = Optional.of(typingMessage);
    this.senderKeyDistributionMessage = senderKeyDistributionMessage;
    this.decryptionErrorMessage       = Optional.empty();
    this.storyMessage                 = Optional.empty();

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  private SignalServiceContent(SenderKeyDistributionMessage senderKeyDistributionMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                               SignalServiceProtos.Envelope envelope)
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.empty();
    this.synchronizeMessage           = Optional.empty();
    this.callMessage                  = Optional.empty();
    this.readMessage                  = Optional.empty();
    this.typingMessage                = Optional.empty();
    this.senderKeyDistributionMessage = Optional.of(senderKeyDistributionMessage);
    this.decryptionErrorMessage       = Optional.empty();
    this.storyMessage                 = Optional.empty();

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  private SignalServiceContent(SignalServiceStoryMessage storyMessage,
                               SignalServiceAddress sender,
                               int senderDevice,
                               long timestamp,
                               long serverReceivedTimestamp,
                               long serverDeliveredTimestamp,
                               boolean needsReceipt,
                               String serverUuid,
                               Optional<byte[]> groupId,
                               SignalServiceContentProto serializedState,
                               SignalServiceProtos.Envelope envelope)
  {
    this.sender                   = sender;
    this.senderDevice             = senderDevice;
    this.timestamp                = timestamp;
    this.serverReceivedTimestamp  = serverReceivedTimestamp;
    this.serverDeliveredTimestamp = serverDeliveredTimestamp;
    this.needsReceipt             = needsReceipt;
    this.serverUuid               = serverUuid;
    this.groupId                  = groupId;
    this.serializedState          = serializedState;

    this.message                      = Optional.empty();
    this.synchronizeMessage           = Optional.empty();
    this.callMessage                  = Optional.empty();
    this.readMessage                  = Optional.empty();
    this.typingMessage                = Optional.empty();
    this.senderKeyDistributionMessage = Optional.empty();
    this.decryptionErrorMessage       = Optional.empty();
    this.storyMessage                 = Optional.of(storyMessage);

    this.envelope       = Optional.ofNullable(envelope);//AA+
  }

  public Optional<SignalServiceDataMessage> getDataMessage() {
    return message;
  }

  public Optional<SignalServiceSyncMessage> getSyncMessage() {
    return synchronizeMessage;
  }

  public Optional<SignalServiceCallMessage> getCallMessage() {
    return callMessage;
  }

  public Optional<SignalServiceReceiptMessage> getReceiptMessage() {
    return readMessage;
  }

  public Optional<SignalServiceTypingMessage> getTypingMessage() {
    return typingMessage;
  }

  public Optional<SignalServiceStoryMessage> getStoryMessage() {
    return storyMessage;
  }

  public Optional<SenderKeyDistributionMessage> getSenderKeyDistributionMessage() {
    return senderKeyDistributionMessage;
  }

  public Optional<DecryptionErrorMessage> getDecryptionErrorMessage() {
    return decryptionErrorMessage;
  }

  public SignalServiceAddress getSender() {
    return sender;
  }

  public int getSenderDevice() {
    return senderDevice;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public long getServerReceivedTimestamp() {
    return serverReceivedTimestamp;
  }

  public long getServerDeliveredTimestamp() {
    return serverDeliveredTimestamp;
  }


  public Optional<SignalServiceProtos.Envelope> getEnvelope () {
    return envelope;
  }

  public boolean isNeedsReceipt() {
    return needsReceipt;
  }

  public String getServerUuid() {
    return serverUuid;
  }

  public Optional<byte[]> getGroupId() {
    return groupId;
  }

  public byte[] serialize() {
    return serializedState.toByteArray();
  }

  public static SignalServiceContent deserialize(byte[] data) {
    try {
      if (data == null) return null;

      SignalServiceContentProto signalServiceContentProto = SignalServiceContentProto.parseFrom(data);


      return createFromProto(signalServiceContentProto, null);//todo: null envelope passed
    } catch (InvalidProtocolBufferException | ProtocolInvalidMessageException | ProtocolInvalidKeyException | UnsupportedDataMessageException | InvalidMessageStructureException e) {
      // We do not expect any of these exceptions if this byte[] has come from serialize.
      throw new AssertionError(e);
    }
  }

  /**
   * Takes internal protobuf serialization format and processes it into a {@link SignalServiceContent}.
   */
  public static SignalServiceContent createFromProto(SignalServiceContentProto serviceContentProto, SignalServiceEnvelope envelope)//AA++ envelope
          throws ProtocolInvalidMessageException, ProtocolInvalidKeyException, UnsupportedDataMessageException, InvalidMessageStructureException
  {
    SignalServiceMetadata metadata     = SignalServiceMetadataProtobufSerializer.fromProtobuf(serviceContentProto.getMetadata());
    SignalServiceAddress  localAddress = SignalServiceAddressProtobufSerializer.fromProtobuf(serviceContentProto.getLocalAddress());

    //AA+
    if (envelope == null) {
      envelope = new SignalServiceEnvelope(SignalServiceProtos.Envelope.Type.PREKEY_BUNDLE_VALUE,
                                           Optional.of(metadata.getSender()),//RecipientUtil.toSignalServiceAddress(getContext(), messageRecord.getIndividualRecipient())),
                                           metadata.getSenderDevice(),
                                           metadata.getTimestamp(),
                                           null,
                                           null,
                                           metadata.getServerReceivedTimestamp(),
                                           metadata.getServerDeliveredTimestamp(),
                                           null,
                                           serviceContentProto.getEnvelope().toByteArray(), 0);
    }

    if (serviceContentProto.getDataCase() == SignalServiceContentProto.DataCase.LEGACYDATAMESSAGE) {
      SignalServiceProtos.DataMessage message = serviceContentProto.getLegacyDataMessage();//not provided by ufsrv

      return new SignalServiceContent(createSignalServiceMessage(metadata, message, envelope),//AA++ envelope
                                      Optional.empty(),
                                      metadata.getSender(),
                                      metadata.getSenderDevice(),
                                      metadata.getTimestamp(),
                                      metadata.getServerReceivedTimestamp(),
                                      metadata.getServerDeliveredTimestamp(),
                                      metadata.isNeedsReceipt(),
                                      metadata.getServerGuid(),
                                      metadata.getGroupId(),
                                      serviceContentProto,
                                      envelope.getEnvelope());//AA+
    } else if (serviceContentProto.getDataCase() == SignalServiceContentProto.DataCase.CONTENT) {
      SignalServiceProtos.Content            message                      = serviceContentProto.getContent();
      Optional<SenderKeyDistributionMessage> senderKeyDistributionMessage = Optional.empty();

      if (message.hasSenderKeyDistributionMessage()) {
        try {
          senderKeyDistributionMessage = Optional.of(new SenderKeyDistributionMessage(message.getSenderKeyDistributionMessage().toByteArray()));
        } catch (LegacyMessageException | InvalidMessageException | InvalidVersionException | InvalidKeyException e) {
          Log.w(TAG, "Failed to parse SenderKeyDistributionMessage!", e);
        }
      }

      if (message.hasDataMessage()) {
        return new SignalServiceContent(createSignalServiceMessage(metadata, message.getDataMessage(), envelope),//AA++ envelope
                                        senderKeyDistributionMessage,
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        metadata.isNeedsReceipt(),
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto,
                                        envelope.getEnvelope());//AA+
      } else if (message.hasSyncMessage() && localAddress.matches(metadata.getSender())) {
        return new SignalServiceContent(createSynchronizeMessage(metadata, message.getSyncMessage(), envelope),//AA++ envelope
                                        senderKeyDistributionMessage,
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        metadata.isNeedsReceipt(),
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto,
                                        envelope.getEnvelope());
      } else if (message.hasCallMessage()) {
        return new SignalServiceContent(createCallMessage(message.getDataMessage().getUfsrvCommand().getCallCommand()),
                                        senderKeyDistributionMessage,
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        metadata.isNeedsReceipt(),
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto,
                                        envelope.getEnvelope());//AA+
      } else if (message.hasReceiptMessage()) {
        return new SignalServiceContent(createReceiptMessage(metadata, message.getReceiptMessage()),
                                        senderKeyDistributionMessage,
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        metadata.isNeedsReceipt(),
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto,
                                        envelope.getEnvelope());
      } else if (message.hasTypingMessage()) {
        return new SignalServiceContent(createTypingMessage(metadata, message.getTypingMessage()),
                                        senderKeyDistributionMessage,
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        false,
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto,
                                        envelope.getEnvelope());//AA+
      } else if (message.hasDecryptionErrorMessage()) {
        return new SignalServiceContent(createDecryptionErrorMessage(metadata, message.getDecryptionErrorMessage()),
                                        senderKeyDistributionMessage,
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        metadata.isNeedsReceipt(),
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto,
                                        envelope.getEnvelope());//AA+
      } else if (senderKeyDistributionMessage.isPresent()) {
        return new SignalServiceContent(senderKeyDistributionMessage.get(),
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        false,
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto,
                                        envelope.getEnvelope());//AA+
      } else if (message.hasStoryMessage()) {
        return new SignalServiceContent(createStoryMessage(message.getStoryMessage()),
                                        metadata.getSender(),
                                        metadata.getSenderDevice(),
                                        metadata.getTimestamp(),
                                        metadata.getServerReceivedTimestamp(),
                                        metadata.getServerDeliveredTimestamp(),
                                        false,
                                        metadata.getServerGuid(),
                                        metadata.getGroupId(),
                                        serviceContentProto, envelope.getEnvelope());//AA+
      }
    }

    return null;
  }

  private static SignalServiceDataMessage createSignalServiceMessage(SignalServiceMetadata metadata, SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope)//AA++ envelope
          throws UnsupportedDataMessageException, InvalidMessageStructureException
  {
    SignalServiceGroupV2                groupInfoV2  = createGroupV2Info(envelope);//AA+ envelope

    if (groupInfoV2 == null)  {
      Log.e(TAG, String.format("createSignalServiceMessage(Envelope.UfsrvType:'%d'): COULD NOT DETERMINE SignalServiceGroupV2", envelope.getUfsrvCommand().getUfsrvtype().getNumber()));

//      return null;//AA+
    }

    Optional<SignalServiceGroupContext> groupContext;

    try {
      groupContext = SignalServiceGroupContext.createOptional(null, groupInfoV2);
    } catch (InvalidMessageException e) {
      throw new InvalidMessageStructureException(e);
    }

    List<SignalServiceAttachment>          attachments       = new LinkedList<>();
    boolean                                endSession        = ((content.getFlags() & SignalServiceProtos.DataMessage.Flags.END_SESSION_VALUE            ) != 0);
    boolean                                expirationUpdate  = ((content.getFlags() & SignalServiceProtos.DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE) != 0);
    boolean                                profileKeyUpdate  = ((content.getFlags() & SignalServiceProtos.DataMessage.Flags.PROFILE_KEY_UPDATE_VALUE     ) != 0);
    SignalServiceDataMessage.Quote         quote             = createQuote(content, envelope);
    List<SharedContact>                    sharedContacts    = createSharedContacts(content, envelope);
    List<SignalServicePreview>             previews          = createPreviews(content, envelope);
    List<SignalServiceDataMessage.Mention> mentions          = createMentions(envelope.getMessageCommand().getBodyRangesList(), envelope.getMessageCommand().getMessagesCount() > 0 ?envelope.getMessageCommand().getMessages(0).getMessage().toStringUtf8() : null);
    SignalServiceDataMessage.Sticker       sticker           = createSticker(content, envelope);
    SignalServiceDataMessage.Reaction      reaction          = createReaction(content, envelope);
    SignalServiceDataMessage.RemoteDelete  remoteDelete      = createRemoteDelete(content);
    SignalServiceDataMessage.GroupCallUpdate groupCallUpdate = createGroupCallUpdate(content);
    SignalServiceDataMessage.StoryContext storyContext       = createStoryContext(content);

    if (content.getRequiredProtocolVersion() > SignalServiceProtos.DataMessage.ProtocolVersion.CURRENT_VALUE) {
      throw new UnsupportedDataMessageProtocolVersionException(SignalServiceProtos.DataMessage.ProtocolVersion.CURRENT_VALUE,
                                                               content.getRequiredProtocolVersion(),
                                                               metadata.getSender().getIdentifier(),
                                                               metadata.getSenderDevice(),
                                                               groupContext);
    }

    SignalServiceDataMessage.Payment payment = createPayment(content);

    if (content.getRequiredProtocolVersion() > SignalServiceProtos.DataMessage.ProtocolVersion.CURRENT.getNumber()) {
      throw new UnsupportedDataMessageProtocolVersionException(SignalServiceProtos.DataMessage.ProtocolVersion.CURRENT.getNumber(),
                                                               content.getRequiredProtocolVersion(),
                                                               metadata.getSender().getIdentifier(),
                                                               metadata.getSenderDevice(),
                                                               groupContext);
    }

//    for (SignalServiceProtos.AttachmentPointer pointer : content.getAttachmentsList()) {
//      attachments.add(createAttachmentPointer(pointer));
//    }
    for (SignalServiceProtos.AttachmentRecord pointer : UfsrvCommandUtils.getAttachments(envelope.getUfsrvCommand())) {
      attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), //AA+  ufid as String
                                                         0, SignalServiceAttachmentRemoteId.from("0"),
                                                         pointer.getContentType(),
                                                         pointer.getKey().toByteArray(),
                                                         pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.empty(),
                                                         pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.empty(),
                                                         pointer.getWidth(), pointer.getHeight(),
                                                         pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.empty(),
                                                         pointer.hasFileName() ? Optional.of(pointer.getFileName()) : Optional.empty(),
                                                         (pointer.getFlags() & FlagUtil.toBinaryFlag(SignalServiceProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE)) != 0,
                                                         (pointer.getFlags() & FlagUtil.toBinaryFlag(SignalServiceProtos.AttachmentPointer.Flags.BORDERLESS_VALUE)) != 0,
                                                         (pointer.getFlags() & FlagUtil.toBinaryFlag(SignalServiceProtos.AttachmentPointer.Flags.GIF_VALUE)) != 0,
                                                         pointer.hasCaption() ? Optional.of(pointer.getCaption()) : Optional.empty(),
                                                         pointer.hasBlurHash() ? Optional.of(pointer.getBlurHash()) : Optional.empty(),
                                                         pointer.hasUploadTimestamp() ? pointer.getUploadTimestamp() : 0));
    }

    if (content.hasTimestamp() && content.getTimestamp() != metadata.getTimestamp()) {
      //AA- todo: not supported at backend
//      throw new InvalidMessageStructureException("Timestamps don't match: " + content.getTimestamp() + " vs " + metadata.getTimestamp(),
//                                                metadata.getSender().getIdentifier(),
//                                                metadata.getSenderDevice());
    }

    return new SignalServiceDataMessage(metadata.getTimestamp(),
                                        null,
                                        groupInfoV2,
                                        attachments,
                                        content.getBody(),
                                        endSession,
                                        (int)(getExpireTimerIfSet(envelope)/1000),//AA++
                                        expirationUpdate,
                                        content.hasProfileKey() ? content.getProfileKey().toByteArray() : null,
                                        profileKeyUpdate,
                                        quote,
                                        sharedContacts,
                                        previews,
                                        mentions,
                                        sticker,
                                        content.getIsViewOnce(),
                                        reaction,
                                        remoteDelete,
                                        groupCallUpdate,
                                        payment,
                                        storyContext,
                                        Optional.ofNullable(envelope.getUfsrvCommand()));//AA+  extra constructor params);
  }

  //AA+
  static private long getExpireTimerIfSet(SignalServiceEnvelope envelope)
  {
    SignalServiceProtos.MessageCommand messageCommand;
    if (envelope.getUfsrvCommand() != null && envelope.getUfsrvCommand().getUfsrvtype() == SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_MESSAGE) {
      messageCommand = envelope.getUfsrvCommand().getMsgCommand();
      if (messageCommand.getHeader().getCommand() == SignalServiceProtos.MessageCommand.CommandTypes.SAY_VALUE && messageCommand.getFencesList().size() > 0) return messageCommand.getFences(0).getExpireTimer();
    }

    return 0;
  }

  private static SignalServiceSyncMessage createSynchronizeMessage(SignalServiceMetadata metadata,
                                                                   SignalServiceProtos.SyncMessage content,
                                                                   SignalServiceEnvelope envelope)//AA++ envelope
          throws ProtocolInvalidKeyException, UnsupportedDataMessageException, InvalidMessageStructureException
  {
    if (content.hasSent()) {
      Map<SignalServiceAddress, Boolean>   unidentifiedStatuses = new HashMap<>();
      SignalServiceProtos.SyncMessage.Sent sentContent          = content.getSent();
      SignalServiceDataMessage             dataMessage          = createSignalServiceMessage(metadata, sentContent.getMessage(), envelope);//AA++ envelope
      Optional<SignalServiceAddress>       address              = SignalServiceAddress.isValidAddress(sentContent.getDestinationUuid())
                                                                  ? Optional.of(new SignalServiceAddress(ServiceId.parseOrThrow(sentContent.getDestinationUuid())))
                                                                  : Optional.<SignalServiceAddress>empty();

      if (!address.isPresent() && !dataMessage.getGroupContext().isPresent()) {
        throw new InvalidMessageStructureException("SyncMessage missing both destination and group ID!");
      }

      for (SignalServiceProtos.SyncMessage.Sent.UnidentifiedDeliveryStatus status : sentContent.getUnidentifiedStatusList()) {
        if (SignalServiceAddress.isValidAddress(status.getDestinationUuid(), null)) {
          SignalServiceAddress recipient = new SignalServiceAddress(ServiceId.parseOrThrow(status.getDestinationUuid()));
          unidentifiedStatuses.put(recipient, status.getUnidentified());
        } else {
          Log.w(TAG, "Encountered an invalid UnidentifiedDeliveryStatus in a SentTranscript! Ignoring.");
        }
      }

      return SignalServiceSyncMessage.forSentTranscript(new SentTranscriptMessage(address,
                                                                                  sentContent.getTimestamp(),
                                                                                  dataMessage,
                                                                                  sentContent.getExpirationStartTimestamp(),
                                                                                  unidentifiedStatuses,
                                                                                  sentContent.getIsRecipientUpdate()));
    }

    if (content.hasRequest()) {
      return SignalServiceSyncMessage.forRequest(new RequestMessage(content.getRequest()));
    }

    if (content.getReadList().size() > 0) {
      List<ReadMessage> readMessages = new LinkedList<>();

      for (SignalServiceProtos.SyncMessage.Read read : content.getReadList()) {
        if (SignalServiceAddress.isValidAddress(read.getSenderUuid())) {
          SignalServiceAddress address = new SignalServiceAddress(ServiceId.parseOrThrow(read.getSenderUuid()));
          readMessages.add(new ReadMessage(address, read.getTimestamp()));
        } else {
          Log.w(TAG, "Encountered an invalid ReadMessage! Ignoring.");
        }
      }

      return SignalServiceSyncMessage.forRead(readMessages);
    }

    if (content.getViewedList().size() > 0) {
      List<ViewedMessage> viewedMessages = new LinkedList<>();

      for (SignalServiceProtos.SyncMessage.Viewed viewed : content.getViewedList()) {
        if (SignalServiceAddress.isValidAddress(viewed.getSenderUuid())) {
          SignalServiceAddress address = new SignalServiceAddress(ServiceId.parseOrThrow(viewed.getSenderUuid()));
          viewedMessages.add(new ViewedMessage(address, viewed.getTimestamp()));
        } else {
          Log.w(TAG, "Encountered an invalid ReadMessage! Ignoring.");
        }
      }

      return SignalServiceSyncMessage.forViewed(viewedMessages);
    }

    if (content.hasViewOnceOpen()) {
      if (SignalServiceAddress.isValidAddress(content.getViewOnceOpen().getSenderUuid())) {
        SignalServiceAddress address   = new SignalServiceAddress(ServiceId.parseOrThrow(content.getViewOnceOpen().getSenderUuid()));
        ViewOnceOpenMessage  timerRead = new ViewOnceOpenMessage(address, content.getViewOnceOpen().getTimestamp());
        return SignalServiceSyncMessage.forViewOnceOpen(timerRead);
      } else {
        throw new InvalidMessageStructureException("ViewOnceOpen message has no sender!");
      }
    }

    if (content.hasVerified()) {
      if (SignalServiceAddress.isValidAddress(content.getVerified().getDestinationUuid())) {
        try {
          SignalServiceProtos.Verified verified    = content.getVerified();
          SignalServiceAddress         destination = new SignalServiceAddress(ServiceId.parseOrThrow(verified.getDestinationUuid()));
          IdentityKey                  identityKey = new IdentityKey(verified.getIdentityKey().toByteArray(), 0);

          VerifiedMessage.VerifiedState verifiedState;

          if (verified.getState() == SignalServiceProtos.Verified.State.DEFAULT) {
            verifiedState = VerifiedMessage.VerifiedState.DEFAULT;
          } else if (verified.getState() == SignalServiceProtos.Verified.State.VERIFIED) {
            verifiedState = VerifiedMessage.VerifiedState.VERIFIED;
          } else if (verified.getState() == SignalServiceProtos.Verified.State.UNVERIFIED) {
            verifiedState = VerifiedMessage.VerifiedState.UNVERIFIED;
          } else {
            throw new InvalidMessageStructureException("Unknown state: " + verified.getState().getNumber(),
                                                       metadata.getSender().getIdentifier(),
                                                       metadata.getSenderDevice());
          }

          return SignalServiceSyncMessage.forVerified(new VerifiedMessage(destination, identityKey, verifiedState, System.currentTimeMillis()));
        } catch (InvalidKeyException e) {
          throw new ProtocolInvalidKeyException(e, metadata.getSender().getIdentifier(), metadata.getSenderDevice());
        }
      } else {
        throw new InvalidMessageStructureException("Verified message has no sender!");
      }
    }

    if (content.getStickerPackOperationList().size() > 0) {
      List<StickerPackOperationMessage> operations = new LinkedList<>();

      for (SignalServiceProtos.SyncMessage.StickerPackOperation operation : content.getStickerPackOperationList()) {
        byte[]                           packId  = operation.hasPackId() ? operation.getPackId().toByteArray() : null;
        byte[]                           packKey = operation.hasPackKey() ? operation.getPackKey().toByteArray() : null;
        StickerPackOperationMessage.Type type    = null;

        if (operation.hasType()) {
          switch (operation.getType()) {
            case INSTALL: type = StickerPackOperationMessage.Type.INSTALL; break;
            case REMOVE:  type = StickerPackOperationMessage.Type.REMOVE; break;
          }
        }
        operations.add(new StickerPackOperationMessage(packId, packKey, type));
      }

      return SignalServiceSyncMessage.forStickerPackOperations(operations);
    }

    if (content.hasBlocked()) {
      List<String>               numbers   = content.getBlocked().getNumbersList();
      List<String>               uuids     = content.getBlocked().getUuidsList();
      List<SignalServiceAddress> addresses = new ArrayList<>(numbers.size() + uuids.size());
      List<byte[]>               groupIds  = new ArrayList<>(content.getBlocked().getGroupIdsList().size());

      for (String uuid : uuids) {
        Optional<SignalServiceAddress> address = SignalServiceAddress.fromRaw(uuid, null);
        if (address.isPresent()) {
          addresses.add(address.get());
        }
      }

      for (ByteString groupId : content.getBlocked().getGroupIdsList()) {
        groupIds.add(groupId.toByteArray());
      }

      return SignalServiceSyncMessage.forBlocked(new BlockedListMessage(addresses, groupIds));
    }

    if (content.hasConfiguration()) {
      Boolean readReceipts                   = content.getConfiguration().hasReadReceipts() ? content.getConfiguration().getReadReceipts() : null;
      Boolean unidentifiedDeliveryIndicators = content.getConfiguration().hasUnidentifiedDeliveryIndicators() ? content.getConfiguration().getUnidentifiedDeliveryIndicators() : null;
      Boolean typingIndicators               = content.getConfiguration().hasTypingIndicators() ? content.getConfiguration().getTypingIndicators() : null;
      Boolean linkPreviews                   = content.getConfiguration().hasLinkPreviews() ? content.getConfiguration().getLinkPreviews() : null;

      return SignalServiceSyncMessage.forConfiguration(new ConfigurationMessage(Optional.ofNullable(readReceipts),
                                                                                Optional.ofNullable(unidentifiedDeliveryIndicators),
                                                                                Optional.ofNullable(typingIndicators),
                                                                                Optional.ofNullable(linkPreviews)));
    }

    if (content.hasFetchLatest() && content.getFetchLatest().hasType()) {
      switch (content.getFetchLatest().getType()) {
        case LOCAL_PROFILE:       return SignalServiceSyncMessage.forFetchLatest(SignalServiceSyncMessage.FetchType.LOCAL_PROFILE);
        case STORAGE_MANIFEST:    return SignalServiceSyncMessage.forFetchLatest(SignalServiceSyncMessage.FetchType.STORAGE_MANIFEST);
        case SUBSCRIPTION_STATUS: return SignalServiceSyncMessage.forFetchLatest(SignalServiceSyncMessage.FetchType.SUBSCRIPTION_STATUS);
      }
    }

    if (content.hasMessageRequestResponse()) {
      MessageRequestResponseMessage.Type type;

      switch (content.getMessageRequestResponse().getType()) {
        case ACCEPT:
          type = MessageRequestResponseMessage.Type.ACCEPT;
          break;
        case DELETE:
          type = MessageRequestResponseMessage.Type.DELETE;
          break;
        case BLOCK:
          type = MessageRequestResponseMessage.Type.BLOCK;
          break;
        case BLOCK_AND_DELETE:
          type = MessageRequestResponseMessage.Type.BLOCK_AND_DELETE;
          break;
        default:
          type = MessageRequestResponseMessage.Type.UNKNOWN;
          break;
      }

      MessageRequestResponseMessage responseMessage;

      if (content.getMessageRequestResponse().hasGroupId()) {
        responseMessage = MessageRequestResponseMessage.forGroup(content.getMessageRequestResponse().getGroupId().toByteArray(), type);
      } else {
        Optional<SignalServiceAddress> address = SignalServiceAddress.fromRaw(content.getMessageRequestResponse().getThreadUuid(), null);

        if (address.isPresent()) {
          responseMessage = MessageRequestResponseMessage.forIndividual(address.get(), type);
        } else {
          throw new InvalidMessageStructureException("Message request response has an invalid thread identifier!");
        }
      }

      return SignalServiceSyncMessage.forMessageRequestResponse(responseMessage);
    }

    if (content.hasOutgoingPayment()) {
      SignalServiceProtos.SyncMessage.OutgoingPayment outgoingPayment = content.getOutgoingPayment();
      switch (outgoingPayment.getPaymentDetailCase()) {
        case MOBILECOIN: {
          SignalServiceProtos.SyncMessage.OutgoingPayment.MobileCoin mobileCoin = outgoingPayment.getMobileCoin();
          Money.MobileCoin                                           amount     = Money.picoMobileCoin(mobileCoin.getAmountPicoMob());
          Money.MobileCoin                                           fee        = Money.picoMobileCoin(mobileCoin.getFeePicoMob());
          ByteString                                                 address    = mobileCoin.getRecipientAddress();
          Optional<SignalServiceAddress>                             recipient  = SignalServiceAddress.fromRaw(outgoingPayment.getRecipientUuid(), null);

          return SignalServiceSyncMessage.forOutgoingPayment(new OutgoingPaymentMessage(recipient,
                                                                                        amount,
                                                                                        fee,
                                                                                        mobileCoin.getReceipt(),
                                                                                        mobileCoin.getLedgerBlockIndex(),
                                                                                        mobileCoin.getLedgerBlockTimestamp(),
                                                                                        address.isEmpty() ? Optional.empty() : Optional.of(address.toByteArray()),
                                                                                        Optional.of(outgoingPayment.getNote()),
                                                                                        mobileCoin.getOutputPublicKeysList(),
                                                                                        mobileCoin.getSpentKeyImagesList()));
        }
        default:
          return SignalServiceSyncMessage.empty();
      }
    }

    if (content.hasKeys() && content.getKeys().hasStorageService()) {
      byte[] storageKey = content.getKeys().getStorageService().toByteArray();

      return SignalServiceSyncMessage.forKeys(new KeysMessage(Optional.of(new StorageKey(storageKey))));
    }

    if (content.hasContacts()) {
      return SignalServiceSyncMessage.forContacts(new ContactsMessage(createAttachmentPointer(content.getContacts().getBlob()), content.getContacts().getComplete()));
    }

    return SignalServiceSyncMessage.empty();
  }

  //AA+ slightly adapted from original.. may be factored out eventually
  private static SignalServiceCallMessage createCallMessage(SignalServiceProtos.CallCommand content) {
    boolean isMultiRing         = content.getMultiRing();
    Integer destinationDeviceId = content.hasDestinationDeviceId() ? content.getDestinationDeviceId() : null;

    Recipient recipient;
    long        groupId;

    if (!content.hasFence() && !content.hasOriginator()) {
      Log.e(TAG, String.format("createCallMessage: CallCommand doesn't have key fields provided (fence:'%b', originator:'%b', to:'%b')", content.hasFence(), content.hasOriginator(), content.getToCount()>0));
      return null;
    }

    groupId     = content.getFence().getFid();
    recipient  = Recipient.live(Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(content.getOriginator().getUfsrvuid().toByteArray())).serialize()).get();
    Log.d(TAG, String.format("createCallMessage: CallCommand Received (fence:'%d', originator:'%d', command:'%d')", groupId, content.hasOriginator(), content.getHeader().getCommand()));

    if (content.hasOffer()) {
      CallCommand.Offer offerContent = content.getOffer();
//      return SignalServiceCallMessage.forOffer(new OfferMessage(offerContent.getId(), offerContent.getDescription(), OfferMessage.Type.fromProto(offerContent.getType()), recipient, groupId), isMultiRing, destinationDeviceId);
      //PORTING NOTE UPDATE processCallCommandOffer
      CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(), CallCommand.CommandTypes.OFFER, recipient);
      return SignalServiceCallMessage.forOffer(new OfferMessage(offerContent.getId(), offerContent.hasSdp() ? offerContent.getSdp() : null, OfferMessage.Type.fromProto(offerContent.getType()), offerContent.hasOpaque() ? offerContent.getOpaque().toByteArray() : null, callCommandBuilder), isMultiRing, destinationDeviceId);
    } else if (content.hasAnswer()) {
      CallCommand.Answer answerContent = content.getAnswer();
      CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(), CallCommand.CommandTypes.ANSWER, recipient);
      return SignalServiceCallMessage.forAnswer(new AnswerMessage(answerContent.getId(), answerContent.hasSdp() ? answerContent.getSdp() : null, answerContent.hasOpaque() ? answerContent.getOpaque().toByteArray() : null, callCommandBuilder), isMultiRing, destinationDeviceId);
//      return SignalServiceCallMessage.forAnswer(new AnswerMessage(answerContent.getId(), answerContent.getDescription(), recipient, groupId), isMultiRing, destinationDeviceId);
    } else if (content.getIceUpdateCount() > 0) {
      List<IceUpdateMessage> iceUpdates = new LinkedList<>();

      for (SignalServiceProtos.CallCommand.IceUpdate iceUpdate : content.getIceUpdateList()) {
//        iceUpdates.add(new IceUpdateMessage(iceUpdate.getId(), iceUpdate.getSdpMid(), iceUpdate.getSdpMLineIndex(), iceUpdate.getSdp(), recipient, groupId));
        CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(), SignalServiceProtos.CallCommand.CommandTypes.ICE_UPDATE, recipient);
        iceUpdates.add(new IceUpdateMessage(iceUpdate.getId(), iceUpdate.hasOpaque() ? iceUpdate.getOpaque().toByteArray() : null, iceUpdate.hasSdp() ? iceUpdate.getSdp() : null, callCommandBuilder));
      }

      return SignalServiceCallMessage.forIceUpdates(iceUpdates, isMultiRing, destinationDeviceId);
    } else if (content.hasLegacyHangup()) {
      CallCommand.Hangup hangup = content.getLegacyHangup();
      CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(), CallCommand.CommandTypes.HANGUP, recipient);
      return SignalServiceCallMessage.forHangup(new HangupMessage(hangup.getId(), HangupMessage.Type.fromProto(hangup.getType()), hangup.getDeviceId(), content.hasLegacyHangup(), callCommandBuilder), isMultiRing, destinationDeviceId);
    } else if (content.hasHangup()) {
      CallCommand.Hangup hangup = content.getHangup();
      CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(), CallCommand.CommandTypes.HANGUP, recipient);
      return SignalServiceCallMessage.forHangup(new HangupMessage(hangup.getId(), HangupMessage.Type.fromProto(hangup.getType()), hangup.getDeviceId(), content.hasLegacyHangup(), callCommandBuilder), isMultiRing, destinationDeviceId);
    } else if (content.hasBusy()) {
      CallCommand.Busy busy = content.getBusy();
      CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(), CallCommand.CommandTypes.BUSY, recipient);
      return SignalServiceCallMessage.forBusy(new BusyMessage(busy.getId(), callCommandBuilder), isMultiRing, destinationDeviceId);
    } else if (content.hasOpaque()) {
      SignalServiceProtos.CallCommand.Opaque opaque = content.getOpaque();
      CallCommand.Builder callCommandBuilder = MessageSender.buildCallCommand(ApplicationContext.getInstance(), System.currentTimeMillis(), CallCommand.CommandTypes.OPAQUE_MESSAGE, recipient);
      return SignalServiceCallMessage.forOpaque(new OpaqueMessage(opaque.getData().toByteArray(), null, callCommandBuilder), isMultiRing, destinationDeviceId);
    }

    return SignalServiceCallMessage.empty();
  }

  private static SignalServiceReceiptMessage createReceiptMessage(SignalServiceMetadata metadata, SignalServiceProtos.ReceiptMessage content) {
    SignalServiceReceiptMessage.Type type;

    if      (content.getType() == SignalServiceProtos.ReceiptMessage.Type.DELIVERY) type = SignalServiceReceiptMessage.Type.DELIVERY;
    else if (content.getType() == SignalServiceProtos.ReceiptMessage.Type.READ)     type = SignalServiceReceiptMessage.Type.READ;
    else if (content.getType() == SignalServiceProtos.ReceiptMessage.Type.VIEWED)   type = SignalServiceReceiptMessage.Type.VIEWED;
    else                                                                            type = SignalServiceReceiptMessage.Type.UNKNOWN;

    return new SignalServiceReceiptMessage(type, content.getTimestampList(), metadata.getTimestamp(), null);//AA++ null
  }

  private static DecryptionErrorMessage createDecryptionErrorMessage(SignalServiceMetadata metadata, ByteString content) throws InvalidMessageStructureException {
    try {
      return new DecryptionErrorMessage(content.toByteArray());
    } catch (InvalidMessageException e) {
      throw new InvalidMessageStructureException(e, metadata.getSender().getIdentifier(), metadata.getSenderDevice());
    }
  }

  private static SignalServiceTypingMessage createTypingMessage(SignalServiceMetadata metadata, SignalServiceProtos.TypingMessage content) throws InvalidMessageStructureException {
    SignalServiceTypingMessage.Action action;

    if      (content.getAction() == SignalServiceProtos.TypingMessage.Action.STARTED) action = SignalServiceTypingMessage.Action.STARTED;
    else if (content.getAction() == SignalServiceProtos.TypingMessage.Action.STOPPED) action = SignalServiceTypingMessage.Action.STOPPED;
    else                                                          action = SignalServiceTypingMessage.Action.UNKNOWN;

    if (content.hasTimestamp() && content.getTimestamp() != metadata.getTimestamp()) {
      throw new InvalidMessageStructureException("Timestamps don't match: " + content.getTimestamp() + " vs " + metadata.getTimestamp(),
                                                 metadata.getSender().getIdentifier(),
                                                 metadata.getSenderDevice());
    }

    return new SignalServiceTypingMessage(action, content.getTimestamp(),
                                          content.hasGroupId() ? Optional.of(content.getGroupId().toByteArray()) :
                                          Optional.empty());
  }

  private static SignalServiceStoryMessage createStoryMessage(SignalServiceProtos.StoryMessage content) throws InvalidMessageStructureException {
    byte[] profileKey = content.hasProfileKey() ? content.getProfileKey().toByteArray() : null;

    if (content.hasFileAttachment()) {
      return SignalServiceStoryMessage.forFileAttachment(profileKey,
                                                         createGroupV2Info(content),
                                                         createAttachmentPointer(content.getFileAttachment()),
                                                         content.getAllowsReplies());
    } else {
      return SignalServiceStoryMessage.forTextAttachment(profileKey,
                                                         createGroupV2Info(content),
                                                         createTextAttachment(content.getTextAttachment()),
                                                         content.getAllowsReplies());
    }
  }

 /* private static SignalServiceDataMessage.Quote createQuote(SignalServiceProtos.DataMessage content, boolean isGroupV2)
          throws  InvalidMessageStructureException
  {
    if (!content.hasQuote()) return null;
    List<SignalServiceDataMessage.Quote.QuotedAttachment> attachments = new LinkedList<>();
    for (SignalServiceProtos.DataMessage.Quote.QuotedAttachment attachment : content.getQuote().getAttachmentsList()) {
      attachments.add(new SignalServiceDataMessage.Quote.QuotedAttachment(attachment.getContentType(),
                                                                          attachment.getFileName(),
                                                                          attachment.hasThumbnail() ? createAttachmentPointer(attachment.getThumbnail()) : null));
    }

   if (SignalServiceAddress.isValidAddress(content.getQuote().getAuthorUuid())) {
      SignalServiceAddress address = new SignalServiceAddress(ServiceId.parseOrThrow(content.getQuote().getAuthorUuid()));

      return new SignalServiceDataMessage.Quote(content.getQuote().getId(),
                                                address,
                                                content.getQuote().getText(),
                                                attachments,
                                                createMentions(content.getQuote().getBodyRangesList(), content.getQuote().getText(), isGroupV2));
    } else {
      Log.w(TAG, "Quote was missing an author! Returning null.");
      return null;
    }
  }*/

  //AA+ replaces above: adapted to work withQuotedAttachment/QuotedAttachmentRecord/AttachmentRecord
  private static SignalServiceDataMessage.Quote createQuote(SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope) throws InvalidMessageStructureException {
    SignalServiceProtos.MessageCommand messageCommand = envelope.getMessageCommand();
    if (messageCommand == null || !messageCommand.hasQuotedMessage()) return null;

    List<SignalServiceDataMessage.Quote.QuotedAttachment> attachments = new LinkedList<>();

    for (SignalServiceProtos.QuotedMessageRecord.QuotedAttachment attachment : messageCommand.getQuotedMessage().getAttachmentsList()) {
      attachments.add(new SignalServiceDataMessage.Quote.QuotedAttachment(attachment.getContentType(),
                                                                          attachment.getFileName(),
                                                                          attachment.hasThumbnail() ? createAttachmentPointer(attachment.getThumbnail()) : null));
    }

    Recipient recipient = Recipient.resolvedFromUfsrvUid(messageCommand.getQuotedMessage().getAuthorE164());
    SignalServiceAddress address = new SignalServiceAddress(recipient.requireServiceId(), messageCommand.getQuotedMessage().getAuthorE164());

    return new SignalServiceDataMessage.Quote(messageCommand.getQuotedMessage().getId(),
                                              address,
                                              messageCommand.getQuotedMessage().getText(),
                                              attachments,
                                              createMentions(messageCommand.getQuotedMessage().getBodyRangesList(), messageCommand.getQuotedMessage().getText()));
  }

//  if (message.getPreviews().isPresent()) {
//      for (SignalServiceDataMessage.Preview preview : message.getPreviews().get()) {
//        DataMessage.Preview.Builder previewBuilder = DataMessage.Preview.newBuilder();
//        previewBuilder.setTitle(preview.getTitle());
//        previewBuilder.setDescription(preview.getDescription());
//        previewBuilder.setDate(preview.getDate());
//        previewBuilder.setUrl(preview.getUrl());
//
//        if (preview.getImage().isPresent()) {
//          if (preview.getImage().get().isStream()) {
//            previewBuilder.setImage(createAttachmentPointer(preview.getImage().get().asStream()));
//          } else {
//            previewBuilder.setImage(createAttachmentPointer(preview.getImage().get().asPointer()));
//          }
//        }
//
//        builder.addPreview(previewBuilder.build());
//      }
//    }

  //AA+ replaces above from createMessageContent(SignalServiceDataMessage message)
  private static List<SignalServicePreview> createPreviews(SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope) throws InvalidMessageStructureException
  {
    SignalServiceProtos.MessageCommand msgCommand = envelope.getMessageCommand();
    if (msgCommand != null) {
      if (msgCommand.getPreviewCount() > 0) {
        List<SignalServicePreview> results = new LinkedList<>();

        for (SignalServiceProtos.PreviewRecord preview : msgCommand.getPreviewList()) {
          results.add(createPreview(preview));
        }

        return results;
      }
    }

    return null;
  }

  //AA+ to replace below
  private static SignalServicePreview createPreview(SignalServiceProtos.PreviewRecord preview) throws InvalidMessageStructureException {
    SignalServiceAttachment attachment = null;

    if (preview.hasImage()) {
      attachment = createAttachmentPointer(preview.getImage());
    }

    return new SignalServicePreview(preview.getUrl(),
                                    preview.getTitle(),
                                    preview.getDescription(),
                                    preview.getDate(),
                                    Optional.ofNullable(attachment));
  }

  //PORTING NOTE see createPreview(SignalServiceProtos.PreviewRecord preview)
  private static SignalServicePreview createPreview(SignalServiceProtos.Preview preview) throws InvalidMessageStructureException {
    SignalServiceAttachment attachment = null;

    if (preview.hasImage()) {
      attachment = createAttachmentPointer(preview.getImage());
    }

    return new SignalServicePreview(preview.getUrl(),
                                    preview.getTitle(),
                                    preview.getDescription(),
                                    preview.getDate(),
                                    Optional.ofNullable(attachment));
  }

  public static List<SignalServiceDataMessage.Mention> createMentions(List<SignalServiceProtos.BodyRange> bodyRanges, String body) throws InvalidMessageStructureException {//AA+ public
    List<SignalServiceDataMessage.Mention> mentions = new LinkedList<>();

    for (SignalServiceProtos.BodyRange bodyRange : bodyRanges) {
      if (bodyRange.hasMentionUuid()) {
        try {
          UfsrvUid ufsrvUid =  UfsrvUid.fromEncoded(bodyRange.getMentionUuid());//AA+
          ServiceId serviceId =Recipient.resolvedFromUfsrvUid(ufsrvUid.toString()).requireServiceId();//AA+
          mentions.add(new SignalServiceDataMessage.Mention(ServiceId.parseOrThrow(serviceId.toString()), bodyRange.getStart(), bodyRange.getLength(), ufsrvUid));//AA+ UKNOWN ACI
//          mentions.add(new SignalServiceDataMessage.Mention(ServiceId.parseOrThrow(bodyRange.getMentionUuid()), bodyRange.getStart(), bodyRange.getLength()));
        } catch (IllegalArgumentException e) {
          throw new InvalidMessageStructureException("Invalid body range!");
        }
      }
    }

    return mentions;
  }

//  private static SignalServiceDataMessage.Sticker createSticker(SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope) {//AA++ envelope
//    if (!content.hasSticker()                ||
//            !content.getSticker().hasPackId()    ||
//            !content.getSticker().hasPackKey()   ||
//            !content.getSticker().hasStickerId() ||
//            !content.getSticker().hasData())
//    {
//      return null;
//    }
//
//    SignalServiceProtos.DataMessage.Sticker sticker = content.getSticker();
//
//    return new SignalServiceDataMessage.Sticker(sticker.getPackId().toByteArray(),
//                                                sticker.getPackKey().toByteArray(),
//                                                sticker.getStickerId(),
//                                                sticker.getEmoji(),
//                                                createAttachmentPointer(sticker.getData()));
//  }
  //AA+ replaces above
  private static Sticker createSticker(SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope) throws InvalidMessageStructureException {
    MessageCommand msgCommand = envelope.getMessageCommand();
    if (!msgCommand.hasSticker()                ||
            !msgCommand.getSticker().hasPackId()    ||
            !msgCommand.getSticker().hasPackKey()   ||
            !msgCommand.getSticker().hasStickerId() ||
            !msgCommand.getSticker().hasData())
    {
      return null;
    }

    SignalServiceProtos.StickerRecord sticker = msgCommand.getSticker();

    return new Sticker(sticker.getPackId().toByteArray(),
                       sticker.getPackKey().toByteArray(),
                       sticker.getStickerId(),
                       sticker.getEmoji(),
                       createAttachmentPointer(sticker.getData()));
  }

  //AA todo: to be adapted. currently done directly in UfsrvMessageUtils for reactions
  private static SignalServiceDataMessage.Reaction createReaction(SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope) {//AA++ envelope
    if (!content.hasReaction()                           ||
            !content.getReaction().hasEmoji()                ||
            !content.getReaction().hasTargetAuthorUuid()     ||
            !content.getReaction().hasTargetSentTimestamp())
    {
      return null;
    }

    SignalServiceProtos.DataMessage.Reaction reaction = content.getReaction();
    ServiceId                                serviceId = ServiceId.parseOrNull(reaction.getTargetAuthorUuid());

    if (serviceId == null) {
      Log.w(TAG, "Cannot parse author UUID on reaction");
      return null;
    }

    return new SignalServiceDataMessage.Reaction(reaction.getEmoji(),
                                                 reaction.getRemove(),
                                                 new SignalServiceAddress(serviceId),
                                                 reaction.getTargetSentTimestamp());
  }

  //AA TO BE PORTED GROUPCALL
  private static SignalServiceDataMessage.GroupCallUpdate createGroupCallUpdate(SignalServiceProtos.DataMessage content) {
    if (!content.hasGroupCallUpdate()) {
      return null;
    }

    SignalServiceProtos.DataMessage.GroupCallUpdate groupCallUpdate = content.getGroupCallUpdate();

    return new SignalServiceDataMessage.GroupCallUpdate(groupCallUpdate.getEraId());
  }

  //AA TO BE PORTED
  private static SignalServiceDataMessage.Payment createPayment(SignalServiceProtos.DataMessage content) throws InvalidMessageStructureException {
    if (!content.hasPayment()) {
      return null;
    }

    SignalServiceProtos.DataMessage.Payment payment = content.getPayment();

    switch (payment.getItemCase()) {
      case NOTIFICATION: return new SignalServiceDataMessage.Payment(createPaymentNotification(payment));
      default          : throw new InvalidMessageStructureException("Unknown payment item");
    }
  }

  //AA TO BE PORTED
  private static SignalServiceDataMessage.StoryContext createStoryContext(SignalServiceProtos.DataMessage content) throws InvalidMessageStructureException {
    if (!content.hasStoryContext()) {
      return null;
    }

    ServiceId serviceId = ServiceId.parseOrNull(content.getStoryContext().getAuthorUuid());

    if (serviceId == null) {
      throw new InvalidMessageStructureException("Invalid author ACI!");
    }

    return new SignalServiceDataMessage.StoryContext(serviceId, content.getStoryContext().getSentTimestamp());
  }

  private static SignalServiceDataMessage.PaymentNotification createPaymentNotification(SignalServiceProtos.DataMessage.Payment content)
          throws InvalidMessageStructureException
  {
    if (!content.hasNotification() ||
        content.getNotification().getTransactionCase() != SignalServiceProtos.DataMessage.Payment.Notification.TransactionCase.MOBILECOIN)
    {
      throw new InvalidMessageStructureException("Badly-formatted payment notification!");
    }

    SignalServiceProtos.DataMessage.Payment.Notification payment = content.getNotification();

    return new SignalServiceDataMessage.PaymentNotification(payment.getMobileCoin().getReceipt().toByteArray(), payment.getNote());
  }

//  private static List<SharedContact> createSharedContacts(SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope) {//AA++ envelope
//    if (content.getContactCount() <= 0) return null;
//
//    List<SharedContact> results = new LinkedList<>();
//
//    for (SignalServiceProtos.DataMessage.Contact contact : content.getContactList()) {
//      SharedContact.Builder builder = SharedContact.newBuilder()
//              .setName(SharedContact.Name.newBuilder()
//                               .setDisplay(contact.getGroupName().getDisplayName())
//                               .setFamily(contact.getGroupName().getFamilyName())
//                               .setGiven(contact.getGroupName().getGivenName())
//                               .setMiddle(contact.getGroupName().getMiddleName())
//                               .setPrefix(contact.getGroupName().getPrefix())
//                               .setSuffix(contact.getGroupName().getSuffix())
//                               .build());
//
//      if (contact.getAddressCount() > 0) {
//        for (SignalServiceProtos.DataMessage.Contact.PostalAddress address : contact.getAddressList()) {
//          SharedContact.PostalAddress.Type type = SharedContact.PostalAddress.Type.HOME;
//
//          switch (address.getType()) {
//            case WORK:   type = SharedContact.PostalAddress.Type.WORK;   break;
//            case HOME:   type = SharedContact.PostalAddress.Type.HOME;   break;
//            case CUSTOM: type = SharedContact.PostalAddress.Type.CUSTOM; break;
//          }
//
//          builder.withAddress(SharedContact.PostalAddress.newBuilder()
//                                      .setCity(address.getCity())
//                                      .setCountry(address.getCountry())
//                                      .setLabel(address.getLabel())
//                                      .setNeighborhood(address.getNeighborhood())
//                                      .setPobox(address.getPobox())
//                                      .setPostcode(address.getPostcode())
//                                      .setRegion(address.getRegion())
//                                      .setStreet(address.getStreet())
//                                      .setType(type)
//                                      .build());
//        }
//      }
//
//      if (contact.getNumberCount() > 0) {
//        for (SignalServiceProtos.DataMessage.Contact.Phone phone : contact.getNumberList()) {
//          SharedContact.Phone.Type type = SharedContact.Phone.Type.HOME;
//
//          switch (phone.getType()) {
//            case HOME:   type = SharedContact.Phone.Type.HOME;   break;
//            case WORK:   type = SharedContact.Phone.Type.WORK;   break;
//            case MOBILE: type = SharedContact.Phone.Type.MOBILE; break;
//            case CUSTOM: type = SharedContact.Phone.Type.CUSTOM; break;
//          }
//
//          builder.withPhone(SharedContact.Phone.newBuilder()
//                                    .setLabel(phone.getLabel())
//                                    .setType(type)
//                                    .setValue(phone.getValue())
//                                    .build());
//        }
//      }
//
//      if (contact.getEmailCount() > 0) {
//        for (SignalServiceProtos.DataMessage.Contact.Email email : contact.getEmailList()) {
//          SharedContact.Email.Type type = SharedContact.Email.Type.HOME;
//
//          switch (email.getType()) {
//            case HOME:   type = SharedContact.Email.Type.HOME;   break;
//            case WORK:   type = SharedContact.Email.Type.WORK;   break;
//            case MOBILE: type = SharedContact.Email.Type.MOBILE; break;
//            case CUSTOM: type = SharedContact.Email.Type.CUSTOM; break;
//          }
//
//          builder.withEmail(SharedContact.Email.newBuilder()
//                                    .setLabel(email.getLabel())
//                                    .setType(type)
//                                    .setValue(email.getValue())
//                                    .build());
//        }
//      }
//
//      if (contact.hasAvatar()) {
//        builder.setAvatar(SharedContact.Avatar.newBuilder()
//                                  .withAttachment(createAttachmentPointer(contact.getAvatar().getAvatar()))
//                                  .withProfileFlag(contact.getAvatar().getIsProfile())
//                                  .build());
//      }
//
//      if (contact.hasOrganization()) {
//        builder.withOrganization(contact.getOrganization());
//      }
//
//      results.add(builder.build());
//    }
//
//    return results;
//  }
  //AA+ replaces above
  private static List<SharedContact> createSharedContacts(SignalServiceProtos.DataMessage content, SignalServiceEnvelope envelope) throws InvalidMessageStructureException {
    SignalServiceProtos.MessageCommand userCommand = envelope.getMessageCommand();
    if (userCommand == null || userCommand.getContactsCount() == 0) return null;

    List<SharedContact> results = new LinkedList<>();

    for (SignalServiceProtos.UserContactRecordOrBuilder contact : userCommand.getContactsList()) {
      SharedContact.Builder builder = SharedContact.newBuilder()
              .setName(SharedContact.Name.newBuilder()
                               .setDisplay(contact.getName().getDisplayName())
                               .setFamily(contact.getName().getFamilyName())
                               .setGiven(contact.getName().getGivenName())
                               .setMiddle(contact.getName().getMiddleName())
                               .setPrefix(contact.getName().getPrefix())
                               .setSuffix(contact.getName().getSuffix())
                               .build());

      if (contact.getAddressCount() > 0) {
        for (SignalServiceProtos.UserContactRecord.PostalAddress address : contact.getAddressList()) {
          SharedContact.PostalAddress.Type type = SharedContact.PostalAddress.Type.HOME;

          switch (address.getType()) {
            case WORK:   type = SharedContact.PostalAddress.Type.WORK;   break;
            case HOME:   type = SharedContact.PostalAddress.Type.HOME;   break;
            case CUSTOM: type = SharedContact.PostalAddress.Type.CUSTOM; break;
          }

          builder.withAddress(SharedContact.PostalAddress.newBuilder()
                                      .setCity(address.getCity())
                                      .setCountry(address.getCountry())
                                      .setLabel(address.getLabel())
                                      .setNeighborhood(address.getNeighborhood())
                                      .setPobox(address.getPobox())
                                      .setPostcode(address.getPostcode())
                                      .setRegion(address.getRegion())
                                      .setStreet(address.getStreet())
                                      .setType(type)
                                      .build());
        }
      }

      if (contact.getNumberCount() > 0) {
        for (SignalServiceProtos.UserContactRecord.Phone phone : contact.getNumberList()) {
          SharedContact.Phone.Type type = SharedContact.Phone.Type.HOME;

          switch (phone.getType()) {
            case HOME:   type = SharedContact.Phone.Type.HOME;   break;
            case WORK:   type = SharedContact.Phone.Type.WORK;   break;
            case MOBILE: type = SharedContact.Phone.Type.MOBILE; break;
            case CUSTOM: type = SharedContact.Phone.Type.CUSTOM; break;
          }

          builder.withPhone(SharedContact.Phone.newBuilder()
                                    .setLabel(phone.getLabel())
                                    .setType(type)
                                    .setValue(phone.getValue())
                                    .build());
        }
      }

      if (contact.getEmailCount() > 0) {
        for (SignalServiceProtos.UserContactRecord.Email email : contact.getEmailList()) {
          SharedContact.Email.Type type = SharedContact.Email.Type.HOME;

          switch (email.getType()) {
            case HOME:   type = SharedContact.Email.Type.HOME;   break;
            case WORK:   type = SharedContact.Email.Type.WORK;   break;
            case MOBILE: type = SharedContact.Email.Type.MOBILE; break;
            case CUSTOM: type = SharedContact.Email.Type.CUSTOM; break;
          }

          builder.withEmail(SharedContact.Email.newBuilder()
                                    .setLabel(email.getLabel())
                                    .setType(type)
                                    .setValue(email.getValue())
                                    .build());
        }
      }

      if (contact.hasAvatar()) {
        builder.setAvatar(SharedContact.Avatar.newBuilder()
                                  .withAttachment(createAttachmentPointer(contact.getAvatar().getAvatar()))
                                  .withProfileFlag(contact.getAvatar().getIsProfile())
                                  .build());
      }

      if (contact.hasOrganization()) {
        builder.withOrganization(contact.getOrganization());
      }

      if (contact.hasUfsrvuid()) {
        builder.withUfsrvUid(new UfsrvUid(contact.getUfsrvuid().toByteArray()));
      }

      results.add(builder.build());
    }

    return results;
  }

  private static SignalServiceDataMessage.RemoteDelete createRemoteDelete(SignalServiceProtos.DataMessage content) {
    if (!content.hasDelete() || !content.getDelete().hasTargetSentTimestamp()) {
      return null;
    }

    SignalServiceProtos.DataMessage.Delete delete = content.getDelete();

    return new SignalServiceDataMessage.RemoteDelete(delete.getTargetSentTimestamp());
  }

  private static SignalServiceAttachmentPointer createAttachmentPointer(SignalServiceProtos.AttachmentPointer pointer) throws InvalidMessageStructureException {
    return AttachmentPointerUtil.createSignalAttachmentPointer(pointer);
  }

  //AA+ adapted for AttachmentRecord
  static private SignalServiceAttachmentPointer createAttachmentPointer(SignalServiceProtos.AttachmentRecord pointer) throws InvalidMessageStructureException {
    return new SignalServiceAttachmentPointer(pointer.getId(),
                                              0, null,//SignalServiceAttachmentRemoteId.from(pointer),
                                              pointer.getContentType(),
                                              pointer.getKey().toByteArray(),
                                              pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.<Integer>empty(),
                                              pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.<byte[]>empty(),
                                              pointer.getWidth(), pointer.getHeight(),
                                              pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.<byte[]>empty(),
                                              pointer.hasFileName() ? Optional.of(pointer.getFileName()) : Optional.<String>empty(),
                                              (pointer.getFlags() & FlagUtil.toBinaryFlag(SignalServiceProtos.AttachmentPointer.Flags.VOICE_MESSAGE_VALUE)) != 0,
                                              (pointer.getFlags() & FlagUtil.toBinaryFlag(SignalServiceProtos.AttachmentPointer.Flags.BORDERLESS_VALUE)) != 0,
                                              (pointer.getFlags() & FlagUtil.toBinaryFlag(SignalServiceProtos.AttachmentPointer.Flags.GIF_VALUE)) != 0,
                                              pointer.hasCaption() ? Optional.of(pointer.getCaption()) : Optional.<String>empty(),
                                              pointer.hasBlurHash() ? Optional.of(pointer.getBlurHash()) : Optional.<String>empty(),
                                              pointer.hasUploadTimestamp() ? pointer.getUploadTimestamp() : 0);

  }

  private static SignalServiceTextAttachment createTextAttachment(SignalServiceProtos.TextAttachment attachment) throws InvalidMessageStructureException {
    SignalServiceTextAttachment.Style style = null;
    if (attachment.hasTextStyle()) {
      switch (attachment.getTextStyle()) {
        case DEFAULT:
          style = SignalServiceTextAttachment.Style.DEFAULT;
          break;
        case REGULAR:
          style = SignalServiceTextAttachment.Style.REGULAR;
          break;
        case BOLD:
          style = SignalServiceTextAttachment.Style.BOLD;
          break;
        case SERIF:
          style = SignalServiceTextAttachment.Style.SERIF;
          break;
        case SCRIPT:
          style = SignalServiceTextAttachment.Style.SCRIPT;
          break;
        case CONDENSED:
          style = SignalServiceTextAttachment.Style.CONDENSED;
          break;
      }
    }

    Optional<String>               text                = Optional.ofNullable(attachment.hasText() ? attachment.getText() : null);
    Optional<Integer>              textForegroundColor = Optional.ofNullable(attachment.hasTextForegroundColor() ? attachment.getTextForegroundColor() : null);
    Optional<Integer>              textBackgroundColor = Optional.ofNullable(attachment.hasTextBackgroundColor() ? attachment.getTextBackgroundColor() : null);
    Optional<SignalServicePreview> preview             = Optional.ofNullable(attachment.hasPreview() ? createPreview(attachment.getPreview()) : null);

    if (attachment.hasGradient()) {
      SignalServiceProtos.TextAttachment.Gradient attachmentGradient = attachment.getGradient();

      Integer                              startColor         = attachmentGradient.hasStartColor() ? attachmentGradient.getStartColor() : null;
      Integer                              endColor           = attachmentGradient.hasEndColor() ? attachmentGradient.getEndColor() : null;
      Integer                              angle              = attachmentGradient.hasAngle() ? attachmentGradient.getAngle() : null;
      SignalServiceTextAttachment.Gradient gradient           = new SignalServiceTextAttachment.Gradient(Optional.ofNullable(startColor),
                                                                                                         Optional.ofNullable(endColor),
                                                                                                         Optional.ofNullable(angle));

      return SignalServiceTextAttachment.forGradientBackground(text, Optional.ofNullable(style), textForegroundColor, textBackgroundColor, preview, gradient);
    } else {
      return SignalServiceTextAttachment.forSolidBackground(text, Optional.ofNullable(style), textForegroundColor, textBackgroundColor, preview, attachment.getColor());
    }
  }

  private static SignalServiceGroupV2 createGroupV2Info(SignalServiceProtos.StoryMessage storyMessage) throws InvalidMessageStructureException {
    if (!storyMessage.hasGroup()) {
      return null;
    }
    return createGroupV2Info(storyMessage.getGroup());
  }

  private static SignalServiceGroupV2 GetGroupFromMessageCommandV2(@NonNull SignalServiceEnvelope envelope)
  {
    SignalServiceProtos.MessageCommand messageCommand = envelope.getMessageCommand();
    if (messageCommand.getFencesCount() > 0) {
      return getGroupV2Info(envelope.getMessageCommand().getFences(0));
    } else if (messageCommand.hasReaction()) {
      GroupMasterKey groupMasterKey =SignalDatabase.groups().getGroupMasterKey(messageCommand.getReaction().getFid(), null);
      return SignalServiceGroupV2.newBuilder(groupMasterKey).build();
    } else {
      Log.e(TAG, String.format("GetGroupFromMessageCommandV2: ERROR: COULD NOT RETRIEVE FENCE METADATA FOR MESSAGE COMMAND(protobuf command id:'%d')", envelope.getUfsrvType()));
      return null;
    }
  }

  private static SignalServiceGroupV2 GetGroupFromTypingStateCommandV2(@NonNull SignalServiceEnvelope envelope)
  {
    SignalServiceProtos.StateCommand stateCommand = envelope.getUfsrvCommand().getStateCommand();
    if (stateCommand.hasFid() && stateCommand.getFid() > 0) {
      GroupMasterKey groupMasterKey =SignalDatabase.groups().getGroupMasterKey(stateCommand.getFid(), null);
      return SignalServiceGroupV2.newBuilder(groupMasterKey).build();
    } else {
      Log.e(TAG, String.format("GetGroupFromTypingStateCommandV2: ERROR: COULD NOT RETRIEVE FENCE METADATA FOR MESSAGE COMMAND(protobuf command id:'%d')", envelope.getUfsrvType()));
      return null;
    }
  }

  private static SignalServiceGroupV2 GetGroupFromReceiptCommandV2(@NonNull SignalServiceEnvelope envelope)
  {
    SignalServiceProtos.ReceiptCommand receiptCommand = envelope.getUfsrvCommand().getReceiptCommand();
    if (receiptCommand.hasFid() && receiptCommand.getFid() > 0) {
      GroupMasterKey groupMasterKey =SignalDatabase.groups().getGroupMasterKey(receiptCommand.getFid(), null);
      return SignalServiceGroupV2.newBuilder(groupMasterKey).build();
    } else {
      Log.e(TAG, String.format("GetGroupFromReceiptCommandV2: ERROR: COULD NOT RETRIEVE FENCE METADATA FOR MESSAGE COMMAND(protobuf command id:'%d')", envelope.getUfsrvType()));
      return null;
    }
  }

  private static SignalServiceGroupV2 GetGroupFromCallCommandV2(@NonNull SignalServiceEnvelope envelope)
  {
    SignalServiceProtos.CallCommand callCommand = envelope.getUfsrvCommand().getCallCommand();
    if (callCommand.hasFence()) {
      GroupMasterKey groupMasterKey =SignalDatabase.groups().getGroupMasterKey(callCommand.getFence().getFid(), null);
      return SignalServiceGroupV2.newBuilder(groupMasterKey).build();
    } else {
      Log.e(TAG, String.format("GetGroupFromCallCommandV2: ERROR: COULD NOT RETRIEVE FENCE METADATA FOR MESSAGE COMMAND(protobuf command id:'%d')", envelope.getUfsrvType()));
      return null;
    }
  }

  private static SignalServiceGroupV2 GetGroupFromUserCommandV2(@NonNull SignalServiceEnvelope envelope)
  {
    long fid = -1;
    SignalServiceProtos.UserCommand userCommand = envelope.getUfsrvCommand().getUserCommand();

    if (userCommand.getFencesCount() > 0 && userCommand.getFences(0) != null) {
      fid = userCommand.getFences(0).getFid();
    } else {
      Log.e(TAG, String.format("GetGroupFromCallCommandV2: ERROR: COULD NOT RETRIEVE FENCE METADATA FOR MESSAGE COMMAND(protobuf command id:'%d')", envelope.getUfsrvType()));
    }

    if (fid > 0) {
      GroupMasterKey groupMasterKey =SignalDatabase.groups().getGroupMasterKey(fid, null);
      return SignalServiceGroupV2.newBuilder(groupMasterKey).build();
    } else return null;
  }

  //AA+ Envelope
  private static SignalServiceGroupV2 createGroupV2Info(SignalServiceEnvelope envelope) throws InvalidMessageStructureException
  {
    if (envelope.getUfsrvType() == UfsrvCommandWire.UfsrvType.UFSRV_MESSAGE_VALUE) {
      return GetGroupFromMessageCommandV2(envelope);
    } else if (envelope.getUfsrvType() == UfsrvCommandWire.UfsrvType.UFSRV_STATE_VALUE) {
      return GetGroupFromTypingStateCommandV2(envelope);
    } else if (envelope.getUfsrvType() == UfsrvCommandWire.UfsrvType.UFSRV_RECEIPT_VALUE) {
      return GetGroupFromReceiptCommandV2(envelope);
    } else if (envelope.getUfsrvType() == UfsrvCommandWire.UfsrvType.UFSRV_CALL_VALUE) {
      return GetGroupFromCallCommandV2(envelope);
    } else if (envelope.getUfsrvType() == UfsrvCommandWire.UfsrvType.UFSRV_USER_VALUE) {
      return GetGroupFromUserCommandV2(envelope);

    } else if (envelope.getUfsrvType() != UfsrvCommandWire.UfsrvType.UFSRV_FENCE_VALUE) return null;

    if (envelope.getFenceCommand().getFencesCount() > 0) {
      return getGroupV2Info(envelope.getFenceCommand().getFences(0));
    }

    return null;
  }

  @Nullable public static GroupMasterKey groupMasterKeyFromFenceRecord (@NonNull FenceRecord fenceRecord)
  {
    GroupMasterKey groupMasterKey = null;

    if (fenceRecord.hasFkey()) {
      try {
        groupMasterKey = new GroupMasterKey(fenceRecord.getFkey().toByteArray());
      }
      catch (InvalidInputException x) {
        Log.e(TAG, x.getMessage());
      }
    }
    else if (fenceRecord.hasFid()) {
      groupMasterKey =SignalDatabase.groups().getGroupMasterKey(fenceRecord.getFid(), null);
    }

    if (groupMasterKey == null) {
      if (!TextUtils.isEmpty(fenceRecord.getCname())) {
        groupMasterKey =SignalDatabase.groups().getGroupMasterKey(0, fenceRecord.getCname());
      }
    }

    return groupMasterKey;
  }

  private static SignalServiceGroupV2 getGroupV2Info(@NonNull FenceRecord fenceRecord)
  {
    GroupMasterKey groupMasterKey = groupMasterKeyFromFenceRecord(fenceRecord);

      if (groupMasterKey == null) {
        Log.e(TAG, String.format("getGroupV2Info: ERROR: COULD NOT RETRIEVE GROUP MASTERKEY(fid:'%d')", fenceRecord.getFid()));
        return null;
//        throw new AssertionError(String.format("ERROR (fid:'%d') DETERMINING GROUP_KEY", fenceRecord.getFid()));
      }

      SignalServiceGroupV2.Builder builder = SignalServiceGroupV2.newBuilder(groupMasterKey);
      return builder.build();

  }


  private static SignalServiceGroupV2 createGroupV2Info(SignalServiceProtos.DataMessage dataMessage) throws InvalidMessageStructureException {
    if (!dataMessage.hasGroupV2()) {
      return null;
    }
    return createGroupV2Info(dataMessage.getGroupV2());
  }

  private static SignalServiceGroupV2 createGroupV2Info(SignalServiceProtos.GroupContextV2 groupV2) throws InvalidMessageStructureException {
    if (groupV2 == null) {
      return null;
    }

    if (!groupV2.hasMasterKey()) {
      throw new InvalidMessageStructureException("No GV2 master key on message");
    }
    if (!groupV2.hasRevision()) {
      throw new InvalidMessageStructureException("No GV2 revision on message");
    }

    SignalServiceGroupV2.Builder builder;
    try {
      builder = SignalServiceGroupV2.newBuilder(new GroupMasterKey(groupV2.getMasterKey().toByteArray()))
              .withRevision(groupV2.getRevision());
    } catch (InvalidInputException e) {
      throw new InvalidMessageStructureException("Invalid GV2 input!");
    }

    if (groupV2.hasGroupChange() && !groupV2.getGroupChange().isEmpty()) {
      builder.withSignedGroupChange(groupV2.getGroupChange().toByteArray());
    }

    return builder.build();
  }
}