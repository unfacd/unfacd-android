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
package org.whispersystems.signalservice.api;

import androidx.annotation.NonNull;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;

import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.SessionBuilder;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.state.PreKeyBundle;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.AttachmentCipherOutputStream;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccess;
import org.whispersystems.signalservice.api.crypto.UnidentifiedAccessPair;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.push.exceptions.EncapsulatedExceptions;
import org.whispersystems.signalservice.internal.configuration.SignalServiceConfiguration;
import org.whispersystems.signalservice.internal.crypto.PaddingInputStream;
import org.whispersystems.signalservice.internal.push.AttachmentUploadAttributes;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentStream;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceTypingMessage;
import org.whispersystems.signalservice.api.messages.calls.AnswerMessage;
import org.whispersystems.signalservice.api.messages.calls.IceUpdateMessage;
import org.whispersystems.signalservice.api.messages.calls.OfferMessage;
import org.whispersystems.signalservice.api.messages.calls.SignalServiceCallMessage;
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ConfigurationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.MessageTimerReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.ReadMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SentTranscriptMessage;
import org.whispersystems.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import org.whispersystems.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import org.whispersystems.signalservice.api.messages.multidevice.VerifiedMessage;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;

import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.AuthorizationFailedException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.UnregisteredUserException;
import org.whispersystems.signalservice.api.util.CredentialsProvider;
import org.whispersystems.signalservice.internal.push.MismatchedDevices;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessage;
import org.whispersystems.signalservice.internal.push.OutgoingPushMessageList;
import org.whispersystems.signalservice.internal.push.ProfileAvatarData;
import org.whispersystems.signalservice.internal.push.PushAttachmentData;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SendMessageResponse;
import org.whispersystems.signalservice.internal.push.SendMessageResponseList;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentPointer;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Content;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.DataMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.GroupContext;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.NullMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.TypingMessage;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Verified;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;
import org.whispersystems.signalservice.internal.push.StaleDevices;
import org.whispersystems.signalservice.internal.push.exceptions.MismatchedDevicesException;
import org.whispersystems.signalservice.internal.push.exceptions.StaleDevicesException;
import org.whispersystems.signalservice.internal.push.http.AttachmentCipherOutputStreamFactory;
import org.whispersystems.signalservice.internal.util.Base64;
import org.whispersystems.signalservice.internal.util.StaticCredentialsProvider;
import org.whispersystems.signalservice.internal.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The main interface for sending Signal Service messages.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceMessageSender {

  private static final String TAG = SignalServiceMessageSender.class.getSimpleName();

  private final PushServiceSocket                                   socket;
  private final SignalProtocolStore                                 store;
  private final SignalServiceAddress                                localAddress;
  private final Optional<EventListener>                             eventListener;

  private final AtomicReference<Optional<SignalServiceMessagePipe>> pipe;
  private final AtomicReference<Optional<SignalServiceMessagePipe>> unidentifiedPipe;
  private final AtomicBoolean                                       isMultiDevice;

  /**
   * Construct a SignalServiceMessageSender.
   *
   * @param urls The URL of the Signal Service.
   * @param user The Signal Service username (eg phone number).
   * @param password The Signal Service user password.
   * @param store The SignalProtocolStore.
   * @param eventListener An optional event listener, which fires whenever sessions are
   *                      setup or torn down for a recipient.
   */
  public SignalServiceMessageSender(SignalServiceConfiguration urls,
                                    String user, String password,
                                    SignalProtocolStore store,
                                    String userAgent,
                                    boolean isMultiDevice,
                                    Optional<SignalServiceMessagePipe> pipe,
                                    Optional<SignalServiceMessagePipe> unidentifiedPipe,
                                    Optional<EventListener> eventListener)
  {
    this(urls, new StaticCredentialsProvider(user, password, null), store, userAgent, isMultiDevice, pipe, unidentifiedPipe, eventListener);
  }

  public SignalServiceMessageSender(SignalServiceConfiguration urls,
                                    CredentialsProvider credentialsProvider,
                                    SignalProtocolStore store,
                                    String userAgent,
                                    boolean isMultiDevice,
                                    Optional<SignalServiceMessagePipe> pipe,
                                    Optional<SignalServiceMessagePipe> unidentifiedPipe,
                                    Optional<EventListener> eventListener)
  {
    this.socket           = new PushServiceSocket(urls, credentialsProvider, userAgent);
    this.store            = store;
    this.localAddress     = new SignalServiceAddress(credentialsProvider.getUser());
    this.pipe             = new AtomicReference<>(pipe);
    this.unidentifiedPipe = new AtomicReference<>(unidentifiedPipe);
    this.isMultiDevice    = new AtomicBoolean(isMultiDevice);
    this.eventListener    = eventListener;
  }

  /**
   * Send a read receipt for a received message.
   *
   * @param recipient The sender of the received message you're acknowledging.
   * @param message The read receipt to deliver.
   * @throws IOException
   * @throws UntrustedIdentityException
   */

  public void sendReceipt(SignalServiceAddress recipient,
                          Optional<UnidentifiedAccessPair> unidentifiedAccess,
                          SignalServiceReceiptMessage message,
                          UfsrvCommand ufCommand)
          throws IOException, UntrustedIdentityException
  {
    if (ufCommand==null) {
      Log.e(TAG, "sendReceipt: UfsrvCommand arg was null: RETURNING...");
      return;
    }

    byte[] content = createReceiptContent(message, ufCommand);// ufCommand

    sendMessage(recipient, getTargetUnidentifiedAccess(unidentifiedAccess), message.getWhen(), content, false,  ufCommand);
  }

  /**
   * Send a typing indicator.
   *
   * @param recipient The destination
   * @param message The typing indicator to deliver
   * @throws IOException
   * @throws UntrustedIdentityException
   */
  public void sendTyping(SignalServiceAddress recipient,
                         Optional<UnidentifiedAccessPair> unidentifiedAccess,
                         SignalServiceTypingMessage message)
          throws IOException, UntrustedIdentityException
  {
    byte[] content = createTypingContent(message);

    sendMessage(recipient, getTargetUnidentifiedAccess(unidentifiedAccess), message.getTimestamp(), content, true, null);
  }

  public void sendTyping(List<SignalServiceAddress>             recipients,
                         List<Optional<UnidentifiedAccessPair>> unidentifiedAccess,
                         SignalServiceTypingMessage             message)
          throws IOException
  {
    byte[] content = createTypingContent(message);
    sendMessage(recipients, getTargetUnidentifiedAccess(unidentifiedAccess), message.getTimestamp(), content, true, null);
  }

  /**
   * Send a call setup message to a single recipient.
   *
   * @param recipient The message's destination.
   * @param message The call message.
   * @throws IOException
   */
  public void sendCallMessage(SignalServiceAddress recipient,
                              Optional<UnidentifiedAccessPair> unidentifiedAccess,
                              SignalServiceCallMessage message,
                              UfsrvCommand ufCommand)
          throws IOException, UntrustedIdentityException
  {
    byte[] content = createCallContent(message, ufCommand);//
    sendMessage(recipient, getTargetUnidentifiedAccess(unidentifiedAccess), ufCommand.getCall().getHeader().getWhen(), content, false, ufCommand);
  }

  /**
   * Send a message to a single recipient.
   *
   * @param recipient The message's destination.
   * @param message The message.
   * @throws UntrustedIdentityException
   * @throws IOException
   */
  public SendMessageResult  sendMessage(SignalServiceAddress recipient,
                                        Optional<UnidentifiedAccessPair> unidentifiedAccess,
                                        SignalServiceDataMessage message,
                                        UfsrvCommand ufCommand)//
      throws UntrustedIdentityException, IOException, InvalidKeyException
  {

    byte[]              content   = createMessageContent(message, ufCommand, Optional.absent());
    long                timestamp = message.getTimestamp();
    boolean             silent    = message.getGroupInfo().isPresent() && message.getGroupInfo().get().getType() == SignalServiceGroup.Type.REQUEST_INFO;

    SendMessageResult result    = sendMessage(recipient, getTargetUnidentifiedAccess(unidentifiedAccess), timestamp, content,  false, ufCommand);

    if ((result.getSuccess() != null && result.getSuccess().isNeedsSync()) || (unidentifiedAccess.isPresent() && isMultiDevice.get())) {
      byte[] syncMessage = createMultiDeviceSentTranscriptContent(content, Optional.of(recipient), timestamp, Collections.singletonList(result), false);
      sendMessage(localAddress, Optional.<UnidentifiedAccess>absent(), timestamp, syncMessage,  false, ufCommand);
    }

    if (message.isEndSession()) {
      store.deleteAllSessions(recipient.getNumber());

      if (eventListener.isPresent()) {
        eventListener.get().onSecurityEvent(recipient);
      }
    }

    return result;
  }

  //TBD
/*  public void sendMessage(SignalServiceSyncMessage message)
          throws IOException, UntrustedIdentityException
  {
    byte[] content;

    Log.d(TAG, "sendMessage: Sending Multi device message");
    if (message.getContacts().isPresent()) {
      Log.d(TAG, "sendMessage: Sending Multi device message: contacts");
      content = createMultiDeviceContactsContent(message.getContacts().get().getContactsStream().asStream(),
                                                 message.getContacts().get().isComplete());
    } else if (message.getGroups().isPresent()) {
      Log.d(TAG, "sendMessage: Sending Multi device message: group");
      content = createMultiDeviceGroupsContent(message.getGroups().get().asStream());
    } else if (message.getRead().isPresent()) {
      Log.d(TAG, "sendMessage: Sending Multi device message: read content ");
      content = createMultiDeviceReadContent(message.getRead().get());
    } else if (message.getMessageTimerRead().isPresent()) {
      content = createMultiDeviceMessageTimerReadContent(message.getMessageTimerRead().get());
    } else if (message.getBlockedList().isPresent()) {
      Log.d(TAG, "sendMessage: Sending Multi device message: blocked");
      content = createMultiDeviceBlockedContent(message.getBlockedList().get());
    } else if (message.getConfiguration().isPresent()) {
      content = createMultiDeviceConfigurationContent(message.getConfiguration().get());
    } else if (message.getVerified().isPresent()) {
      sendMessage(message.getVerified().get(), Optional.absent());
      return;
    } else {
      throw new IOException("Unsupported sync message!");
    }

    sendMessage(localAddress, Optional.absent(), System.currentTimeMillis(), content, false, null); // ufcommand
  }*/

  /**
   * Send a message to a group.
   *
   * @param recipients The group members.(AA under current implementation this only includes the sender, not actual recipients list as per orig implementation)
   * @param message The group message.
   * @throws IOException
   */

  // highly specialised for e2ee MessageCommand only
  public LinkedList<SendMessageResult> sendMessage(List<SignalServiceAddress> recipients,
                                                    List<Optional<UnidentifiedAccessPair>> unidentifiedAccess,
                                                   boolean                                isRecipientUpdate,
                                                    SignalServiceDataMessage message,
                                                    UfsrvCommand ufCommand)
          throws IOException, EncapsulatedExceptions, InvalidKeyException
  {
    Pair<byte[], OutgoingPushMessageList> packagedContent;
    byte[]                                content;
    long                    timestamp = message.getTimestamp();
    SendMessageResult       result;
    SendMessageResponseList response  = new SendMessageResponseList();

    try {
      packagedContent = createMessageContent(message, ufCommand, Optional.of(recipients), timestamp,
                                             message.getBody().orNull());
      content = packagedContent.first();
    } catch (UntrustedIdentityException ex) {
      throw new EncapsulatedExceptions(new LinkedList<UntrustedIdentityException>(){{add(ex);}}, null, null);
    }

    try {
      result = sendMessage(recipients.get(0), packagedContent.second(), ufCommand);
    } catch (UntrustedIdentityException ex) {
      throw new EncapsulatedExceptions(new LinkedList<UntrustedIdentityException>(){{add(ex);}}, null, null);
    }

    try {
      if (response.getNeedsSync()) {
        byte[] syncMessage = createMultiDeviceSentTranscriptContent(content, Optional.<SignalServiceAddress>absent(), timestamp, new LinkedList<>(), isRecipientUpdate);
        sendMessage(localAddress, Optional.absent(), timestamp, syncMessage,  false, ufCommand);// ufcommand
      }
    } catch (UntrustedIdentityException e) {
      response.addException(e);
    }

    if (response.hasExceptions()) {
      throw new EncapsulatedExceptions(response.getUntrustedIdentities(), response.getUnregisteredUsers(), response.getNetworkExceptions());
    }

    return new LinkedList<SendMessageResult>(){{add(result);}};
  }

  public void sendMessage(SignalServiceSyncMessage message, Optional<UnidentifiedAccessPair> unidentifiedAccess)
          throws IOException, UntrustedIdentityException, InvalidKeyException
  {
    byte[] content;

    if (message.getContacts().isPresent()) {
      content = createMultiDeviceContactsContent(message.getContacts().get().getContactsStream().asStream(),
                                                 message.getContacts().get().isComplete());
    } else if (message.getGroups().isPresent()) {
      content = createMultiDeviceGroupsContent(message.getGroups().get().asStream());
    } else if (message.getRead().isPresent()) {
      content = createMultiDeviceReadContent(message.getRead().get());
    } else if (message.getMessageTimerRead().isPresent()) {
      content = createMultiDeviceMessageTimerReadContent(message.getMessageTimerRead().get());
    } else if (message.getBlockedList().isPresent()) {
      content = createMultiDeviceBlockedContent(message.getBlockedList().get());
    } else if (message.getConfiguration().isPresent()) {
      content = createMultiDeviceConfigurationContent(message.getConfiguration().get());
    } else if (message.getSent().isPresent()) {
      content = createMultiDeviceSentTranscriptContent(message.getSent().get(), unidentifiedAccess);
    } else if (message.getStickerPackOperations().isPresent()) {
      content = createMultiDeviceStickerPackOperationContent(message.getStickerPackOperations().get());
    } else if (message.getVerified().isPresent()) {
      sendMessage(message.getVerified().get(), unidentifiedAccess);
      return;
    } else {
      throw new IOException("Unsupported sync message!");
    }

    long timestamp = message.getSent().isPresent() ? message.getSent().get().getTimestamp()
                                                   : System.currentTimeMillis();

    sendMessage(localAddress, Optional.<UnidentifiedAccess>absent(), timestamp, content, false, null); // last
  }

  // non MessageCommand
  public void sendMessage(SignalServiceDataMessage dataMessage, Optional<UnidentifiedAccessPair> unidentifiedAccess, UfsrvCommand ufsrvCommand)
          throws IOException, UntrustedIdentityException, InvalidKeyException
  {
    Log.d(TAG, "sendMessage: Sending SignalServiceDataMessage message...");

    byte[] content = createMessageContent(dataMessage, ufsrvCommand, Optional.absent());//DataMessage proto. This is where attachments are actually uploaded

    sendMessage(localAddress, getTargetUnidentifiedAccess(unidentifiedAccess), System.currentTimeMillis(), content, false, ufsrvCommand);
  }

  public void setSoTimeoutMillis(long soTimeoutMillis) {
    socket.setSoTimeoutMillis(soTimeoutMillis);
  }

  public void cancelInFlightRequests() {
    socket.cancelInFlightRequests();
  }

  public void setMessagePipe(SignalServiceMessagePipe pipe, SignalServiceMessagePipe unidentifiedPipe) {
    this.pipe.set(Optional.fromNullable(pipe));
    this.unidentifiedPipe.set(Optional.fromNullable(unidentifiedPipe));
  }

  public void setIsMultiDevice(boolean isMultiDevice) {
    this.isMultiDevice.set(isMultiDevice);
  }

  private void sendMessage(VerifiedMessage message, Optional<UnidentifiedAccessPair> unidentifiedAccess)
          throws IOException, UntrustedIdentityException
  {
    byte[] nullMessageBody = DataMessage.newBuilder()
            .setBody(Base64.encodeBytes(Util.getRandomLengthBytes(140)))
            .build()
            .toByteArray();

    NullMessage nullMessage = NullMessage.newBuilder()
            .setPadding(ByteString.copyFrom(nullMessageBody))
            .build();

    byte[] content          = Content.newBuilder()
            .setNullMessage(nullMessage)
            .build()
            .toByteArray();

    SendMessageResult result = sendMessage(new SignalServiceAddress(message.getDestination()), getTargetUnidentifiedAccess(unidentifiedAccess), message.getTimestamp(), content, false, null);// last false legacy flag temporarily reinstated

    if (result.getSuccess().isNeedsSync()) {
      byte[] syncMessage = createMultiDeviceVerifiedContent(message, nullMessage.toByteArray());
      sendMessage(localAddress, Optional.<UnidentifiedAccess>absent(), message.getTimestamp(), syncMessage, false, null);// last false legacy flag temporarily reinstated
    }
  }

  private byte[] createTypingContent(SignalServiceTypingMessage message)
  {
    Content.Builder container = Content.newBuilder();
    TypingMessage.Builder builder = TypingMessage.newBuilder();

    builder.setTimestamp(message.getTimestamp());

    if (message.isTypingStarted()) builder.setAction(TypingMessage.Action.STARTED);
    else if (message.isTypingStopped()) builder.setAction(TypingMessage.Action.STOPPED);
    else throw new IllegalArgumentException("Unknown typing indicator");

    if (message.getGroupId().isPresent()) {
      builder.setGroupId(ByteString.copyFrom(message.getGroupId().get()));
    }

    return container.setTypingMessage(builder).build().toByteArray();
  }

  private byte[] createReceiptContent(SignalServiceReceiptMessage message) {
    Content.Builder        container = Content.newBuilder();
    ReceiptMessage.Builder builder   = ReceiptMessage.newBuilder();

    for (long timestamp : message.getTimestamps()) {
      builder.addTimestamp(timestamp);
    }

    if      (message.isDeliveryReceipt()) builder.setType(ReceiptMessage.Type.DELIVERY);
    else if (message.isReadReceipt())     builder.setType(ReceiptMessage.Type.READ);

    return container.setReceiptMessage(builder).build().toByteArray();
  }

  private byte[] createReceiptContent(SignalServiceReceiptMessage receiptMessage, UfsrvCommand ufsrvCommand) {
    //Content.Builder         container             = Content.newBuilder();
    DataMessage.Builder     builder               = DataMessage.newBuilder();

    builder.setUfsrvCommand(ufsrvCommand.buildIfNecessary());

    return builder.build().toByteArray();
  }

  // specialised for e2ee option
  private Pair<byte[], OutgoingPushMessageList> createMessageContent(SignalServiceDataMessage message, @NonNull UfsrvCommand ufCommand, Optional<List<SignalServiceAddress>> recipients, long timestamp, String clearBody)
          throws IOException, UntrustedIdentityException, InvalidKeyException
  {
    DataMessage.Builder     builder  = DataMessage.newBuilder();
    List<AttachmentPointer> pointers = createAttachmentPointers(message.getAttachments());//uploads and retrieves id

    SignalServiceProtos.MessageRecord.Builder messageRecordBuilder  = SignalServiceProtos.MessageRecord.newBuilder();
    OutgoingPushMessageList outgoingMessageList = null;

    // note empty message bypasses this path even for e2ee
    if (ufCommand.isE2ee() && ufCommand.getMessageCommandBuilder()!=null/* && !TextUtils.isEmpty(clearBody)*/) { //only msg
      HashMap<SignalServiceAddress, OutgoingPushMessageList> messagesListMap = getEncryptedPushMessagesList(recipients.get(), timestamp, clearBody.getBytes(Charset.forName("UTF-8")), ufCommand);

      //currently only handles default device for one recipient. No sub devices support
      OutgoingPushMessageList messageList = messagesListMap.get(recipients.get().get(0));
      messageRecordBuilder.setMessage(ByteString.copyFromUtf8(messageList.getMessages().get(0).getContent()));
      messageRecordBuilder.setProtocolType(SignalServiceProtos.MessageCommand.ProtocolType.values()[messageList.getMessages().get(0).getType()]);
      ufCommand.getMessageCommandBuilder().addMessages(messageRecordBuilder.build());
      outgoingMessageList = messageList;
    } else {
      if (ufCommand.getMessageCommandBuilder()!=null) {
        messageRecordBuilder.setMessage(ByteString.copyFromUtf8(clearBody));
        ufCommand.getMessageCommandBuilder().addMessages(messageRecordBuilder.build());
      }
    }
    //

    if (!pointers.isEmpty()) {
      builder.addAllAttachments(pointers);

      ufCommand.includeAttachments(pointers); //
    }

    //- already included in MessageCommand.
//    if (message.getBody().isPresent()) {
//      builder.setBody(message.getBody().get());
//    }

    // builds groupContext
    if (message.getGroupInfo().isPresent()) {
      builder.setGroup(createGroupContent(message.getGroupInfo().get()));
    }

    // no longer valid
    if (message.isExpirationUpdate()) {
      builder.setFlags(DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE);
    }

    if (message.isEndSession()) {
      builder.setFlags(DataMessage.Flags.END_SESSION_VALUE);
    }

    // no longer valid
    if (message.isProfileKeyUpdate()) {
      builder.setFlags(DataMessage.Flags.PROFILE_KEY_UPDATE_VALUE);
    }

    // no longer valid
    if (message.getExpiresInSeconds() > 0) {
      builder.setExpireTimer(message.getExpiresInSeconds());
    }

    if (message.getProfileKey().isPresent()) {
      builder.setProfileKey(ByteString.copyFrom(message.getProfileKey().get()));
    }

    if (message.getQuote().isPresent()) {
      //
      if (ufCommand.getMessageCommandBuilder()!=null) {
        ufCommand.getMessageCommandBuilder().setQuotedMessage(adaptForQuotedMessage(message.getQuote().get()));
      }
      //
    }

    if (message.getSharedContacts().isPresent()) {
      //
      if (ufCommand.getMessageCommandBuilder()!=null) {
        ufCommand.getMessageCommandBuilder().addAllContacts(adaptForContacts(message.getSharedContacts().get()));
      }
      //
//      builder.addAllContact(createSharedContactContent(message.getSharedContacts().get()));//- see block above
    }

    if (message.getPreviews().isPresent()) {
      ufCommand.getMessageCommandBuilder().addAllPreview(adaptForPreviews(message.getPreviews().get()));
    }

    if (message.getSticker().isPresent()) {
      DataMessage.Sticker.Builder stickerBuilder = DataMessage.Sticker.newBuilder();

      stickerBuilder.setPackId(ByteString.copyFrom(message.getSticker().get().getPackId()));
      stickerBuilder.setPackKey(ByteString.copyFrom(message.getSticker().get().getPackKey()));
      stickerBuilder.setStickerId(message.getSticker().get().getStickerId());

      if (message.getSticker().get().getAttachment().isStream()) {
        stickerBuilder.setData(createAttachmentPointer(message.getSticker().get().getAttachment().asStream(), true));
      } else {
        stickerBuilder.setData(createAttachmentPointer(message.getSticker().get().getAttachment().asPointer()));
      }

      builder.setSticker(stickerBuilder.build());
    }

    builder.setTimestamp(message.getTimestamp());

    // finish off our command message building process. we still have the opportunity to add to the wire builder
    builder.setUfsrvCommand(ufCommand.buildIfNecessary());

    byte[] content = builder.build().toByteArray(); //DataMessage built

    if (!ufCommand.isE2ee()) {
      //create OutgoingPushMessageList for the non encrypted message
      List<OutgoingPushMessage> messages = new LinkedList<>();
      OutgoingPushMessage outgoingPushMessage = new OutgoingPushMessage(SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE,
                                                                        SignalServiceAddress.DEFAULT_DEVICE_ID,
                                                                        0,
                                                                        Base64.encodeBytes(content));
      messages.add(outgoingPushMessage);
      outgoingMessageList = new OutgoingPushMessageList("+0", timestamp, messages, false);//todo: look into destination value
      return new Pair<>(content, outgoingMessageList);
    } else {
      List<OutgoingPushMessage> messages = new LinkedList<>();
      OutgoingPushMessage outgoingMessage = outgoingMessageList.getMessages().get(0); //need to rebuild it because content contains encrypted messsage and we want to use prebuilt DataMessage
      OutgoingPushMessage outgoingPushMessage = new OutgoingPushMessage(outgoingMessage.getType(),
                                                                        outgoingMessage.getDestinationDeviceId(),
                                                                       outgoingMessage.getDestinationRegistrationId(),
                                                                        Base64.encodeBytes(content));
      messages.add(outgoingPushMessage);
      outgoingMessageList = new OutgoingPushMessageList("+0", timestamp, messages,false);//todo: look into destination value
      return new Pair<>(content, outgoingMessageList);
    }
  }

// serialises into final DataMessage proto. Attachment uploaded and attachment pointers created
  private byte[] createMessageContent(SignalServiceDataMessage message, @NonNull UfsrvCommand ufCommand, Optional<List<SignalServiceAddress>> recipients)
          throws IOException, UntrustedIdentityException, InvalidKeyException {
    DataMessage.Builder     builder  = DataMessage.newBuilder();
    List<AttachmentPointer> pointers = createAttachmentPointers(message.getAttachments());//uploads and retrieves id

    // assuming MessageCommand
    if (ufCommand.isE2ee()) {
      SignalServiceProtos.MessageRecord.Builder messageRecordBuilder  = SignalServiceProtos.MessageRecord.newBuilder();
      OutgoingPushMessage encryptedMsg = getEncryptedMessage(socket, recipients.get().get(0), Optional.absent(), SignalServiceAddress.DEFAULT_DEVICE_ID, ufCommand.getMessage().getMessages(0).getMessage().toByteArray(), ufCommand);
      messageRecordBuilder.setMessage(ByteString.copyFromUtf8(encryptedMsg.getContent()));
      ufCommand.getMessageCommandBuilder().addMessages(messageRecordBuilder.build());
    }
    //

    if (!pointers.isEmpty()) {
      builder.addAllAttachments(pointers);

      ufCommand.includeAttachments(pointers); //
    }

    //- already included in MessageCommand.
//    if (message.getBody().isPresent()) {
//      builder.setBody(message.getBody().get());
//    }

    // builds groupContext
    if (message.getGroupInfo().isPresent()) {
      builder.setGroup(createGroupContent(message.getGroupInfo().get()));
    }

    // no longer valid
    if (message.isExpirationUpdate()) {
      builder.setFlags(DataMessage.Flags.EXPIRATION_TIMER_UPDATE_VALUE);
    }

    if (message.isEndSession()) {
      builder.setFlags(DataMessage.Flags.END_SESSION_VALUE);
    }

    // no longer valid
    if (message.isProfileKeyUpdate()) {
    builder.setFlags(DataMessage.Flags.PROFILE_KEY_UPDATE_VALUE);
    }

    // no longer valid
    if (message.getExpiresInSeconds() > 0) {
      builder.setExpireTimer(message.getExpiresInSeconds());
    }

    if (message.getProfileKey().isPresent()) {
      builder.setProfileKey(ByteString.copyFrom(message.getProfileKey().get()));
    }

    if (message.getQuote().isPresent()) {
      //
        if (ufCommand.getMessageCommandBuilder()!=null) {
          ufCommand.getMessageCommandBuilder().setQuotedMessage(adaptForQuotedMessage(message.getQuote().get()));
        }
      //

      //- disabled in favour of direct ufsrv QuotedMessage
//      DataMessage.Quote.Builder quoteBuilder = DataMessage.Quote.newBuilder()
//              .setId(message.getQuote().get().getId())
//              .setAuthor(message.getQuote().get().getAuthor().getNumber())
//              .setText(message.getQuote().get().getText());
//
//      for (SignalServiceDataMessage.Quote.QuotedAttachment attachment : message.getQuote().get().getAttachments()) {
//        DataMessage.Quote.QuotedAttachment.Builder quotedAttachment = DataMessage.Quote.QuotedAttachment.newBuilder();
//
//        quotedAttachment.setContentType(attachment.getContentType());
//
//        if (attachment.getFileName() != null) {
//          quotedAttachment.setFileName(attachment.getFileName());
//        }
//
//        if (attachment.getThumbnail() != null) {
//          quotedAttachment.setThumbnail(createAttachmentPointer(attachment.getThumbnail().asStream()));
//        }
//
//        quoteBuilder.addAttachments(quotedAttachment);
//      }
//
//      builder.setQuote(quoteBuilder);
    }

    if (message.getSharedContacts().isPresent()) {
      //
      if (ufCommand.getMessageCommandBuilder()!=null) {
        ufCommand.getMessageCommandBuilder().addAllContacts(adaptForContacts(message.getSharedContacts().get()));
      }
      //
//      builder.addAllContact(createSharedContactContent(message.getSharedContacts().get()));//- see block above
    }

    if (message.getPreviews().isPresent()) {
      ufCommand.getMessageCommandBuilder().addAllPreview(adaptForPreviews(message.getPreviews().get()));
    }

    if (message.getSticker().isPresent()) {
      DataMessage.Sticker.Builder stickerBuilder = DataMessage.Sticker.newBuilder();

      stickerBuilder.setPackId(ByteString.copyFrom(message.getSticker().get().getPackId()));
      stickerBuilder.setPackKey(ByteString.copyFrom(message.getSticker().get().getPackKey()));
      stickerBuilder.setStickerId(message.getSticker().get().getStickerId());

      if (message.getSticker().get().getAttachment().isStream()) {
        stickerBuilder.setData(createAttachmentPointer(message.getSticker().get().getAttachment().asStream(), true));
      } else {
        stickerBuilder.setData(createAttachmentPointer(message.getSticker().get().getAttachment().asPointer()));
      }

      builder.setSticker(stickerBuilder.build());
    }

    builder.setTimestamp(message.getTimestamp());

    // finish off our command message building process. we still have the opportunity to add to the wire builder
    builder.setUfsrvCommand(ufCommand.buildIfNecessary());

    return builder.build().toByteArray();
  }

//
  private byte[] createCallContent(SignalServiceCallMessage callMessage, UfsrvCommand ufsrvCommand) {
    DataMessage.Builder     builder               = DataMessage.newBuilder();
    CallCommand.Builder     callCommandBuilder    = ufsrvCommand.getCallCommandBuilder();

    if (callMessage.getOfferMessage().isPresent()) {
      OfferMessage offer = callMessage.getOfferMessage().get();
      callCommandBuilder.setOffer(CallCommand.Offer.newBuilder()
              .setId(offer.getId())
              .setDescription(offer.getDescription()));
    } else if (callMessage.getAnswerMessage().isPresent()) {
      AnswerMessage answer = callMessage.getAnswerMessage().get();
      callCommandBuilder.setAnswer(CallCommand.Answer.newBuilder()
              .setId(answer.getId())
              .setDescription(answer.getDescription()));
    } else if (callMessage.getIceUpdateMessages().isPresent()) {
      List<IceUpdateMessage> updates = callMessage.getIceUpdateMessages().get();

      for (IceUpdateMessage update : updates) {
        callCommandBuilder.addIceUpdate(CallCommand.IceUpdate.newBuilder()
                .setId(update.getId())
                .setSdp(update.getSdp())
                .setSdpMid(update.getSdpMid())
                .setSdpMLineIndex(update.getSdpMLineIndex()));
      }
    } else if (callMessage.getHangupMessage().isPresent()) {
      callCommandBuilder.setHangup(CallCommand.Hangup.newBuilder().setId(callMessage.getHangupMessage().get().getId()));
    } else if (callMessage.getBusyMessage().isPresent()) {
      callCommandBuilder.setBusy(CallCommand.Busy.newBuilder().setId(callMessage.getBusyMessage().get().getId()));
    }

    builder.setUfsrvCommand(ufsrvCommand.buildIfNecessary());

    return builder.build().toByteArray();
  }

  private byte[] createMultiDeviceContactsContent(SignalServiceAttachmentStream contacts, boolean complete) throws IOException {
    Content.Builder     container = Content.newBuilder();
    SyncMessage.Builder builder   = createSyncMessageBuilder();
    builder.setContacts(SyncMessage.Contacts.newBuilder()
                                .setBlob(createAttachmentPointer(contacts))
                                .setComplete(complete));

    return container.setSyncMessage(builder).build().toByteArray();
  }

  private byte[] createMultiDeviceGroupsContent(SignalServiceAttachmentStream groups) throws IOException {
    Content.Builder     container = Content.newBuilder();
    SyncMessage.Builder builder   = createSyncMessageBuilder();
    builder.setGroups(SyncMessage.Groups.newBuilder()
            .setBlob(createAttachmentPointer(groups)));

    return container.setSyncMessage(builder).build().toByteArray();
  }

  private byte[] createMultiDeviceSentTranscriptContent(SentTranscriptMessage transcript, Optional<UnidentifiedAccessPair> unidentifiedAccess) throws IOException, InvalidKeyException, UntrustedIdentityException {
    SignalServiceAddress address = new SignalServiceAddress(transcript.getDestination().get());
    SendMessageResult    result  = SendMessageResult.success(address, unidentifiedAccess.isPresent(), true);

    return createMultiDeviceSentTranscriptContent(createMessageContent(transcript.getMessage(), null, Optional.absent()), // amended params todo: ufcommand is null
                                                  Optional.of(address),
                                                  transcript.getTimestamp(),
                                                  Collections.singletonList(result),
                                                  false);
  }

  private byte[] createMultiDeviceSentTranscriptContent(byte[] content, Optional<SignalServiceAddress> recipient,
                                                        long timestamp, List<SendMessageResult> sendMessageResults,
                                                        boolean isRecipientUpdate)
  {
    try {
      Content.Builder          container   = Content.newBuilder();
      SyncMessage.Builder      syncMessage = createSyncMessageBuilder();
      SyncMessage.Sent.Builder sentMessage = SyncMessage.Sent.newBuilder();
      //      DataMessage              dataMessage = DataMessage.parseFrom(content);
      DataMessage              dataMessage = Content.parseFrom(content).getDataMessage();//NOT TESTED https://github.com/WhisperSystems/libsignal-service-java/commit/4e94d2708657b6f01561034d303015f98c3e9089

      sentMessage.setTimestamp(timestamp);
      sentMessage.setMessage(dataMessage);

      for (SendMessageResult result : sendMessageResults) {
        if (result.getSuccess() != null) {
          sentMessage.addUnidentifiedStatus(SyncMessage.Sent.UnidentifiedDeliveryStatus.newBuilder()
                                                    .setDestination(result.getAddress().getNumber())
                                                    .setUnidentified(result.getSuccess().isUnidentified()));
        }
      }

      if (recipient.isPresent()) {
        sentMessage.setDestination(recipient.get().getNumber());
      }

      if (dataMessage.getExpireTimer() > 0) {
        sentMessage.setExpirationStartTimestamp(System.currentTimeMillis());
      }

      if (dataMessage.getMessageTimer() > 0) {
        dataMessage = dataMessage.toBuilder().clearAttachments().build();
        sentMessage.setMessage(dataMessage);
      }

      sentMessage.setIsRecipientUpdate(isRecipientUpdate);

      return container.setSyncMessage(syncMessage.setSent(sentMessage)).build().toByteArray();
    } catch (InvalidProtocolBufferException e) {
      throw new AssertionError(e);
    }
  }

  private byte[] createMultiDeviceReadContent(List<ReadMessage> readMessages) {
    Content.Builder     container = Content.newBuilder();
    SyncMessage.Builder builder   = createSyncMessageBuilder();

    for (ReadMessage readMessage : readMessages) {
      builder.addRead(SyncMessage.Read.newBuilder()
                                      .setTimestamp(readMessage.getTimestamp())
                                      .setSender(readMessage.getSender()));
    }

    return container.setSyncMessage(builder).build().toByteArray();
  }

  private byte[] createMultiDeviceMessageTimerReadContent(MessageTimerReadMessage readMessage) {
    Content.Builder     container = Content.newBuilder();
    SyncMessage.Builder builder   = createSyncMessageBuilder();

    builder.setMessageTimerRead(SyncMessage.MessageTimerRead.newBuilder()
                                        .setTimestamp(readMessage.getTimestamp())
                                        .setSender(readMessage.getSender()));

    return container.setSyncMessage(builder).build().toByteArray();
  }

  private byte[] createMultiDeviceBlockedContent(BlockedListMessage blocked) {
    Content.Builder             container      = Content.newBuilder();
    SyncMessage.Builder         syncMessage    = createSyncMessageBuilder();
    SyncMessage.Blocked.Builder blockedMessage = SyncMessage.Blocked.newBuilder();

    blockedMessage.addAllNumbers(blocked.getNumbers());

    for (byte[] groupId : blocked.getGroupIds()) {
      blockedMessage.addGroupIds(ByteString.copyFrom(groupId));
    }

    return container.setSyncMessage(syncMessage.setBlocked(blockedMessage)).build().toByteArray();
  }

  private byte[] createMultiDeviceConfigurationContent(ConfigurationMessage configuration) {
    Content.Builder                   container            = Content.newBuilder();
    SyncMessage.Builder               syncMessage          = createSyncMessageBuilder();
    SyncMessage.Configuration.Builder configurationMessage = SyncMessage.Configuration.newBuilder();

    if (configuration.getReadReceipts().isPresent()) {
      configurationMessage.setReadReceipts(configuration.getReadReceipts().get());
    }

    if (configuration.getUnidentifiedDeliveryIndicators().isPresent()) {
      configurationMessage.setUnidentifiedDeliveryIndicators(configuration.getUnidentifiedDeliveryIndicators().get());
    }

    if (configuration.getTypingIndicators().isPresent()) {
      configurationMessage.setTypingIndicators(configuration.getTypingIndicators().get());
    }

    if (configuration.getLinkPreviews().isPresent()) {
      configurationMessage.setLinkPreviews(configuration.getLinkPreviews().get());
    }

    return container.setSyncMessage(syncMessage.setConfiguration(configurationMessage)).build().toByteArray();
  }

  private byte[] createMultiDeviceStickerPackOperationContent(List<StickerPackOperationMessage> stickerPackOperations) {
    Content.Builder     container   = Content.newBuilder();
    SyncMessage.Builder syncMessage = createSyncMessageBuilder();

    for (StickerPackOperationMessage stickerPackOperation : stickerPackOperations) {
      SyncMessage.StickerPackOperation.Builder builder = SyncMessage.StickerPackOperation.newBuilder();

      if (stickerPackOperation.getPackId().isPresent()) {
        builder.setPackId(ByteString.copyFrom(stickerPackOperation.getPackId().get()));
      }

      if (stickerPackOperation.getPackKey().isPresent()) {
        builder.setPackKey(ByteString.copyFrom(stickerPackOperation.getPackKey().get()));
      }

      if (stickerPackOperation.getType().isPresent()) {
        switch (stickerPackOperation.getType().get()) {
          case INSTALL: builder.setType(SyncMessage.StickerPackOperation.Type.INSTALL); break;
          case REMOVE:  builder.setType(SyncMessage.StickerPackOperation.Type.REMOVE); break;
        }
      }

      syncMessage.addStickerPackOperation(builder);
    }

    return container.setSyncMessage(syncMessage).build().toByteArray();
  }

  private byte[] createMultiDeviceVerifiedContent(VerifiedMessage verifiedMessage, byte[] nullMessage) {
    Content.Builder     container              = Content.newBuilder();
    SyncMessage.Builder syncMessage            = createSyncMessageBuilder();
    Verified.Builder    verifiedMessageBuilder = Verified.newBuilder();

    verifiedMessageBuilder.setNullMessage(ByteString.copyFrom(nullMessage));
    verifiedMessageBuilder.setDestination(verifiedMessage.getDestination());
    verifiedMessageBuilder.setIdentityKey(ByteString.copyFrom(verifiedMessage.getIdentityKey().serialize()));

    switch(verifiedMessage.getVerified()) {
      case DEFAULT:    verifiedMessageBuilder.setState(Verified.State.DEFAULT);    break;
      case VERIFIED:   verifiedMessageBuilder.setState(Verified.State.VERIFIED);   break;
      case UNVERIFIED: verifiedMessageBuilder.setState(Verified.State.UNVERIFIED); break;
      default:         throw new AssertionError("Unknown: " + verifiedMessage.getVerified());
    }

    syncMessage.setVerified(verifiedMessageBuilder);
    return container.setSyncMessage(syncMessage).build().toByteArray();
  }

  private SyncMessage.Builder createSyncMessageBuilder() {
    SecureRandom random  = new SecureRandom();
    byte[]       padding = Util.getRandomLengthBytes(512);
    random.nextBytes(padding);

    SyncMessage.Builder builder = SyncMessage.newBuilder();
    builder.setPadding(ByteString.copyFrom(padding));

    return builder;
  }

  // rebuilds the GroupContext object as we now have knowledge of the attachment id
  private GroupContext createGroupContent(SignalServiceGroup group) throws IOException {
    GroupContext.Builder builder = GroupContext.newBuilder();
    builder.setId(ByteString.copyFrom(group.getGroupId()));

    if (group.getType() != SignalServiceGroup.Type.DELIVER) {
      if      (group.getType() == SignalServiceGroup.Type.UPDATE)       builder.setType(GroupContext.Type.UPDATE);
      else if (group.getType() == SignalServiceGroup.Type.QUIT)         builder.setType(GroupContext.Type.QUIT);
      else if (group.getType() == SignalServiceGroup.Type.REQUEST_INFO) builder.setType(GroupContext.Type.REQUEST_INFO);
      else                                                              throw new AssertionError("Unknown type: " + group.getType());

      if (group.getName().isPresent()) builder.setName(group.getName().get());
      if (group.getMembers().isPresent()) builder.addAllMembers(group.getMembers().get());

      if (group.getAvatar().isPresent()) {
        if (group.getAvatar().get().isStream()) {
          builder.setAvatar(createAttachmentPointer(group.getAvatar().get().asStream()));
        } else {
          builder.setAvatar(createAttachmentPointer(group.getAvatar().get().asPointer()));
        }
      }
    } else {
      builder.setType(GroupContext.Type.DELIVER);
    }

    return builder.build();
  }

  // defunct: see adaptForContacts()
  private List<DataMessage.Contact> createSharedContactContent(List<SharedContact> contacts) throws IOException {
    List<DataMessage.Contact> results = new LinkedList<>();

    for (SharedContact contact : contacts) {
      DataMessage.Contact.Name.Builder nameBuilder    = DataMessage.Contact.Name.newBuilder();

      if (contact.getName().getFamily().isPresent())  nameBuilder.setFamilyName(contact.getName().getFamily().get());
      if (contact.getName().getGiven().isPresent())   nameBuilder.setGivenName(contact.getName().getGiven().get());
      if (contact.getName().getMiddle().isPresent())  nameBuilder.setMiddleName(contact.getName().getMiddle().get());
      if (contact.getName().getPrefix().isPresent())  nameBuilder.setPrefix(contact.getName().getPrefix().get());
      if (contact.getName().getSuffix().isPresent())  nameBuilder.setSuffix(contact.getName().getSuffix().get());
      if (contact.getName().getDisplay().isPresent()) nameBuilder.setDisplayName(contact.getName().getDisplay().get());

      DataMessage.Contact.Builder contactBuilder = DataMessage.Contact.newBuilder()
              .setName(nameBuilder);

      if (contact.getAddress().isPresent()) {
        for (SharedContact.PostalAddress address : contact.getAddress().get()) {
          DataMessage.Contact.PostalAddress.Builder addressBuilder = DataMessage.Contact.PostalAddress.newBuilder();

          switch (address.getType()) {
            case HOME:   addressBuilder.setType(DataMessage.Contact.PostalAddress.Type.HOME); break;
            case WORK:   addressBuilder.setType(DataMessage.Contact.PostalAddress.Type.WORK); break;
            case CUSTOM: addressBuilder.setType(DataMessage.Contact.PostalAddress.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + address.getType());
          }

          if (address.getCity().isPresent())         addressBuilder.setCity(address.getCity().get());
          if (address.getCountry().isPresent())      addressBuilder.setCountry(address.getCountry().get());
          if (address.getLabel().isPresent())        addressBuilder.setLabel(address.getLabel().get());
          if (address.getNeighborhood().isPresent()) addressBuilder.setNeighborhood(address.getNeighborhood().get());
          if (address.getPobox().isPresent())        addressBuilder.setPobox(address.getPobox().get());
          if (address.getPostcode().isPresent())     addressBuilder.setPostcode(address.getPostcode().get());
          if (address.getRegion().isPresent())       addressBuilder.setRegion(address.getRegion().get());
          if (address.getStreet().isPresent())       addressBuilder.setStreet(address.getStreet().get());

          contactBuilder.addAddress(addressBuilder);
        }
      }

      if (contact.getEmail().isPresent()) {
        for (SharedContact.Email email : contact.getEmail().get()) {
          DataMessage.Contact.Email.Builder emailBuilder = DataMessage.Contact.Email.newBuilder()
                  .setValue(email.getValue());

          switch (email.getType()) {
            case HOME:   emailBuilder.setType(DataMessage.Contact.Email.Type.HOME);   break;
            case WORK:   emailBuilder.setType(DataMessage.Contact.Email.Type.WORK);   break;
            case MOBILE: emailBuilder.setType(DataMessage.Contact.Email.Type.MOBILE); break;
            case CUSTOM: emailBuilder.setType(DataMessage.Contact.Email.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + email.getType());
          }

          if (email.getLabel().isPresent()) emailBuilder.setLabel(email.getLabel().get());

          contactBuilder.addEmail(emailBuilder);
        }
      }

      if (contact.getPhone().isPresent()) {
        for (SharedContact.Phone phone : contact.getPhone().get()) {
          DataMessage.Contact.Phone.Builder phoneBuilder = DataMessage.Contact.Phone.newBuilder()
                  .setValue(phone.getValue());

          switch (phone.getType()) {
            case HOME:   phoneBuilder.setType(DataMessage.Contact.Phone.Type.HOME);   break;
            case WORK:   phoneBuilder.setType(DataMessage.Contact.Phone.Type.WORK);   break;
            case MOBILE: phoneBuilder.setType(DataMessage.Contact.Phone.Type.MOBILE); break;
            case CUSTOM: phoneBuilder.setType(DataMessage.Contact.Phone.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + phone.getType());
          }

          if (phone.getLabel().isPresent()) phoneBuilder.setLabel(phone.getLabel().get());

          contactBuilder.addNumber(phoneBuilder);
        }
      }

      if (contact.getAvatar().isPresent()) {
        AttachmentPointer pointer = contact.getAvatar().get().getAttachment().isStream() ? createAttachmentPointer(contact.getAvatar().get().getAttachment().asStream())
                                                                                         : createAttachmentPointer(contact.getAvatar().get().getAttachment().asPointer());
        contactBuilder.setAvatar(DataMessage.Contact.Avatar.newBuilder()
                                         .setAvatar(pointer)
                                         .setIsProfile(contact.getAvatar().get().isProfile()));
      }

      if (contact.getOrganization().isPresent()) {
        contactBuilder.setOrganization(contact.getOrganization().get());
      }

      results.add(contactBuilder.build());
    }

    return results;
  }

  private List<SendMessageResult> sendMessage(List<SignalServiceAddress>         recipients,
                                              List<Optional<UnidentifiedAccess>> unidentifiedAccess,
                                              long                               timestamp,
                                              byte[]                             content,
                                              boolean                            online,
                                              UfsrvCommand ufsrvCommand)
          throws IOException
  {
    List<SendMessageResult>                results                    = new LinkedList<>();
    Iterator<SignalServiceAddress>         recipientIterator          = recipients.iterator();
    Iterator<Optional<UnidentifiedAccess>> unidentifiedAccessIterator = unidentifiedAccess.iterator();

    while (recipientIterator.hasNext()) {
      SignalServiceAddress recipient = recipientIterator.next();

      try {
        SendMessageResult result = sendMessage(recipient, unidentifiedAccessIterator.next(), timestamp, content, online, ufsrvCommand);// uf
        results.add(result);
      } catch (UntrustedIdentityException e) {
        Log.w(TAG, e);
        results.add(SendMessageResult.identityFailure(recipient, e.getIdentityKey()));
      } catch (UnregisteredUserException e) {
        Log.w(TAG, e);
        results.add(SendMessageResult.unregisteredFailure(recipient));
      } catch (PushNetworkException e) {
        Log.w(TAG, e);
        results.add(SendMessageResult.networkFailure(recipient));
      }
    }

    return results;
  }

  //
  HashMap<SignalServiceAddress, OutgoingPushMessageList> getEncryptedPushMessagesList (List<SignalServiceAddress> recipients, long timestamp, byte[] content, UfsrvCommand ufCommand)
  throws IOException, UntrustedIdentityException, InvalidKeyException
  {
    HashMap<SignalServiceAddress, OutgoingPushMessageList> messagesList = new HashMap<>();

    for (SignalServiceAddress recipient : recipients)
    {
      try {
        OutgoingPushMessageList messages = getEncryptedMessages(socket, recipient, Optional.absent(), timestamp, content, false, ufCommand); // ufcommand returns json
        messagesList.put(recipient, messages);
      } catch (MismatchedDevicesException mde) {
        Log.w(TAG, mde);
        handleMismatchedDevices(socket, recipient, mde.getMismatchedDevices());
      } catch (StaleDevicesException ste) {
        Log.w(TAG, ste);
        handleStaleDevices(recipient, ste.getStaleDevices());
      }
    }

    return messagesList;
  }

  OutgoingPushMessageList getPushMessagesList (SignalServiceAddress recipient, long timestamp, byte[] content, UfsrvCommand ufCommand)
  {
    List<OutgoingPushMessage> outgoingMessageList = new LinkedList<>();

    OutgoingPushMessage outgoingPushMessage = new OutgoingPushMessage(SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE,
                                                                      SignalServiceAddress.DEFAULT_DEVICE_ID,
                                                                      0,
                                                                      Base64.encodeBytes(content));
    outgoingMessageList.add(outgoingPushMessage);
    return new OutgoingPushMessageList("+0", timestamp, outgoingMessageList, false);//todo: look into destination value

//    for (SignalServiceAddress recipient : recipients)
//    {
//      try {
//        OutgoingPushMessageList messages = getEncryptedMessages(socket, recipient, timestamp, content, legacy, silent, ufCommand); // ufcommand returns json
//        messagesList.put(recipient, messages);
//      } catch (MismatchedDevicesException mde) {
//        Log.w(TAG, mde);
//        handleMismatchedDevices(socket, recipient, mde.getMismatchedDevices());
//      } catch (StaleDevicesException ste) {
//        Log.w(TAG, ste);
//        handleStaleDevices(recipient, ste.getStaleDevices());
//      }
//    }
//
//    return messagesList;
  }
  //

  //- see above end for single source
// private SendMessageResponse sendMessage(List<SignalServiceAddress> recipients, long timestamp, byte[] content, boolean legacy, UfsrvCommand ufCommand)// encrypt
//      throws IOException, EncapsulatedExceptions
//  {
//    List<UntrustedIdentityException> untrustedIdentities = new LinkedList<>();
//    List<UnregisteredUserException>  unregisteredUsers   = new LinkedList<>();
//    List<NetworkFailureException>    networkExceptions   = new LinkedList<>();
//
//    SendMessageResponse response = null;
//    android.util.Log.d(TAG, String.format(">>sendMessage: Legacy flag:'%b', Encrypt flag:'%b'", legacy, ufCommand.isE2ee()));
//    for (SignalServiceAddress recipient : recipients) {
//      try {
//        //-
//        //response = sendMessage(recipient, timestamp, content, legacy);
//        //
//        response = sendMessage(recipient, timestamp, content, legacy, ufCommand);
//      } catch (UntrustedIdentityException e) {
//        Log.w(TAG, e);
//        untrustedIdentities.add(e);
//      } catch (UnregisteredUserException e) {
//        Log.w(TAG, e);
//        unregisteredUsers.add(e);
//      } catch (PushNetworkException e) {
//        Log.w(TAG, e);
//        networkExceptions.add(new NetworkFailureException(recipient.getNumber(), e));
//      }
//    }
//
//    if (!untrustedIdentities.isEmpty() || !unregisteredUsers.isEmpty() || !networkExceptions.isEmpty()) {
//      throw new EncapsulatedExceptions(untrustedIdentities, unregisteredUsers, networkExceptions);
//    }
//
//    return response;
//  }

  // distinguishes e2ee
  private SendMessageResult sendMessage(SignalServiceAddress recipient,
                                        Optional<UnidentifiedAccess> unidentifiedAccess,
                                        long timestamp,
                                        byte[] content,
                                        boolean online,
                                        UfsrvCommand ufCommand)
          throws UntrustedIdentityException, IOException
  {
    OutgoingPushMessageList messages;
    for (int i=0;i<3;i++) {
      try {
        if (!ufCommand.isE2ee()) {
            messages = getPushMessagesList (recipient, timestamp, content, ufCommand);
        } else {
          messages = getEncryptedMessages(socket, recipient, unidentifiedAccess, timestamp, content, online, ufCommand); // ufcommand returns json
        }
        Optional<SignalServiceMessagePipe> pipe             = this.pipe.get();
        Optional<SignalServiceMessagePipe> unidentifiedPipe = this.unidentifiedPipe.get();

        //- disabled .. for now always send over new stateless api connection
        if (false && pipe.isPresent() && !unidentifiedAccess.isPresent()) {
          try {
            Log.w(TAG, "sendMessage: Transmitting over pipe...");
            SendMessageResponse response = pipe.get().send(messages, Optional.<UnidentifiedAccess>absent());
            return SendMessageResult.success(recipient, false, response.getNeedsSync());
          } catch (IOException e) {
            Log.w(TAG, e);
            Log.w(TAG, "sendMessage: Falling back to new connection...");
          }
        } else if (false && unidentifiedPipe.isPresent() && unidentifiedAccess.isPresent()) {
          try {
            Log.w(TAG, "Transmitting over unidentified pipe...");
            SendMessageResponse response = unidentifiedPipe.get().send(messages, unidentifiedAccess);
            return SendMessageResult.success(recipient, true, response.getNeedsSync());
          } catch (IOException e) {
            Log.w(TAG, e);
            Log.w(TAG, "Falling back to new connection...");
          }
        }
        Log.w(TAG, "sendMessage: Not transmitting over pipe...");

        SendMessageResponse response = socket.sendMessage(messages, unidentifiedAccess, ufCommand);
        return SendMessageResult.success(recipient, false, response.getNeedsSync());
      } catch (InvalidKeyException ike) {
        Log.w(TAG, ike);
        unidentifiedAccess = Optional.absent();
      } catch (AuthorizationFailedException afe) {
        Log.w(TAG, afe);
        if (unidentifiedAccess.isPresent()) {
          unidentifiedAccess = Optional.absent();
        } else {
          throw afe;
        }
      } catch (MismatchedDevicesException mde) {
        Log.w(TAG, mde);
        handleMismatchedDevices(socket, recipient, mde.getMismatchedDevices());
      } catch (StaleDevicesException ste) {
        Log.w(TAG, ste);
        handleStaleDevices(recipient, ste.getStaleDevices());
      }
    }

    throw new IOException("Failed to resolve conflicts after 3 attempts!");
  }

// pure transmission works with uf style e2ee where OutgoingPushMessageList is provided
private SendMessageResult sendMessage(SignalServiceAddress recipient, OutgoingPushMessageList messages, UfsrvCommand ufCommand)
        throws UntrustedIdentityException, IOException
{
  SendMessageResponseList responseList = new SendMessageResponseList();

  for (int i=0;i<3;i++) {
    try {
      Optional<SignalServiceMessagePipe> pipe     = this.pipe.get();

      //- disabled .. always send over new stateless connection
      if (false) {//pipe.isPresent()) {
        try {
          Log.w(TAG, "sendMessage: Transmitting over pipe...");
          SendMessageResponse response = pipe.get().send(messages, Optional.absent());
          return SendMessageResult.success(recipient, false, response.getNeedsSync());
        } catch (IOException e) {
          Log.w(TAG, e);
          Log.w(TAG, "sendMessage: Falling back to new connection...");
        }
      }
      Log.w(TAG, "sendMessage: Not transmitting over pipe...");

      SendMessageResponse response = socket.sendMessage(messages, Optional.absent(), ufCommand);
      return SendMessageResult.success(recipient, false, response.getNeedsSync());
    } catch (MismatchedDevicesException mde) {
      Log.w(TAG, mde);
      handleMismatchedDevices(socket, recipient, mde.getMismatchedDevices());
    } catch (StaleDevicesException ste) {
      Log.w(TAG, ste);
      handleStaleDevices(recipient, ste.getStaleDevices());
    }
  }

  throw new IOException("Failed to resolve conflicts after 3 attempts!");
}
//

  //see https://github.com/signalapp/libsignal-service-java/commit/67ecd91eafaf9fd88b39bbd06d7c07a929344371#diff-904493c201d15c0818b2e6898d5fb142
  //which introduced AttachmentAttributes for CDN type
  public SignalServiceAttachmentPointer uploadAttachment(SignalServiceAttachmentStream attachment, boolean usePadding) throws IOException {
    //
    byte[]             attachmentKey;
    if (attachment.getKey() != null && attachment.getKey().isPresent()) { //todo: fix check for null: should pass Optional.absent()
      attachmentKey = attachment.getKey().get();
    } else {
      attachmentKey = Util.getSecretBytes(64);
    }
    //

    long               paddedLength     = usePadding ? PaddingInputStream.getPaddedSize(attachment.getLength())
                                                     : attachment.getLength();
    InputStream        dataStream       = usePadding ? new PaddingInputStream(attachment.getInputStream(), attachment.getLength())
                                                     : attachment.getInputStream();
    long               ciphertextLength = AttachmentCipherOutputStream.getCiphertextLength(paddedLength);
    PushAttachmentData attachmentData   = new PushAttachmentData(attachment.getContentType(),
                                                                 dataStream,
                                                                 ciphertextLength,
                                                                 new AttachmentCipherOutputStreamFactory(attachmentKey),
                                                                 attachment.getListener());

    AttachmentUploadAttributes uploadAttributes = null;

    if (1 == 2) {
      if (pipe.get().isPresent()) {
        Log.d(TAG, "Using pipe to retrieve attachment upload attributes...");
        try {
          uploadAttributes = pipe.get().get().getAttachmentUploadAttributes();
        } catch (IOException e) {
          Log.w(TAG, "Failed to retrieve attachment upload attributes using pipe. Falling back...");
        }
      }

      if (uploadAttributes == null) {
        Log.d(TAG, "Not using pipe to retrieve attachment upload attributes...");
        uploadAttributes = socket.getAttachmentUploadAttributes();
      }
      Pair<Long, byte[]> attachmentIdAndDigest = socket.uploadAttachment(attachmentData, uploadAttributes);
    }

    Pair<String, byte[]> attachmentIdAndDigest  = socket.sendAttachment(attachmentData); // return type string
    Log.d(TAG, String.format("createAttachmentPointer: Received attachment ID: '%s', digest:'%s' ", attachmentIdAndDigest.first(), ByteString.copyFrom(attachmentIdAndDigest.second()).toString()));

    return new SignalServiceAttachmentPointer(attachmentIdAndDigest.first(),// note string ufid
                                              0,
                                              attachment.getContentType(),
                                              attachmentKey,
                                              Optional.of(Util.toIntExact(attachment.getLength())),
                                              attachment.getPreview(),
                                              attachment.getWidth(), attachment.getHeight(),
                                              Optional.of(attachmentIdAndDigest.second()),
                                              attachment.getFileName(),
                                              attachment.getVoiceNote(),
                                              attachment.getCaption());
  }

  private List<AttachmentPointer> createAttachmentPointers(Optional<List<SignalServiceAttachment>> attachments) throws IOException {
    List<AttachmentPointer> pointers = new LinkedList<>();

    if (!attachments.isPresent() || attachments.get().isEmpty()) {
      Log.w(TAG, "No attachments present...");
      return pointers;
    }

    for (SignalServiceAttachment attachment : attachments.get()) {
      if (attachment.isStream()) {
        Log.w(TAG, "Found attachment, creating pointer...");
        pointers.add(createAttachmentPointer(attachment.asStream()));
      } else if (attachment.isPointer()) {
        Log.w(TAG, "Including existing attachment pointer...");
        pointers.add(createAttachmentPointer(attachment.asPointer()));
      }
    }

    return pointers;
  }

  // IMPORTANT always update createAttachmentRecord() BELOW if this gets updated until phased out
  private AttachmentPointer createAttachmentPointer(SignalServiceAttachmentPointer attachment) {
    AttachmentPointer.Builder builder = AttachmentPointer.newBuilder()
            .setContentType(attachment.getContentType())
            .setId(attachment.getId())
            .setUfid(attachment.getUfId())//  extra field
            .setKey(ByteString.copyFrom(attachment.getKey()))
            .setDigest(ByteString.copyFrom(attachment.getDigest().get()))
            .setSize(attachment.getSize().get());

    if (attachment.getFileName().isPresent()) {
      builder.setFileName(attachment.getFileName().get());
    }

    if (attachment.getPreview().isPresent()) {
      builder.setThumbnail(ByteString.copyFrom(attachment.getPreview().get()));
    }

    if (attachment.getWidth() > 0) {
      builder.setWidth(attachment.getWidth());
    }

    if (attachment.getHeight() > 0) {
      builder.setHeight(attachment.getHeight());
    }

    if (attachment.getVoiceNote()) {
      builder.setFlags(AttachmentPointer.Flags.VOICE_MESSAGE_VALUE);
    }

    if (attachment.getCaption().isPresent()) {
      builder.setCaption(attachment.getCaption().get());
    }

    return builder.build();
  }

  // Same as createAttachmentPointer, except it generates AttachmentRecord, not pointer. user nonce as id in the protobuf and uses AttachmentRecord proto.
  private SignalServiceProtos.AttachmentRecord createAttachmentRecord (SignalServiceAttachmentPointer attachment)
          throws IOException
  {
    SignalServiceProtos.AttachmentRecord.Builder builder = SignalServiceProtos.AttachmentRecord.newBuilder()
            .setContentType(attachment.getContentType())
            .setId(attachment.getUfId())// ufid
            .setKey(ByteString.copyFrom(attachment.getKey()))
            .setDigest(ByteString.copyFrom(attachment.getDigest().get()))
            .setSize(attachment.getSize().get());

    if (attachment.getFileName().isPresent()) {
      builder.setFileName(attachment.getFileName().get());
    }

    if (attachment.getPreview().isPresent()) {
      Log.d(TAG, String.format("createAttachmentRecord: copying thumbnail..."));
      builder.setThumbnail(ByteString.copyFrom(attachment.getPreview().get()));
    }

    if (attachment.getWidth() > 0) {
      builder.setWidth(attachment.getWidth());
    }

    if (attachment.getHeight() > 0) {
      builder.setHeight(attachment.getHeight());
    }
    if (attachment.getVoiceNote()) {
      builder.setFlags(AttachmentPointer.Flags.VOICE_MESSAGE_VALUE);
    }

    if (attachment.getCaption().isPresent()) {
      builder.setCaption(attachment.getCaption().get());
    }

    return builder.build();

  }

  private AttachmentPointer createAttachmentPointer(SignalServiceAttachmentStream attachment)
          throws IOException
  {
    SignalServiceAttachmentPointer pointer = uploadAttachment(attachment, false);
    return createAttachmentPointer(pointer);
  }

  private AttachmentPointer createAttachmentPointer(SignalServiceAttachmentStream attachment, boolean usePadding)
          throws IOException
  {
    SignalServiceAttachmentPointer pointer = uploadAttachment(attachment, usePadding);
    return createAttachmentPointer(pointer);
  }

  //
  private SignalServiceProtos.AttachmentRecord createAttachmentRecord(SignalServiceAttachmentStream attachment)
          throws IOException
  {
    SignalServiceAttachmentPointer pointer = uploadAttachment(attachment, false);
    return createAttachmentRecord(pointer);
  }
  //

  //
  public SignalServiceProtos.AttachmentRecord createAttachmentPointerProfileAvatar (ProfileAvatarData attachmentData, byte[] key)
          throws IOException
  {
    Pair<String, byte[]> attachmentIdAndDigest = socket.sendProfileAvatarUfsrv(attachmentData);
    Log.d(TAG, String.format("createAttachmentPointerProfileAvatar: Received attachment ID: '%s' ", attachmentIdAndDigest.first()));

    SignalServiceProtos.AttachmentRecord.Builder builder = SignalServiceProtos.AttachmentRecord.newBuilder()
                                                  .setContentType(attachmentData.getContentType())
                                                  .setId(attachmentIdAndDigest.first())
                                                  .setKey(ByteString.copyFrom(key))
                                                  .setDigest(ByteString.copyFrom(attachmentIdAndDigest.second()))
                                                  .setSize((int)attachmentData.getDataLength());


    return builder.build();
  }

  private List<SignalServiceProtos.PreviewRecord> adaptForPreviews (List<SignalServiceDataMessage.Preview> previews)
          throws IOException {
    List<SignalServiceProtos.PreviewRecord> results = new LinkedList<>();

    for (SignalServiceDataMessage.Preview preview : previews) {
      SignalServiceProtos.PreviewRecord.Builder previewRecordBuilder = SignalServiceProtos.PreviewRecord.newBuilder();
      previewRecordBuilder.setTitle(preview.getTitle());
      previewRecordBuilder.setUrl(preview.getUrl());

      if (preview.getImage().isPresent()) {
        if (preview.getImage().get().isStream()) {
          previewRecordBuilder.setImage(createAttachmentRecord(preview.getImage().get().asStream()));
        } else {
          previewRecordBuilder.setImage(createAttachmentRecord(preview.getImage().get().asPointer()));
        }
      }

      results.add(previewRecordBuilder.build());
    }

    return results;

    //reference implementation
//    for (SignalServiceDataMessage.Preview preview : message.getPreviews().get()) {
//      DataMessage.Preview.Builder previewBuilder = DataMessage.Preview.newBuilder();
//      previewBuilder.setTitle(preview.getTitle());
//      previewBuilder.setUrl(preview.getUrl());
//
//      if (preview.getImage().isPresent()) {
//        if (preview.getImage().get().isStream()) {
//          previewBuilder.setImage(createAttachmentPointer(preview.getImage().get().asStream()));
//        } else {
//          previewBuilder.setImage(createAttachmentPointer(preview.getImage().get().asPointer()));
//        }
//      }
//
//      builder.addPreview(previewBuilder.build());
//    }
  }

  private SignalServiceProtos.QuotedMessageRecord.QuotedAttachment adaptForQuotedAttachment (SignalServiceDataMessage.Quote.QuotedAttachment quotedAttachment) throws IOException
  {
    SignalServiceProtos.QuotedMessageRecord.QuotedAttachment.Builder quotedAttachmentBuilder = SignalServiceProtos.QuotedMessageRecord.QuotedAttachment.newBuilder();

    quotedAttachmentBuilder.setContentType(quotedAttachment.getContentType());
    if (quotedAttachment.getFileName()!=null) {
      quotedAttachmentBuilder.setFileName(quotedAttachment.getFileName());
    }
    if (quotedAttachment.getThumbnail()!=null) {
      quotedAttachmentBuilder.setThumbnail(createAttachmentRecord(quotedAttachment.getThumbnail().asStream()));
    }

    return quotedAttachmentBuilder.build();
  }

  private SignalServiceProtos.QuotedMessageRecord adaptForQuotedMessage (SignalServiceDataMessage.Quote quote) throws IOException
  {
    SignalServiceProtos.QuotedMessageRecord.Builder quotedMessageBuilder = SignalServiceProtos.QuotedMessageRecord.newBuilder();

    quotedMessageBuilder.setAuthor(quote.getAuthor().getNumber());
    quotedMessageBuilder.setId(quote.getId());
    quotedMessageBuilder.setText(quote.getText());

    for (SignalServiceDataMessage.Quote.QuotedAttachment pointer : quote.getAttachments()) {
      SignalServiceProtos.QuotedMessageRecord.QuotedAttachment quotedAttachment = adaptForQuotedAttachment(pointer);
      quotedMessageBuilder.addAttachments(quotedAttachment);
    }

    return quotedMessageBuilder.build();
  }

  //based on createSharedContactContent()
  private List<SignalServiceProtos.ContactRecord> adaptForContacts (List<SharedContact> contacts) throws IOException
  {
    List<SignalServiceProtos.ContactRecord> results = new LinkedList<>();

    for (SharedContact contact : contacts) {
      SignalServiceProtos.ContactRecord.Name.Builder nameBuilder = SignalServiceProtos.ContactRecord.Name.newBuilder();

      if (contact.getName().getFamily().isPresent())  nameBuilder.setFamilyName(contact.getName().getFamily().get());
      if (contact.getName().getGiven().isPresent())   nameBuilder.setGivenName(contact.getName().getGiven().get());
      if (contact.getName().getMiddle().isPresent())  nameBuilder.setMiddleName(contact.getName().getMiddle().get());
      if (contact.getName().getPrefix().isPresent())  nameBuilder.setPrefix(contact.getName().getPrefix().get());
      if (contact.getName().getSuffix().isPresent())  nameBuilder.setSuffix(contact.getName().getSuffix().get());
      if (contact.getName().getDisplay().isPresent()) nameBuilder.setDisplayName(contact.getName().getDisplay().get());

      SignalServiceProtos.ContactRecord.Builder contactBuilder    = SignalServiceProtos.ContactRecord.newBuilder().setName(nameBuilder);

      if (contact.getAddress().isPresent()) {
        for (SharedContact.PostalAddress address : contact.getAddress().get()) {
          SignalServiceProtos.ContactRecord.PostalAddress.Builder addressBuilder = SignalServiceProtos.ContactRecord.PostalAddress.newBuilder();

          switch (address.getType()) {
            case HOME:   addressBuilder.setType(SignalServiceProtos.ContactRecord.PostalAddress.Type.HOME); break;
            case WORK:   addressBuilder.setType(SignalServiceProtos.ContactRecord.PostalAddress.Type.WORK); break;
            case CUSTOM: addressBuilder.setType(SignalServiceProtos.ContactRecord.PostalAddress.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + address.getType());
          }

          if (address.getCity().isPresent())         addressBuilder.setCity(address.getCity().get());
          if (address.getCountry().isPresent())      addressBuilder.setCountry(address.getCountry().get());
          if (address.getLabel().isPresent())        addressBuilder.setLabel(address.getLabel().get());
          if (address.getNeighborhood().isPresent()) addressBuilder.setNeighborhood(address.getNeighborhood().get());
          if (address.getPobox().isPresent())        addressBuilder.setPobox(address.getPobox().get());
          if (address.getPostcode().isPresent())     addressBuilder.setPostcode(address.getPostcode().get());
          if (address.getRegion().isPresent())       addressBuilder.setRegion(address.getRegion().get());
          if (address.getStreet().isPresent())       addressBuilder.setStreet(address.getStreet().get());

          contactBuilder.addAddress(addressBuilder);
        }
      }

      if (contact.getEmail().isPresent()) {
        for (SharedContact.Email email : contact.getEmail().get()) {
          SignalServiceProtos.ContactRecord.Email.Builder emailBuilder = SignalServiceProtos.ContactRecord.Email.newBuilder()
                  .setValue(email.getValue());

          switch (email.getType()) {
            case HOME:   emailBuilder.setType(SignalServiceProtos.ContactRecord.Email.Type.HOME);   break;
            case WORK:   emailBuilder.setType(SignalServiceProtos.ContactRecord.Email.Type.WORK);   break;
            case MOBILE: emailBuilder.setType(SignalServiceProtos.ContactRecord.Email.Type.MOBILE); break;
            case CUSTOM: emailBuilder.setType(SignalServiceProtos.ContactRecord.Email.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + email.getType());
          }

          if (email.getLabel().isPresent()) emailBuilder.setLabel(email.getLabel().get());

          contactBuilder.addEmail(emailBuilder);
        }
      }

      if (contact.getPhone().isPresent()) {
        for (SharedContact.Phone phone : contact.getPhone().get()) {
          SignalServiceProtos.ContactRecord.Phone.Builder phoneBuilder = SignalServiceProtos.ContactRecord.Phone.newBuilder()
                  .setValue(phone.getValue());

          switch (phone.getType()) {
            case HOME:   phoneBuilder.setType(SignalServiceProtos.ContactRecord.Phone.Type.HOME);   break;
            case WORK:   phoneBuilder.setType(SignalServiceProtos.ContactRecord.Phone.Type.WORK);   break;
            case MOBILE: phoneBuilder.setType(SignalServiceProtos.ContactRecord.Phone.Type.MOBILE); break;
            case CUSTOM: phoneBuilder.setType(SignalServiceProtos.ContactRecord.Phone.Type.CUSTOM); break;
            default:     throw new AssertionError("Unknown type: " + phone.getType());
          }

          if (phone.getLabel().isPresent()) phoneBuilder.setLabel(phone.getLabel().get());

          contactBuilder.addNumber(phoneBuilder);
        }
      }

      if (contact.getAvatar().isPresent()) {
        SignalServiceProtos.AttachmentRecord pointer = contact.getAvatar().get().getAttachment().isStream() ? createAttachmentRecord(contact.getAvatar().get().getAttachment().asStream())
                                                                                                            : createAttachmentRecord(contact.getAvatar().get().getAttachment().asPointer());
        contactBuilder.setAvatar(SignalServiceProtos.ContactRecord.Avatar.newBuilder()
                                         .setAvatar(pointer)
                                         .setIsProfile(contact.getAvatar().get().isProfile()));
      }

      if (contact.getOrganization().isPresent()) {
        contactBuilder.setOrganization(contact.getOrganization().get());
      }

      if (contact.getUfsrvUid().isPresent()) {
        contactBuilder.setUfsrvuid(ByteString.copyFrom(contact.getUfsrvUid().get().getUfsrvUidRaw()));
      }

      results.add(contactBuilder.build());
    }

    return results;
  }
//

  //
  //plain text is a serialised DataMessage proto
  private OutgoingPushMessageList getEncryptedMessages (PushServiceSocket            socket,
                                                        SignalServiceAddress         recipient,
                                                        Optional<UnidentifiedAccess> unidentifiedAccess,
                                                        long                         timestamp,
                                                        byte[]                       plaintext,
                                                        boolean                      online,
                                                        UfsrvCommand                 ufCommand)
          throws IOException, InvalidKeyException, UntrustedIdentityException
  {
    List<OutgoingPushMessage> messages = new LinkedList<>();

    //- IMPORTANT this not valid check anymore, as we allow the local user to send (self) aka request message to the server
    if (true/*!recipient.equals(localAddress) ||unidentifiedAccess.isPresent()*/) {
      messages.add(getEncryptedMessage(socket, recipient, unidentifiedAccess, SignalServiceAddress.DEFAULT_DEVICE_ID, plaintext, ufCommand));
    }

    for (int deviceId : store.getSubDeviceSessions(recipient.getNumber())) {
      if (store.containsSession(new SignalProtocolAddress(recipient.getNumber(), deviceId))) { // check https://github.com/WhisperSystems/libsignal-service-java/commit/089b7f11239eaa475a78ae064c6df22b5b88b74f
        messages.add(getEncryptedMessage(socket, recipient, unidentifiedAccess, deviceId, plaintext, ufCommand));
      }
    }

    Log.d(TAG, "getEncryptedMessages: List contain messages count: "+messages.size());

    return new OutgoingPushMessageList(recipient.getNumber(), timestamp, messages, online);
  }

  // uf
  private OutgoingPushMessage getEncryptedMessage (PushServiceSocket socket,
                                                   SignalServiceAddress recipient,
                                                   Optional<UnidentifiedAccess> unidentifiedAccess,
                                                   int deviceId,
                                                   byte[] plaintext,
                                                   UfsrvCommand ufCommand)
          throws IOException, InvalidKeyException, UntrustedIdentityException
  {
    SignalProtocolAddress signalProtocolAddress = new SignalProtocolAddress(recipient.getNumber(), deviceId);
    SignalServiceCipher   cipher                = new SignalServiceCipher(localAddress, store, null);

    if (!signalProtocolAddress.getName().equals("0") && !store.containsSession(signalProtocolAddress)) { // 1st conditional
      Log.d(TAG, String.format("getEncryptedMessage: Store doesn't contain session for address: '%s': issuing api call ", signalProtocolAddress.getName()));
      try {
        List<PreKeyBundle> preKeys = socket.getPreKeys(recipient, unidentifiedAccess, deviceId);

        for (PreKeyBundle preKey : preKeys) {
          try {
            SignalProtocolAddress preKeyAddress  = new SignalProtocolAddress(recipient.getNumber(), preKey.getDeviceId());
            SessionBuilder        sessionBuilder = new SessionBuilder(store, preKeyAddress);
            sessionBuilder.process(preKey);
          } catch (org.whispersystems.libsignal.UntrustedIdentityException e) {
            throw new UntrustedIdentityException("Untrusted identity key!", recipient.getNumber(), preKey.getIdentityKey());
          }
        }

        if (eventListener.isPresent()) {
          eventListener.get().onSecurityEvent(recipient);
        }
      } catch (InvalidKeyException e) {
        throw new IOException(e);
      }
    }

    ///>>>>>>>>>>>>>
    try {
      return cipher.encrypt(signalProtocolAddress, unidentifiedAccess, plaintext, /*legacy, */ufCommand);
    } catch (org.whispersystems.libsignal.UntrustedIdentityException e) {
      throw new UntrustedIdentityException("Untrusted on send", recipient.getNumber(), e.getUntrustedIdentity());
    }
    ///>>>>>>>>>>>>
  }


  private void handleMismatchedDevices(PushServiceSocket socket, SignalServiceAddress recipient,
                                       MismatchedDevices mismatchedDevices)
      throws IOException, UntrustedIdentityException
  {
    try {
      for (int extraDeviceId : mismatchedDevices.getExtraDevices()) {
        store.deleteSession(new SignalProtocolAddress(recipient.getNumber(), extraDeviceId));
      }

      for (int missingDeviceId : mismatchedDevices.getMissingDevices()) {
        PreKeyBundle preKey = socket.getPreKey(recipient, missingDeviceId);

        try {
          SessionBuilder sessionBuilder = new SessionBuilder(store, new SignalProtocolAddress(recipient.getNumber(), missingDeviceId));
          sessionBuilder.process(preKey);
        } catch (org.whispersystems.libsignal.UntrustedIdentityException e) {
          throw new UntrustedIdentityException("Untrusted identity key!", recipient.getNumber(), preKey.getIdentityKey());
        }
      }
    } catch (InvalidKeyException e) {
      throw new IOException(e);
    }
  }

  private void handleStaleDevices(SignalServiceAddress recipient, StaleDevices staleDevices) {
    for (int staleDeviceId : staleDevices.getStaleDevices()) {
      store.deleteSession(new SignalProtocolAddress(recipient.getNumber(), staleDeviceId));
    }
  }

  private Optional<UnidentifiedAccess> getTargetUnidentifiedAccess(Optional<UnidentifiedAccessPair> unidentifiedAccess) {
    if (unidentifiedAccess.isPresent()) {
      return unidentifiedAccess.get().getTargetUnidentifiedAccess();
    }

    return Optional.absent();
  }

  private List<Optional<UnidentifiedAccess>> getTargetUnidentifiedAccess(List<Optional<UnidentifiedAccessPair>> unidentifiedAccess) {
    List<Optional<UnidentifiedAccess>> results = new LinkedList<>();

    for (Optional<UnidentifiedAccessPair> item : unidentifiedAccess) {
      if (item.isPresent()) results.add(item.get().getTargetUnidentifiedAccess());
      else                  results.add(Optional.<UnidentifiedAccess>absent());
    }

    return results;
  }

  public static interface EventListener {
    public void onSecurityEvent(SignalServiceAddress address);
  }

}
