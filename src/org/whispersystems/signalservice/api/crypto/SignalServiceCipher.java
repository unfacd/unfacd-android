/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.signalservice.api.crypto;

import com.unfacd.android.ApplicationContext;

import org.thoughtcrime.securesms.logging.Log;

import com.google.protobuf.InvalidProtocolBufferException;

import org.signal.libsignal.metadata.InvalidMetadataMessageException;
import org.signal.libsignal.metadata.InvalidMetadataVersionException;
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidVersionException;
import org.signal.libsignal.metadata.ProtocolLegacyMessageException;
import org.signal.libsignal.metadata.ProtocolNoSessionException;
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException;
import org.signal.libsignal.metadata.SealedSessionCipher;
import org.signal.libsignal.metadata.SelfSendException;
import org.signal.libsignal.metadata.certificate.CertificateValidator;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.whispersystems.libsignal.IdentityKey;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.protocol.CiphertextMessage;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Preview;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Sticker;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.BusyMessage;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.MessageTimerReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.RequestMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage.VerifiedState;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage;
import org.whispersystems.signalservice.internal.push.PushTransportDetails;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope.Type;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.TypingMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Verified;
import org.whispersystems.signalservice.internal.push.UnsupportedDataMessageException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.util.Base64;

import java.util.HashMap;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext.Type.DELIVER;

/**
 * This is used to decrypt received {@link SignalServiceEnvelope}s.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceCipher {

  private static final String TAG = SignalServiceCipher.class.getSimpleName();

  private final SignalProtocolStore  signalProtocolStore;
  private final SignalServiceAddress localAddress;
  private final CertificateValidator certificateValidator;

  public SignalServiceCipher(SignalServiceAddress localAddress,
                             SignalProtocolStore signalProtocolStore,
                             CertificateValidator certificateValidator)
  {
    this.signalProtocolStore  = signalProtocolStore;
    this.localAddress         = localAddress;
    this.certificateValidator = certificateValidator;
  }

  public OutgoingPushMessage encrypt(SignalProtocolAddress        destination,
                                     Optional<UnidentifiedAccess> unidentifiedAccess,
                                     byte[]                       unpaddedMessage)
          throws UntrustedIdentityException, InvalidKeyException
  {
    if (unidentifiedAccess.isPresent()) {
      SealedSessionCipher  sessionCipher        = new SealedSessionCipher(signalProtocolStore, new SignalProtocolAddress(localAddress.getNumber(), 1));
      PushTransportDetails transportDetails     = new PushTransportDetails(sessionCipher.getSessionVersion(destination));
      byte[]               ciphertext           = sessionCipher.encrypt(destination, unidentifiedAccess.get().getUnidentifiedCertificate(), transportDetails.getPaddedMessageBody(unpaddedMessage));
      String               body                 = Base64.encodeBytes(ciphertext);
      int                  remoteRegistrationId = sessionCipher.getRemoteRegistrationId(destination);

      return new OutgoingPushMessage(Type.UNIDENTIFIED_SENDER_VALUE, destination.getDeviceId(), remoteRegistrationId, body);
    } else {
      SessionCipher        sessionCipher        = new SessionCipher(signalProtocolStore, destination);
      PushTransportDetails transportDetails     = new PushTransportDetails(sessionCipher.getSessionVersion());
      CiphertextMessage    message              = sessionCipher.encrypt(transportDetails.getPaddedMessageBody(unpaddedMessage));
      int                  remoteRegistrationId = sessionCipher.getRemoteRegistrationId();
      String               body                 = Base64.encodeBytes(message.serialize());

      int type;

      switch (message.getType()) {
        case CiphertextMessage.PREKEY_TYPE:  type = Type.PREKEY_BUNDLE_VALUE; break;
        case CiphertextMessage.WHISPER_TYPE: type = Type.CIPHERTEXT_VALUE;    break;
        default: throw new AssertionError("Bad type: " + message.getType());
      }

      return new OutgoingPushMessage(type, destination.getDeviceId(), remoteRegistrationId, body);
    }
  }
//

 public OutgoingPushMessage encrypt (SignalProtocolAddress destination,
                                     Optional<UnidentifiedAccess> unidentifiedAccess,
                                     byte[] unpaddedMessage,
                                     UfsrvCommand ufCommand)
         throws UntrustedIdentityException, InvalidKeyException
 {
    if (ufCommand.isE2ee()) {
      if (unidentifiedAccess.isPresent()) {
        SealedSessionCipher  sessionCipher        = new SealedSessionCipher(signalProtocolStore, new SignalProtocolAddress(localAddress.getNumber(), 1));
        PushTransportDetails transportDetails     = new PushTransportDetails(sessionCipher.getSessionVersion(destination));
        byte[]               ciphertext           = sessionCipher.encrypt(destination, unidentifiedAccess.get().getUnidentifiedCertificate(), transportDetails.getPaddedMessageBody(unpaddedMessage));
        String               body                 = Base64.encodeBytes(ciphertext);
        int                  remoteRegistrationId = sessionCipher.getRemoteRegistrationId(destination);

        return new OutgoingPushMessage(Type.UNIDENTIFIED_SENDER_VALUE, destination.getDeviceId(), remoteRegistrationId, body);
      } else {
        SessionCipher        sessionCipher        = new SessionCipher(signalProtocolStore, destination);
        PushTransportDetails transportDetails     = new PushTransportDetails(sessionCipher.getSessionVersion());
        CiphertextMessage    message              = sessionCipher.encrypt(transportDetails.getPaddedMessageBody(unpaddedMessage));
        int                  remoteRegistrationId = sessionCipher.getRemoteRegistrationId();
        String               body                 = Base64.encodeBytes(message.serialize());

        int type;

        switch (message.getType()) {
          case CiphertextMessage.PREKEY_TYPE:  type = Type.PREKEY_BUNDLE_VALUE; break;
          case CiphertextMessage.WHISPER_TYPE: type = Type.CIPHERTEXT_VALUE;    break;
          default: throw new AssertionError("Bad type: " + message.getType());
        }

        return new OutgoingPushMessage(type, destination.getDeviceId(), remoteRegistrationId, body);
      }
    } else {
      String body = Base64.encodeBytes(unpaddedMessage);
      return new OutgoingPushMessage(Type.UNKNOWN_VALUE, destination.getDeviceId(), 0/*remoteRegistrationId*/,
                                     body);
    }
  }

  /**
   * Decrypt a received {@link SignalServiceEnvelope}
   *
   * @param envelope The received SignalServiceEnvelope
   *
   * @return a decrypted SignalServiceContent
   * @throws InvalidVersionException
   *    * @throws InvalidMessageException
   *    * @throws InvalidKeyException
   *    * @throws DuplicateMessageException
   *    * @throws InvalidKeyIdException
   *    * @throws UntrustedIdentityException
   *    * @throws LegacyMessageException
   *    * @throws NoSessionException
   */
  public SignalServiceContent decrypt(SignalServiceEnvelope envelope)
          throws InvalidMetadataMessageException, InvalidMetadataVersionException,
          ProtocolInvalidKeyIdException, ProtocolLegacyMessageException,
          ProtocolUntrustedIdentityException, ProtocolNoSessionException,
          ProtocolInvalidVersionException, ProtocolInvalidMessageException,
          ProtocolInvalidKeyException, ProtocolDuplicateMessageException,
          SelfSendException, UnsupportedDataMessageException

  {
    try {
      if (envelope.hasLegacyMessage()) {
        DataMessage message;
        if (envelope.isUfsrvMessage()) {
          Log.d(TAG, String.format("decrypt: server response path -> FOUND UFSRV MESSAGE WITH LEGACY CONTENT: NOT DECRYPTING"));
          Plaintext plaintext = decrypt(envelope, envelope.getLegacyMessage(), false);
          message = DataMessage.parseFrom(plaintext.getData());
          return new SignalServiceContent(createSignalServiceMessage(plaintext.getMetadata(), envelope, message), null, 0, 0, false);// todo: fix undefine args
        } else {
          Log.d(TAG, String.format("decrypt: original path -> FOUND NON-UFSRV MESSAGE WITH LEGACY CONTENT"));
          Plaintext plaintext = decrypt(envelope, envelope.getLegacyMessage(), false); // false
          message = DataMessage.parseFrom(plaintext.getData());
          return new SignalServiceContent(createSignalServiceMessage(plaintext.getMetadata(), envelope, message),
                                          plaintext.getMetadata().getSender(),
                                          plaintext.getMetadata().getSenderDevice(),
                                          plaintext.getMetadata().getTimestamp(),
                                          plaintext.getMetadata().isNeedsReceipt());
        }
      } else if (envelope.hasContent()) {
        // usfrv doesnt populate the content field on device bound
        //
        Content   message;
        Plaintext plaintext = null;

        if (envelope.isUfsrvMessage()) {
          //should not possible anymore?
          Log.d(TAG, String.format("decrypt: FOUND UFSRV MESSAGE WITH NON-LEGACY CONTENT: NOT DECRYPTING"));
          message = Content.parseFrom(decrypt/*Flagged*/(envelope, envelope.getContent(), false).getData());
        } else {
          Log.d(TAG, String.format("decrypt: defaulting to original path -> FOUND NON-UFSRV MESSAGE WITH NON-LEGACY CONTENT"));
          plaintext = decrypt(envelope, envelope.getContent(), false);
          message = Content.parseFrom(plaintext.getData());
        }

        if (message.hasDataMessage()) {
          return new SignalServiceContent(createSignalServiceMessage(plaintext.getMetadata(), envelope, message.getDataMessage()),
                                          plaintext.getMetadata().getSender(),
                                          plaintext.getMetadata().getSenderDevice(),
                                          plaintext.getMetadata().getTimestamp(),
                                          plaintext.getMetadata().isNeedsReceipt());
        } else if (message.hasSyncMessage() && localAddress.getNumber().equals(plaintext.getMetadata().getSender())) {
          return new SignalServiceContent(createSynchronizeMessage(plaintext.getMetadata(), envelope, message.getSyncMessage()), // retained envelope
                                          plaintext.getMetadata().getSender(),
                                          plaintext.getMetadata().getSenderDevice(),
                                          plaintext.getMetadata().getTimestamp(),
                                          plaintext.getMetadata().isNeedsReceipt());
        } else if (message.hasCallMessage()) {
          return new SignalServiceContent(createCallMessage(null/*message.getCallMessage()*/), // null
                                          plaintext.getMetadata().getSender(),
                                          plaintext.getMetadata().getSenderDevice(),
                                          plaintext.getMetadata().getTimestamp(),
                                          plaintext.getMetadata().isNeedsReceipt());
        } else if (message.hasReceiptMessage()) {
          return new SignalServiceContent(createReceiptMessage(plaintext.getMetadata(), message.getReceiptMessage()),
                                          plaintext.getMetadata().getSender(),
                                          plaintext.getMetadata().getSenderDevice(),
                                          plaintext.getMetadata().getTimestamp(),
                                          plaintext.getMetadata().isNeedsReceipt());
        } else if (message.hasTypingMessage()) {
          return new SignalServiceContent(createTypingMessage(plaintext.getMetadata(), message.getTypingMessage()),
                                          plaintext.getMetadata().getSender(),
                                          plaintext.getMetadata().getSenderDevice(),
                                          plaintext.getMetadata().getTimestamp(),
                                          false);
        }
      } else {
        // this ufrsv Direct own push ie not in reaction to this client's previous command
        Log.d(TAG, String.format("decrypt: FOUND NATIVE UFSRV PUSH MESSAGE..."));
        boolean isNeedsreceipt = envelope.getUfsrvType()==UfsrvCommand.Type.MESSAGE.ordinal();
        return new SignalServiceContent(createSignalServiceMessage(envelope, null),null, 0, 0, isNeedsreceipt);// todo: fix undefined args inline with presealed version
      }
      //

      return null;
    } catch (InvalidProtocolBufferException e) {
      throw new InvalidMetadataMessageException(e);
    }
  }

  private Plaintext decrypt(SignalServiceEnvelope envelope, byte[] ciphertext,  boolean decrypt) // bool
          throws InvalidMetadataMessageException, InvalidMetadataVersionException,
          ProtocolDuplicateMessageException, ProtocolUntrustedIdentityException,
          ProtocolLegacyMessageException, ProtocolInvalidKeyException,
          ProtocolInvalidVersionException, ProtocolInvalidMessageException,
          ProtocolInvalidKeyIdException, ProtocolNoSessionException,
          SelfSendException
  {
    if (decrypt) { //
      try {
        SignalProtocolAddress sourceAddress = new SignalProtocolAddress(envelope.getSource(), envelope.getSourceDevice());
        SessionCipher sessionCipher = new SessionCipher(signalProtocolStore, sourceAddress);
        SealedSessionCipher sealedSessionCipher = new SealedSessionCipher(signalProtocolStore, new SignalProtocolAddress(localAddress.getNumber(), 1));

        byte[] paddedMessage;
        Metadata metadata;
        int sessionVersion;

        if (envelope.isPreKeySignalMessage()) {
          Log.d(TAG, ">>decrypt: decrypting a PreKeySignalMessage");
          paddedMessage = sessionCipher.decrypt(new PreKeySignalMessage(ciphertext));
          metadata = new Metadata(envelope.getSource(), envelope.getSourceDevice(), envelope.getTimestamp(), false);
          sessionVersion = sessionCipher.getSessionVersion();
        }
        else if (envelope.isSignalMessage()) {
          Log.d(TAG, ">>decrypt: decrypting a SignalMessage");
          paddedMessage = sessionCipher.decrypt(new SignalMessage(ciphertext));
          metadata = new Metadata(envelope.getSource(), envelope.getSourceDevice(), envelope.getTimestamp(), false);
          sessionVersion = sessionCipher.getSessionVersion();
        }
        else if (envelope.isUnidentifiedSender()) {
          Pair<SignalProtocolAddress, byte[]> results = sealedSessionCipher.decrypt(certificateValidator, ciphertext, envelope.getServerTimestamp());
          paddedMessage = results.second();
          metadata = new Metadata(results.first().getName(), results.first().getDeviceId(), envelope.getTimestamp(), true);
          sessionVersion = sealedSessionCipher.getSessionVersion(new SignalProtocolAddress(metadata.getSender(), metadata.getSenderDevice()));
        }
        else {
          throw new InvalidMetadataMessageException("Unknown type: " + envelope.getType());
        }

        PushTransportDetails transportDetails = new PushTransportDetails(sessionVersion);
        byte[] data = transportDetails.getStrippedPaddingMessageBody(paddedMessage);

        return new Plaintext(metadata, data);
      }
      catch (DuplicateMessageException e) {
        throw new ProtocolDuplicateMessageException(e, envelope.getSource(), envelope.getSourceDevice());
      }
      catch (LegacyMessageException e) {
        throw new ProtocolLegacyMessageException(e, envelope.getSource(), envelope.getSourceDevice());
      }
      catch (InvalidMessageException e) {
        throw new ProtocolInvalidMessageException(e, envelope.getSource(), envelope.getSourceDevice());
      }
      catch (InvalidKeyIdException e) {
        throw new ProtocolInvalidKeyIdException(e, envelope.getSource(), envelope.getSourceDevice());
      }
      catch (InvalidKeyException e) {
        throw new ProtocolInvalidKeyException(e, envelope.getSource(), envelope.getSourceDevice());
      }
      catch (UntrustedIdentityException e) {
        throw new ProtocolUntrustedIdentityException(e, envelope.getSource(), envelope.getSourceDevice());
      }
      catch (InvalidVersionException e) {
        throw new ProtocolInvalidVersionException(e, envelope.getSource(), envelope.getSourceDevice());
      }
      catch (NoSessionException e) {
        throw new ProtocolNoSessionException(e, envelope.getSource(), envelope.getSourceDevice());
      }
    }

    //
    Log.d(TAG, ">>decrypt: Not decrypting...");
    Metadata metadata = new Metadata(envelope.getSource(), envelope.getSourceDevice(), envelope.getTimestamp(), false);
    return new Plaintext(metadata, ciphertext);
    //
  }

  //presealed version
  private SignalServiceDataMessage createSignalServiceMessage(Metadata metadata, SignalServiceEnvelope envelope, DataMessage content)
          throws ProtocolInvalidMessageException, UnsupportedDataMessageException
  {
    SignalServiceGroup             groupInfo        = createGroupInfo(content);
    List<SignalServiceAttachment>  attachments      = new LinkedList<>();
    boolean                        endSession       = ((content.getFlags() & DataMessage.Flags.END_SESSION_VALUE            ) != 0);
    boolean                        expirationUpdate = ((content.getFlags() & DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE) != 0);
    boolean                        profileKeyUpdate = ((content.getFlags() & DataMessage.Flags.PROFILE_KEY_UPDATE_VALUE     ) != 0);
    SignalServiceDataMessage.Quote quote            = createQuote(envelope);
    List<SharedContact>            sharedContacts   = createSharedContacts(envelope);
    List<Preview>                  previews         = createPreviews(envelope);
    Sticker                        sticker          = createSticker(content);

    if (content.getRequiredProtocolVersion() > DataMessage.ProtocolVersion.CURRENT.getNumber()) {
      throw new UnsupportedDataMessageException(DataMessage.ProtocolVersion.CURRENT.getNumber(),
                                                content.getRequiredProtocolVersion(),
                                                metadata.getSender(),
                                                metadata.getSenderDevice(),
                                                Optional.fromNullable(groupInfo));
    }

    for (AttachmentPointer pointer : content.getAttachmentsList()) {
      attachments.add(createAttachmentPointer(pointer));
    }

    if (content.hasTimestamp() && content.getTimestamp() != metadata.getTimestamp()) {
      //- todo: this is not supported at serverside yet
//      throw new ProtocolInvalidMessageException(new InvalidMessageException("Timestamps don't match: " + content.getTimestamp() + " vs " + metadata.getTimestamp()),
//                                                metadata.getSender(),
//                                                metadata.getSenderDevice());
    }

    return new SignalServiceDataMessage(metadata.getTimestamp(),
                                        groupInfo,
                                        attachments,
                                        content.getBody(),
                                        endSession,
                                        content.getExpireTimer(),
                                        expirationUpdate,
                                        content.hasProfileKey() ? content.getProfileKey().toByteArray() : null,
                                        profileKeyUpdate,
                                        quote,
                                        sharedContacts,
                                        previews,
                                        sticker,
                                        content.getMessageTimer(),
                                        Optional.fromNullable(envelope.getUfsrvCommand()));//  extra constructor params);
  }

  // todo: port to sealed sender
  // alternative push message path, based on native UFSRV PUSH ie not in response to client initiated message
  private SignalServiceDataMessage createSignalServiceMessage(SignalServiceEnvelope envelope, DataMessage message)
          throws ProtocolInvalidMessageException, UnsupportedDataMessageException
  {
    SignalServiceGroup            groupInfo        = createGroupInfo(envelope);//, content);
    List<SignalServiceAttachment> attachments      = new LinkedList<>();
    SignalServiceDataMessage.Quote quote            = createQuote(envelope);
    List<SharedContact>            sharedContacts   = createSharedContacts(envelope);
    List<Preview>                  previews         = createPreviews(envelope);
    Sticker                        sticker          = createSticker(envelope);
    // look into if endSession required any more in the context of this path. Expiry is encapsulated into its own fence command
    //boolean                       endSession       = ((content.getFlags() & DataMessage.Flags.END_SESSION_VALUE) != 0);

    if (message != null) {
      if (message.getRequiredProtocolVersion() > DataMessage.ProtocolVersion.CURRENT.getNumber()) {
        throw new UnsupportedDataMessageException(DataMessage.ProtocolVersion.CURRENT.getNumber(),
                                                  message.getRequiredProtocolVersion(),
                                                  envelope.getSourceAddress().getNumber(),
                                                  envelope.getSourceDevice(),
                                                  Optional.fromNullable(groupInfo));
      }
    }

    for (SignalServiceProtos.AttachmentRecord pointer : UfsrvCommandUtils.getAttachments(envelope.getUfsrvCommand())) {
      attachments.add(new SignalServiceAttachmentPointer(pointer.getId(), 0,//  ufid as String
                                                        pointer.getContentType(),
                                                        pointer.getKey().toByteArray(),
                                                        pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.<Integer>absent(),
                                                        pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.<byte[]>absent(),
                                                         pointer.getWidth(), pointer.getHeight(),
                                                         pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.<byte[]>absent(),
                                                         pointer.hasFileName() ? Optional.of(pointer.getFileName()) : Optional.<String>absent(),
                                                         (pointer.getFlags() & AttachmentPointer.Flags.VOICE_MESSAGE_VALUE) != 0,
                                                                pointer.hasCaption() ? Optional.of(pointer.getCaption()) : Optional.<String>absent()));
    }


    // support for ufsrvCommand
    return new SignalServiceDataMessage(envelope.getTimestamp(),
                                        groupInfo,
                                        attachments,
                                  null,
                                        false,
                                        (int)(getExpireTimerIfSet(envelope)/1000)/*content.getExpireTimer()*/,
                          false,
                                        null,
                                        false,
                                        quote,
                                        sharedContacts,
                                        previews,
                                        sticker,
                                        0,
                                        Optional.of(envelope.getUfsrvCommand()));//  extra constructor params
  }

  //This is only set for for inbound message commands
  private long getExpireTimerIfSet(SignalServiceEnvelope envelope)
  {
    SignalServiceProtos.MessageCommand messageCommand;
    if (envelope.getUfsrvCommand() !=null && envelope.getUfsrvCommand().getUfsrvtype() == UfsrvCommandWire.UfsrvType.UFSRV_MESSAGE) {
      messageCommand = envelope.getUfsrvCommand().getMsgCommand();
      if (messageCommand.getHeader().getCommand() == SignalServiceProtos.MessageCommand.CommandTypes.SAY_VALUE) return messageCommand.getFences(0).getExpireTimer();
    }

    return 0;
  }
//

  private SignalServiceSyncMessage createSynchronizeMessage(Metadata metadata, SignalServiceEnvelope envelope, SyncMessage content) // envelope
          throws ProtocolInvalidMessageException, ProtocolInvalidKeyException, UnsupportedDataMessageException
  {
    if (content.hasSent()) {
      SyncMessage.Sent     sentContent          = content.getSent();
      Map<String, Boolean> unidentifiedStatuses = new HashMap<>();

      for (SyncMessage.Sent.UnidentifiedDeliveryStatus status : sentContent.getUnidentifiedStatusList()) {
        unidentifiedStatuses.put(status.getDestination(), status.getUnidentified());
      }

      return SignalServiceSyncMessage.forSentTranscript(new SentTranscriptMessage(sentContent.getDestination(),
                                                                                  sentContent.getTimestamp(),
                                                                                  createSignalServiceMessage(metadata, envelope, sentContent.getMessage()), // envelope
                                                                                  sentContent.getExpirationStartTimestamp(),
                                                                                  unidentifiedStatuses,
                                                                                  sentContent.getIsRecipientUpdate()));
    }

    if (content.hasRequest()) {
      return SignalServiceSyncMessage.forRequest(new RequestMessage(content.getRequest()));
    }

    if (content.getReadList().size() > 0) {
      List<ReadMessage> readMessages = new LinkedList<>();

      for (SyncMessage.Read read : content.getReadList()) {
        readMessages.add(new ReadMessage(read.getSender(), read.getTimestamp()));
      }

      return SignalServiceSyncMessage.forRead(readMessages);
    }

    if (content.hasMessageTimerRead()) {
      MessageTimerReadMessage timerRead = new MessageTimerReadMessage(content.getMessageTimerRead().getSender(),
                                                                      content.getMessageTimerRead().getTimestamp());
      return SignalServiceSyncMessage.forMessageTimerRead(timerRead);
    }

    if (content.hasVerified()) {
      try {
        Verified    verified    = content.getVerified();
        String      destination = verified.getDestination();
        IdentityKey identityKey = new IdentityKey(verified.getIdentityKey().toByteArray(), 0);

        VerifiedState verifiedState;

        if (verified.getState() == Verified.State.DEFAULT) {
          verifiedState = VerifiedState.DEFAULT;
        } else if (verified.getState() == Verified.State.VERIFIED) {
          verifiedState = VerifiedState.VERIFIED;
        } else if (verified.getState() == Verified.State.UNVERIFIED) {
          verifiedState = VerifiedState.UNVERIFIED;
        } else {
          throw new ProtocolInvalidMessageException(new InvalidMessageException("Unknown state: " + verified.getState().getNumber()),
                                                    metadata.getSender(), metadata.getSenderDevice());
        }

        return SignalServiceSyncMessage.forVerified(new VerifiedMessage(destination, identityKey, verifiedState, System.currentTimeMillis()));
      } catch (InvalidKeyException e) {
        throw new ProtocolInvalidKeyException(e, metadata.getSender(), metadata.getSenderDevice());
      }
    }

    if (content.getStickerPackOperationList().size() > 0) {
      List<StickerPackOperationMessage> operations = new LinkedList<>();

      for (SyncMessage.StickerPackOperation operation : content.getStickerPackOperationList()) {
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

    return SignalServiceSyncMessage.empty();
  }

  // slightly adpated from original.. may be factored out eventually
  private SignalServiceCallMessage createCallMessage(CallCommand content) {
    Recipient recipient;
    long        groupId;

    if (!content.hasFence() && !content.hasOriginator())
    {
      Log.e(TAG, String.format("createCallMessage: CallCommand doesnt have key fields provided (fence:'%b', originator:'%b', to:'%b')", content.hasFence(), content.hasOriginator(), content.getToCount()>0));
      return null;
    }

    groupId     = content.getFence().getFid();
    recipient  = Recipient.from(ApplicationContext.getInstance(), Address.fromSerialized(UfsrvUid.EncodedfromSerialisedBytes(content.getOriginator().getUfsrvuid().toByteArray())), false);
    Log.d(TAG, String.format("createCallMessage: CallCommand Received (fence:'%d', originator:'%d', command:'%d')", groupId, content.hasOriginator(), content.getHeader().getCommand()));

    if (content.hasOffer()) {
      CallCommand.Offer offerContent = content.getOffer();
      return SignalServiceCallMessage.forOffer(new OfferMessage(offerContent.getId(), offerContent.getDescription(), recipient, groupId));
    } else if (content.hasAnswer()) {
      CallCommand.Answer answerContent = content.getAnswer();
      return SignalServiceCallMessage.forAnswer(new AnswerMessage(answerContent.getId(), answerContent.getDescription(), recipient, groupId));
    } else if (content.getIceUpdateCount() > 0) {
      List<IceUpdateMessage> iceUpdates = new LinkedList<>();

      for (CallCommand.IceUpdate iceUpdate : content.getIceUpdateList()) {
        iceUpdates.add(new IceUpdateMessage(iceUpdate.getId(), iceUpdate.getSdpMid(), iceUpdate.getSdpMLineIndex(), iceUpdate.getSdp(), recipient, groupId));
      }

      return SignalServiceCallMessage.forIceUpdates(iceUpdates);
    } else if (content.hasHangup()) {
      CallCommand.Hangup hangup = content.getHangup();
      return SignalServiceCallMessage.forHangup(new HangupMessage(hangup.getId(), recipient, groupId));
    } else if (content.hasBusy()) {
      CallCommand.Busy busy = content.getBusy();
      return SignalServiceCallMessage.forBusy(new BusyMessage(busy.getId(), recipient, groupId));
    }

    return SignalServiceCallMessage.empty();
  }

//  private SignalServiceReceiptMessage createReceiptMessage(SignalServiceEnvelope envelope, ReceiptMessage content) {
  private SignalServiceReceiptMessage createReceiptMessage(Metadata metadata, ReceiptMessage content) {
    SignalServiceReceiptMessage.Type type;

    if      (content.getType() == ReceiptMessage.Type.DELIVERY) type = SignalServiceReceiptMessage.Type.DELIVERY;
    else if (content.getType() == ReceiptMessage.Type.READ)     type = SignalServiceReceiptMessage.Type.READ;
    else                                                        type = SignalServiceReceiptMessage.Type.UNKNOWN;

    return new SignalServiceReceiptMessage(type, content.getTimestampList(), metadata.getTimestamp(), null);// last arg
  }

  private SignalServiceTypingMessage createTypingMessage(Metadata metadata, TypingMessage content) throws ProtocolInvalidMessageException {
    SignalServiceTypingMessage.Action action;

    if      (content.getAction() == TypingMessage.Action.STARTED) action = SignalServiceTypingMessage.Action.STARTED;
    else if (content.getAction() == TypingMessage.Action.STOPPED) action = SignalServiceTypingMessage.Action.STOPPED;
    else                                                          action = SignalServiceTypingMessage.Action.UNKNOWN;

    if (content.hasTimestamp() && content.getTimestamp() != metadata.getTimestamp()) {
      throw new ProtocolInvalidMessageException(new InvalidMessageException("Timestamps don't match: " + content.getTimestamp() + " vs " + metadata.getTimestamp()),
                                                metadata.getSender(),
                                                metadata.getSenderDevice());
    }

    return new SignalServiceTypingMessage(action, content.getTimestamp(),
                                          content.hasGroupId() ? Optional.of(content.getGroupId().toByteArray()) :
                                          Optional.<byte[]>absent());
  }

  private SignalServiceDataMessage.Quote createQuote(SignalServiceEnvelope envelope, DataMessage content) {
    if (!content.hasQuote()) return null;

    List<SignalServiceDataMessage.Quote.QuotedAttachment> attachments = new LinkedList<>();

    for (DataMessage.Quote.QuotedAttachment attachment : content.getQuote().getAttachmentsList()) {
      attachments.add(new SignalServiceDataMessage.Quote.QuotedAttachment(attachment.getContentType(),
                                                                          attachment.getFileName(),
                                                                          attachment.hasThumbnail() ? createAttachmentPointer(attachment.getThumbnail()) : null));
    }

    return new SignalServiceDataMessage.Quote(content.getQuote().getId(),
                                              new SignalServiceAddress(content.getQuote().getAuthor()),
                                              content.getQuote().getText(),
                                              attachments);
  }

  // replaces above: adapted to work withQuotedAttachment/QuotedAttachmentRecord/AttachmentRecord
  private SignalServiceDataMessage.Quote createQuote(SignalServiceEnvelope envelope) {
    SignalServiceProtos.MessageCommand messageCommand = envelope.getMessageCommand();
    if (messageCommand==null || !messageCommand.hasQuotedMessage()) return null;

    List<SignalServiceDataMessage.Quote.QuotedAttachment> attachments = new LinkedList<>();

    for (SignalServiceProtos.QuotedMessageRecord.QuotedAttachment attachment : messageCommand.getQuotedMessage().getAttachmentsList()) {
      attachments.add(new SignalServiceDataMessage.Quote.QuotedAttachment(attachment.getContentType(),
                                                                          attachment.getFileName(),
                                                                          attachment.hasThumbnail() ? createAttachmentPointer(attachment.getThumbnail()) : null));
    }

    return new SignalServiceDataMessage.Quote( messageCommand.getQuotedMessage().getId(),
                                              new SignalServiceAddress( messageCommand.getQuotedMessage().getAuthor()),
                                               messageCommand.getQuotedMessage().getText(),
                                              attachments);
  }

  //defunct see below
  private List<Preview> createPreviews(DataMessage content) {
    if (content.getPreviewCount() <= 0) return null;

    List<Preview> results = new LinkedList<>();

    for (DataMessage.Preview preview : content.getPreviewList()) {
      SignalServiceAttachment attachment = null;

      if (preview.hasImage()) {
        attachment = createAttachmentPointer(preview.getImage());
      }

      results.add(new Preview(preview.getUrl(),
                              preview.getTitle(),
                              Optional.fromNullable(attachment)));
    }

    return results;
  }

  //replaces above
  private List<Preview> createPreviews(SignalServiceEnvelope envelope) {
    SignalServiceProtos.MessageCommand msgCommand = envelope.getMessageCommand();
    if (msgCommand!=null) {
      if (msgCommand.getPreviewCount()>0) {
        List<Preview> results = new LinkedList<>();

        for (SignalServiceProtos.PreviewRecord preview : msgCommand.getPreviewList()) {
          SignalServiceAttachment attachment = null;
          if (preview.hasImage()) {
            attachment = createAttachmentPointer(preview.getImage());
            results.add(new Preview(preview.getUrl(),
                              preview.getTitle(),
                              Optional.fromNullable(attachment)));
          }
        }

        return results;
      }
    }
//    if (content.getPreviewCount() <= 0) return null;
//
//    List<Preview> results = new LinkedList<>();
//
//    for (DataMessage.Preview preview : content.getPreviewList()) {
//      SignalServiceAttachment attachment = null;
//
//      if (preview.hasImage()) {
//        attachment = createAttachmentPointer(preview.getImage());
//      }
//
//      results.add(new Preview(preview.getUrl(),
//                              preview.getTitle(),
//                              Optional.fromNullable(attachment)));
//    }
//
//    return results;
    return null;
  }

  //defunct see below
  private List<SharedContact> createSharedContacts(SignalServiceEnvelope envelope, DataMessage content) {
    if (content.getContactCount() <= 0) return null;

    List<SharedContact> results = new LinkedList<>();

    for (DataMessage.Contact contact : content.getContactList()) {
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
        for (DataMessage.Contact.PostalAddress address : contact.getAddressList()) {
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
        for (DataMessage.Contact.Phone phone : contact.getNumberList()) {
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
        for (DataMessage.Contact.Email email : contact.getEmailList()) {
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

      results.add(builder.build());
    }

    return results;
  }

  // replaces above
  private List<SharedContact> createSharedContacts(SignalServiceEnvelope envelope) {
    SignalServiceProtos.MessageCommand userCommand = envelope.getMessageCommand();
    if (userCommand == null || userCommand.getContactsCount() == 0) return null;

    List<SharedContact> results = new LinkedList<>();

    for (SignalServiceProtos.ContactRecordOrBuilder contact : userCommand.getContactsList()) {
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
        for (SignalServiceProtos.ContactRecord.PostalAddress address : contact.getAddressList()) {
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
        for (SignalServiceProtos.ContactRecord.Phone phone : contact.getNumberList()) {
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
        for (SignalServiceProtos.ContactRecord.Email email : contact.getEmailList()) {
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

  //defunct see below
  private Sticker createSticker(DataMessage content) {
    if (!content.hasSticker()                ||
            !content.getSticker().hasPackId()    ||
            !content.getSticker().hasPackKey()   ||
            !content.getSticker().hasStickerId() ||
            !content.getSticker().hasData())
    {
      return null;
    }

    DataMessage.Sticker sticker = content.getSticker();

    return new Sticker(sticker.getPackId().toByteArray(),
                       sticker.getPackKey().toByteArray(),
                       sticker.getStickerId(),
                       createAttachmentPointer(sticker.getData()));
  }

  private Sticker createSticker(SignalServiceEnvelope envelope) {
    SignalServiceProtos.MessageCommand msgCommand = envelope.getMessageCommand();
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
                       createAttachmentPointer(sticker.getData()));
  }

  private SignalServiceAttachmentPointer createAttachmentPointer( AttachmentPointer pointer) {
    return new SignalServiceAttachmentPointer(pointer.getUfid(), pointer.getId(),//  ufid
                                              pointer.getContentType(),
                                              pointer.getKey().toByteArray(),
                                              pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.<Integer>absent(),
                                              pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.<byte[]>absent(),
                                              pointer.getWidth(), pointer.getHeight(),
                                              pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.<byte[]>absent(),
                                              pointer.hasFileName() ? Optional.of(pointer.getFileName()) : Optional.<String>absent(),
                                              (pointer.getFlags() & AttachmentPointer.Flags.VOICE_MESSAGE_VALUE) != 0,
                                              pointer.hasCaption() ? Optional.of(pointer.getCaption()) : Optional.<String>absent());

  }

  // works for AttachmentRecord
  private SignalServiceAttachmentPointer createAttachmentPointer(SignalServiceProtos.AttachmentRecord pointer) {
    return new SignalServiceAttachmentPointer(pointer.getId(),0,
                                              pointer.getContentType(),
                                              pointer.getKey().toByteArray(),
                                              pointer.hasSize() ? Optional.of(pointer.getSize()) : Optional.<Integer>absent(),
                                              pointer.hasThumbnail() ? Optional.of(pointer.getThumbnail().toByteArray()): Optional.<byte[]>absent(),
                                              pointer.getWidth(), pointer.getHeight(),
                                              pointer.hasDigest() ? Optional.of(pointer.getDigest().toByteArray()) : Optional.<byte[]>absent(),
                                              pointer.hasFileName() ? Optional.of(pointer.getFileName()) : Optional.<String>absent(),
                                              (pointer.getFlags() & AttachmentPointer.Flags.VOICE_MESSAGE_VALUE) != 0,
                                              pointer.hasCaption() ? Optional.of(pointer.getCaption()) : Optional.<String>absent());

  }

  //- see above highly adapted...
//  private SignalServiceCallMessage createCallMessage(CallMessage content) {
//    if (content.hasOffer()) {
//      CallMessage.Offer offerContent = content.getOffer();
//      return SignalServiceCallMessage.forOffer(new OfferMessage(offerContent.getEncodedId(), offerContent.getDescription()));
//    } else if (content.hasAnswer()) {
//      CallMessage.Answer answerContent = content.getAnswer();
//      return SignalServiceCallMessage.forAnswer(new AnswerMessage(answerContent.getEncodedId(), answerContent.getDescription()));
//    } else if (content.getIceUpdateCount() > 0) {
//      List<IceUpdateMessage> iceUpdates = new LinkedList<>();
//
//      for (CallMessage.IceUpdate iceUpdate : content.getIceUpdateList()) {
//        iceUpdates.add(new IceUpdateMessage(iceUpdate.getEncodedId(), iceUpdate.getSdpMid(), iceUpdate.getSdpMLineIndex(), iceUpdate.getSdp()));
//      }
//
//      return SignalServiceCallMessage.forIceUpdates(iceUpdates);
//    } else if (content.hasHangup()) {
//      CallMessage.Hangup hangup = content.getHangup();
//      return SignalServiceCallMessage.forHangup(new HangupMessage(hangup.getEncodedId()));
//    } else if (content.hasBusy()) {
//      CallMessage.Busy busy = content.getBusy();
//      return SignalServiceCallMessage.forBusy(new BusyMessage(busy.getEncodedId()));
//    }
//
//    return SignalServiceCallMessage.empty();
//  }

  private SignalServiceGroup createGroupInfo(DataMessage content) {
    if (!content.hasGroup()) return null;

    SignalServiceGroup.Type type;

    switch (content.getGroup().getType()) {
      case DELIVER:      type = SignalServiceGroup.Type.DELIVER;      break;
      case UPDATE:       type = SignalServiceGroup.Type.UPDATE;       break;
      case QUIT:         type = SignalServiceGroup.Type.QUIT;         break;
      case REQUEST_INFO: type = SignalServiceGroup.Type.REQUEST_INFO; break;
      default:           type = SignalServiceGroup.Type.UNKNOWN;      break;
    }

    if (content.getGroup().getType() != DELIVER) {
      String                      name    = null;
      List<String>                members = null;
      SignalServiceAttachmentPointer avatar  = null;

      if (content.getGroup().hasName()) {
        name = content.getGroup().getName();
      }

      if (content.getGroup().getMembersCount() > 0) {
        members = content.getGroup().getMembersList();
      }

      if (content.getGroup().hasAvatar()) {
        AttachmentPointer pointer = content.getGroup().getAvatar();
        avatar = new SignalServiceAttachmentPointer(pointer.getUfid(),// ufid all attachments must include this
                                                    pointer.getId(),
                                                    pointer.getContentType(),
                                                    pointer.getKey().toByteArray(),
                                                    Optional.of(pointer.getSize()),
                                                    Optional.<byte[]>absent(), 0, 0,
                                                    Optional.fromNullable(pointer.hasDigest() ? pointer.getDigest().toByteArray() : null),
                                                    Optional.<String>absent(),
                                                    false,
                                                    Optional.<String>absent());

      }

      return new SignalServiceGroup(type, content.getGroup().getId().toByteArray(), name, members, avatar);
    }

    return new SignalServiceGroup(content.getGroup().getId().toByteArray());
  }

  //
  //Alternative to above, native to ufsrv; ie no 'DataMessage content' proto
  private SignalServiceGroup createGroupInfo(SignalServiceEnvelope envelope) {
    if (envelope.getUfsrvType()!= SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE_VALUE) return null;

    FenceRecord fenceRecord=null;
    if (envelope.getFenceCommand().getFencesCount()>0)
    {
      byte[] groupId = null;
      fenceRecord = envelope.getFenceCommand().getFences(0);
      String groupIdEncoded = DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance()).getGroupId(fenceRecord.getFid(), null, false);

      if (groupIdEncoded!=null) {
        try {
          groupId = GroupUtil.getDecodedId(groupIdEncoded);
        }
        catch (IOException ex) {
          Log.e(TAG, String.format(ex.getMessage()));
          return new SignalServiceGroup(null);
        }
      }
//      byte[] groupId=DatabaseFactory.getGroupDatabase(ApplicationContext.getInstance()).getGroupId(fenceRecord.getFid(), null, false);
      SignalServiceGroup.Type type;

//    switch (content.getGroupByCname().getType()) {
//      case DELIVER: type = SignalServiceGroup.Type.DELIVER; break;
//      case UPDATE:  type = SignalServiceGroup.Type.UPDATE;  break;
//      case QUIT:    type = SignalServiceGroup.Type.QUIT;    break;
//      default:      type = SignalServiceGroup.Type.UNKNOWN; break;
//    }

      //
      type = SignalServiceGroup.Type.UFSRV;

    /*if (content.getGroupByCname().getType() != DELIVER)*/
      //{
      String                          name;
      List<String>                    members = null;
      SignalServiceAttachmentPointer  avatar;

      /*if (content.getGroupByCname().hasName()) {
        name = content.getGroupByCname().getStylisedName();
      }*/
      name = fenceRecord.getFname();

      // we can build this model
     /* if (content.getGroupByCname().getMembersCount() > 0) {
        members = content.getGroupByCname().getMembersList();
      }*/


      /*if (content.getGroupByCname().hasAvatar()) {
        avatar = new SignalServiceAttachmentPointer(content.getGroupByCname().getAvatar().getUfid(),// ufid all attachments must include this
                content.getGroupByCname().getAvatar().getEncodedId(),
                content.getGroupByCname().getAvatar().getContentType(),
                content.getGroupByCname().getAvatar().getKey().toByteArray(),
                envelope.getRelay());
      }*/
      if (fenceRecord.hasAvatar())
      {
        avatar = new SignalServiceAttachmentPointer(fenceRecord.getAvatar().getId(),// ufid all attachments must include this
                                                    0,//this is the original id field which now replace by above
                                                    fenceRecord.getAvatar().getContentType(),
                                                    fenceRecord.getAvatar().getKey().toByteArray(),
                                                    Optional.of(fenceRecord.getAvatar().getSize()),
                                                    Optional.<byte[]>absent(),
                                                    fenceRecord.getAvatar().getWidth(),
                                                    fenceRecord.getAvatar().getHeight(),
                                                    Optional.fromNullable(fenceRecord.getAvatar().hasDigest() ? fenceRecord.getAvatar().getDigest().toByteArray() : null),
                                                    Optional.<String>absent(),
                                                    false,
                                                    Optional.<String>absent());

        //}
        return new SignalServiceGroup(type, groupId, name, members, avatar, envelope.getFenceCommand());
      }

      return new SignalServiceGroup(groupId/*content.getGroupByCname().getEncodedId().toByteArray()*/);
    }
    else
      return new SignalServiceGroup(null/*content.getGroupByCname().getEncodedId().toByteArray()*/);
  }

  private static class Metadata {
    private final String  sender;
    private final int     senderDevice;
    private final long    timestamp;
    private final boolean needsReceipt;

    private Metadata(String sender, int senderDevice, long timestamp, boolean needsReceipt) {
      this.sender       = sender;
      this.senderDevice = senderDevice;
      this.timestamp    = timestamp;
      this.needsReceipt = needsReceipt;
    }

    public String getSender() {
      return sender;
    }

    public int getSenderDevice() {
      return senderDevice;
    }

    public long getTimestamp() {
      return timestamp;
    }

    public boolean isNeedsReceipt() {
      return needsReceipt;
    }
  }

  private static class Plaintext {
    private final Metadata metadata;
    private final byte[]   data;

    private Plaintext(Metadata metadata, byte[] data) {
      this.metadata = metadata;
      this.data     = data;
    }

    public Metadata getMetadata() {
      return metadata;
    }

    public byte[] getData() {
      return data;
    }
  }
}

