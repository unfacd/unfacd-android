package org.thoughtcrime.securesms.jobs;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.R;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.text.TextUtils;
import android.util.Pair;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

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

import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvCallUtils;
import com.unfacd.android.utils.UfsrvMessageUtils;
import com.unfacd.android.utils.UfsrvReceiptUtils;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.signal.libsignal.metadata.SelfSendException;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.attachments.PointerAttachment;
import org.thoughtcrime.securesms.attachments.TombstoneAttachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactModelMapper;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.SecurityEvent;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.crypto.storage.SignalProtocolStoreImpl;
import org.thoughtcrime.securesms.crypto.storage.TextSecureSessionStore;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase;
import org.thoughtcrime.securesms.database.GroupReceiptDatabase.GroupReceiptInfo;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.PushDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.StickerDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.StickerRecord;
import org.thoughtcrime.securesms.groups.GroupMessageProcessor;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingExpirationUpdateMessage;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage;
import org.thoughtcrime.securesms.mms.SlideDeck;
import org.thoughtcrime.securesms.mms.StickerSlide;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.notifications.NotificationChannels;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.revealable.RevealExpirationInfo;
import org.thoughtcrime.securesms.revealable.RevealableMessageManager;
import org.thoughtcrime.securesms.service.WebRtcCallService;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingEndSessionMessage;
import org.thoughtcrime.securesms.sms.OutgoingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.sms.OutgoingEndSessionMessage;
import org.thoughtcrime.securesms.sms.OutgoingTextMessage;
import org.thoughtcrime.securesms.stickers.StickerLocator;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.Hex;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.linkpreview.Link;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil;
import org.thoughtcrime.securesms.logging.Log;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.crypto.SignalServiceCipher;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage.Preview;
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
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.UnsupportedDataMessageException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class PushDecryptJob extends BaseJob {

  public static final String KEY = "PushDecryptJob";

  public static final String TAG = PushDecryptJob.class.getSimpleName();

  private static final String KEY_MESSAGE_ID     = "message_id";
  private static final String KEY_SMS_MESSAGE_ID = "sms_message_id";

  private long messageId;
  private long smsMessageId;

  public PushDecryptJob(Context context) {
    this(context, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId) {
    this(context, pushMessageId, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId, long smsMessageId) {
    this(new Job.Parameters.Builder()
                 .setQueue("__PUSH_DECRYPT_JOB__")
                 .setMaxAttempts(10)
                 .build(),
         pushMessageId,
         smsMessageId);
    setContext(context);
  }

  private PushDecryptJob(@NonNull Job.Parameters parameters, long pushMessageId, long smsMessageId) {
    super(parameters);

    this.messageId    = pushMessageId;
    this.smsMessageId = smsMessageId;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
            .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws NoSuchMessageException {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping, waiting for migration...");
        postMigrationNotification();
        return;
      }

      PushDatabase          database             = DatabaseFactory.getPushDatabase(context);
      SignalServiceEnvelope envelope             = database.get(messageId);
      Optional<Long>        optionalSmsMessageId = smsMessageId > 0 ? Optional.of(smsMessageId) : Optional.absent();

      handleMessage(envelope, optionalSmsMessageId);
      database.delete(messageId);
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    return false;
  }

  @Override
  public void onCanceled() {

  }

  public void processMessage(@NonNull SignalServiceEnvelope envelope) {
    synchronized (PushReceivedJob.RECEIVE_LOCK) {
      if (needsMigration()) {
        Log.w(TAG, "Skipping and storing envelope, waiting for migration...");
        DatabaseFactory.getPushDatabase(context).insert(envelope);
        postMigrationNotification();
        return;
      }

      handleMessage(envelope, Optional.absent());
    }
  }

  private boolean needsMigration() {
    return !IdentityKeyUtil.hasIdentityKey(context) || TextSecurePreferences.getNeedsSqlCipherMigration(context);
  }

  private void postMigrationNotification() {
    NotificationManagerCompat.from(context).notify(494949,
                                                   new NotificationCompat.Builder(context, NotificationChannels.getMessagesChannel(context))
                                                           .setSmallIcon(R.drawable.icon_notification)
                                                           .setPriority(NotificationCompat.PRIORITY_HIGH)
                                                           .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                                                           .setContentTitle(context.getString(R.string.PushDecryptJob_new_locked_message))
                                                           .setContentText(context.getString(R.string.PushDecryptJob_unlock_to_view_pending_messages))
                                                           .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, ConversationListActivity.class), 0))
                                                           .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                                                           .build());

  }

  private void handleMessage(SignalServiceEnvelope envelope, Optional<Long> smsMessageId) {
    try {
      SignalProtocolStore  axolotlStore  = new SignalProtocolStoreImpl(context);
      SignalServiceAddress localAddress  = new SignalServiceAddress(TextSecurePreferences.getUfsrvUserId(context));//
      SignalServiceCipher  cipher        = new SignalServiceCipher(localAddress, axolotlStore, UnidentifiedAccessUtil.getCertificateValidator());

      SignalServiceContent content = cipher.decrypt(envelope);

      //- todo: ignoring blocked message
//      if (shouldIgnore(content)) {
//        Log.i(TAG, "Ignoring message.");
//        return;
//      }

      if (content.getDataMessage().isPresent()) {
        SignalServiceDataMessage message        = content.getDataMessage().get();
        boolean                  isMediaMessage = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent() || message.getPreviews().isPresent() || message.getSticker().isPresent();

        //
        if (TextUtils.isEmpty(envelope.getSource()) || envelope.getSource().equals("0"))
          handleUfsrvCommandMessage(envelope, message, Long.valueOf(messageId), smsMessageId);

        //essentially all of the conditionals below are defunct and left there for reference
        else if (isInvalidMessage(message))               handleInvalidMessage(content.getSender(), content.getSenderDevice(), message.getGroupInfo(), content.getTimestamp(), smsMessageId);
        else if (message.isEndSession())                  handleEndSessionMessage(envelope, message, smsMessageId);
        else if (message.isGroupUpdate())                 Log.e(TAG, "handleMessage: RECEIVED GROUPUPDATE MESSAGE ON UNSUPPORTED PATH");//handleGroupMessage(masterSecret, envelope, message, smsMessageId);//covered
        else if (message.isExpirationUpdate())            handleExpirationUpdate(envelope, message, smsMessageId);//handleExpirationUpdate(masterSecret, envelope, message, smsMessageId);//covered
        else if (isMediaMessage)                          Log.e(TAG, "handleMessage: RECEIVED ATTACHMENT MESSAGE ON UNSUPPORTED PATH");//handleMediaMessage(masterSecret, envelope, message, smsMessageId);//covered
        else if (message.getBody().isPresent())           handleTextMessage(envelope, message, smsMessageId);

        // todo: need to think about this.. check commit cf01959e16acc1ff31cd007d68b3ba43810b6c3a
//       if (message.getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false))) {
//            handleUnknownGroupMessage(envelope, message.getGroupInfo().get());
//        }
        if (message.getProfileKey().isPresent() && message.getProfileKey().get().length == 32) {
          handleProfileKey(content, message);
        }

        if (content.isNeedsReceipt()) {
          handleNeedsDeliveryReceipt(content, message);
        }
      } else if (content.getSyncMessage().isPresent()) {
        TextSecurePreferences.setMultiDevice(context, true);
        SignalServiceSyncMessage syncMessage = content.getSyncMessage().get();

        if      (syncMessage.getSent().isPresent())                  handleSynchronizeSentMessage(content, envelope, syncMessage.getSent().get());// envelope
        else if (syncMessage.getRequest().isPresent())               handleSynchronizeRequestMessage(syncMessage.getRequest().get());
        else if (syncMessage.getRead().isPresent())                  handleSynchronizeReadMessage(syncMessage.getRead().get(), content.getTimestamp());
        else if (syncMessage.getMessageTimerRead().isPresent())      handleSynchronizeMessageTimerReadMessage(syncMessage.getMessageTimerRead().get(), content.getTimestamp());
        else if (syncMessage.getVerified().isPresent())              handleSynchronizeVerifiedMessage(syncMessage.getVerified().get());
        else if (syncMessage.getStickerPackOperations().isPresent()) handleSynchronizeStickerPackOperation(syncMessage.getStickerPackOperations().get());
        else                                                         Log.w(TAG, "Contains no known sync types...");
      } else if (content.getCallMessage().isPresent()) {
        Log.w(TAG, "Got call message...");
        SignalServiceCallMessage message = content.getCallMessage().get();

        if      (message.getOfferMessage().isPresent())      handleCallOfferMessage(envelope, message.getOfferMessage().get(), smsMessageId);
        else if (message.getAnswerMessage().isPresent())     handleCallAnswerMessage(envelope, message.getAnswerMessage().get());
        else if (message.getIceUpdateMessages().isPresent()) handleCallIceUpdateMessage(envelope, message.getIceUpdateMessages().get());
        else if (message.getHangupMessage().isPresent())     handleCallHangupMessage(envelope, message.getHangupMessage().get(), smsMessageId);
        else if (message.getBusyMessage().isPresent())       handleCallBusyMessage(envelope, message.getBusyMessage().get());
      } else if (content.getReceiptMessage().isPresent()) {
        SignalServiceReceiptMessage message = content.getReceiptMessage().get();

        if      (message.isReadReceipt())     handleReadReceipt(content, message);
        else if (message.isDeliveryReceipt()) handleDeliveryReceipt(content, message);
      } else if (content.getTypingMessage().isPresent()) {
        handleTypingMessage(content, content.getTypingMessage().get());
      } else {
        Log.w(TAG, "Got unrecognized message...");
      }

      if (!TextUtils.isEmpty(content.getSender()))  resetRecipientToPush(Recipient.from(context, Address.fromExternal(context, content.getSender()), false));// conditional

      if (envelope.isPreKeySignalMessage()) {
        ApplicationContext.getInstance(context).getJobManager().add(new RefreshPreKeysJob());
      }
    } catch (ProtocolInvalidVersionException e) {
      Log.w(TAG, e);
      handleInvalidVersionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolInvalidMessageException  e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolInvalidKeyIdException | ProtocolInvalidKeyException | ProtocolUntrustedIdentityException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (StorageFailedException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolNoSessionException e) {
      Log.w(TAG, e);
      handleNoSessionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolLegacyMessageException e) {
      Log.w(TAG, e);
      handleLegacyMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolDuplicateMessageException e) {
      Log.w(TAG, e);
      handleDuplicateMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (InvalidMetadataVersionException | InvalidMetadataMessageException e) {
      Log.w(TAG, e);
    } catch (SelfSendException e) {
      Log.i(TAG, "Dropping UD message from self.");
    } catch (MmsException e) {
      Log.w(TAG, e);
    } catch (UnsupportedDataMessageException e) {
      Log.w(TAG, e);
      handleUnsupportedDataMessage(e.getSender(), e.getSenderDevice(), e.getGroup(), envelope.getTimestamp(), smsMessageId);
    }
  }


  // linked with Long processCallCommandOffer
  private void handleCallOfferMessage(@NonNull SignalServiceEnvelope envelope,
                                      @NonNull OfferMessage message,
                                      @NonNull Optional<Long> smsMessageId)
  {
    Log.w(TAG, "handleCallOfferMessage...");

    if (smsMessageId.isPresent()) {
      SmsDatabase database = DatabaseFactory.getSmsDatabase(context);
      database.markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_INCOMING_CALL);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, envelope.getSource()));
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());
      intent.putExtra(WebRtcCallService.EXTRA_TIMESTAMP, envelope.getTimestamp());
      context.startService(intent);
    }
  }

  // linked with processCallCommandAnswer
  private void handleCallAnswerMessage(@NonNull SignalServiceEnvelope envelope,
                                       @NonNull AnswerMessage message)
  {
    Log.w(TAG, "handleCallAnswerMessage...");
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_RESPONSE_MESSAGE);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, envelope.getSource()));
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());
    context.startService(intent);
  }


  // linked with processCallCommandIceUpdate
  private void handleCallIceUpdateMessage(@NonNull SignalServiceEnvelope envelope,
                                          @NonNull List<IceUpdateMessage> messages)
  {
    Log.w(TAG, "handleCallIceUpdateMessage... " + messages.size());
    for (IceUpdateMessage message : messages) {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_ICE_MESSAGE);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, envelope.getSource()));
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP, message.getSdp());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_MID, message.getSdpMid());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_LINE_INDEX, message.getSdpMLineIndex());
      context.startService(intent);
    }
  }

  // linked with processCallCommandHangUp
  private void handleCallHangupMessage(@NonNull SignalServiceEnvelope envelope,
                                       @NonNull HangupMessage message,
                                       @NonNull Optional<Long> smsMessageId)
  {
    Log.w(TAG, "handleCallHangupMessage");
    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_REMOTE_HANGUP);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, envelope.getSource()));
      context.startService(intent);
    }
  }

  // linked with processCallCommandBusy NOT TESTED https://github.com/WhisperSystems/Signal-Android/commit/b50a3fa2b80fc26d214596b4331724b4922b565e
  private void handleCallBusyMessage(@NonNull SignalServiceEnvelope envelope,
                                     @NonNull BusyMessage message)
  {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_REMOTE_BUSY);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_ADDRESS, Address.fromExternal(context, envelope.getSource()));
    context.startService(intent);
  }

  private void handleEndSessionMessage(@NonNull SignalServiceEnvelope    envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @NonNull Optional<Long>           smsMessageId)
  {
    SmsDatabase         smsDatabase         = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage incomingTextMessage = new IncomingTextMessage(Address.fromExternal(context, envelope.getSource()),
                                                                      envelope.getSourceDevice(),
                                                                      message.getTimestamp(),
                                                                      "", Optional.absent(), 0, 0, false); //todo: as last arg content.isNeedsReceipt()

    Long threadId;

    if (!smsMessageId.isPresent()) {
      IncomingEndSessionMessage incomingEndSessionMessage = new IncomingEndSessionMessage(incomingTextMessage);
      Optional<InsertResult>    insertResult              = smsDatabase.insertMessageInbox(incomingEndSessionMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;
    } else {
      smsDatabase.markAsEndSession(smsMessageId.get());
      threadId = smsDatabase.getThreadIdForMessage(smsMessageId.get());
    }

    if (threadId != null) {
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(envelope.getSource());

      SecurityEvent.broadcastSecurityUpdateEvent(context);
      MessageNotifier.updateNotification(context, threadId);
    }
  }

  private long handleSynchronizeSentEndSessionMessage(@NonNull SentTranscriptMessage message)
  {
    SmsDatabase               database                  = DatabaseFactory.getSmsDatabase(context);
    Recipient                 recipient                 = getSyncMessageDestination(message);
    OutgoingTextMessage       outgoingTextMessage       = new OutgoingTextMessage(recipient, "", -1);
    OutgoingEndSessionMessage outgoingEndSessionMessage = new OutgoingEndSessionMessage(outgoingTextMessage);

    long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);

    if (!recipient.isGroupRecipient()) {
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(recipient.getAddress().toPhoneString());

      SecurityEvent.broadcastSecurityUpdateEvent(context);

      long messageId = database.insertMessageOutbox(threadId, outgoingEndSessionMessage,
                                                    false, message.getTimestamp(),
                                                    null);
      database.markAsSent(messageId, true);
    }

    return threadId;
  }

  private void handleGroupMessage(@NonNull SignalServiceEnvelope envelope,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    GroupMessageProcessor.process(context, envelope, message, false);

    if (message.getExpiresInSeconds() != 0 && message.getExpiresInSeconds() != getMessageDestination(envelope, message).getExpireMessages()) {
      handleExpirationUpdate(envelope, message, Optional.absent());
    }

    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
    }
  }


  private void handleUfsrvCommandMessage(@NonNull SignalServiceEnvelope envelope,
                                          @NonNull SignalServiceDataMessage message,
                                          @NonNull Long messageId,
                                          @NonNull Optional<Long> smsMessageId)
          throws MmsException
  {
    Log.d(TAG, String.format("handleUfsrvCommandMessage (%d): PushMessageId:'%d' with 'outgoing' flag set 'false'", Thread.currentThread().getId(), messageId));

    switch (message.getUfsrvCommand().getUfsrvtype()) {
      case UFSRV_FENCE:
        GroupMessageProcessor.processUfsrvFenceCommand(context, envelope, message, smsMessageId, false);
        //orig
        if (smsMessageId.isPresent()) {
          DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
        }
        break;

      case UFSRV_MESSAGE:
        UfsrvMessageUtils.processUfsrvMessageCommand(context, envelope, message, messageId, smsMessageId, false);
        break;

      case UFSRV_USER:
        UfsrvUserUtils.processUfsrvUserCommand (context, envelope, message, smsMessageId, false);
        break;

      case UFSRV_CALL:
        UfsrvCallUtils.processUfsrvCallCommand (context, envelope, message, smsMessageId, false);
        break;

      case UFSRV_RECEIPT:
        UfsrvReceiptUtils.processUfsrvReceiptCommand (context, envelope, message, smsMessageId, false);
        break;

      case UFSRV_SYNC:
        Log.w(TAG, "handleUfsrvCommandMessage: RECEIVED UfsrvMessage of UNSUPPORTED TYPE (SYNC)");
        break;

      default:
        Log.w(TAG, "handleUfsrvCommandMessage: RECEIVED UfsrvMessage of UNKNOWN TYPE");
    }

  }
  //

  private void handleUnknownGroupMessage(@NonNull SignalServiceEnvelope envelope,
                                          @NonNull SignalServiceGroup group)
  {
      ApplicationContext.getInstance(context)
        .getJobManager()
        .add(new RequestGroupInfoJob(envelope.getSource(), group.getGroupId()));
    }

  private void handleExpirationUpdate(@NonNull SignalServiceEnvelope envelope,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    try {
      MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
      Recipient recipient = getMessageDestination(envelope, message);
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(Address.fromExternal(context, envelope.getSource()),
                                                                   message.getTimestamp(), -1,
                                                                   message.getExpiresInSeconds() * 1000, true, 0,
                                                                   false,
                                                                   Optional.absent(),
                                                                   message.getGroupInfo(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   Optional.absent(),
                                                                   envelope.getUfsrvCommand());//


      database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

      DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, message.getExpiresInSeconds());

      if (smsMessageId.isPresent()) {
        DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, "sender", 0);//DEVICEcontent.getSender(), content.getSenderDevice());
    }
  }

  private void handleSynchronizeVerifiedMessage(@NonNull VerifiedMessage verifiedMessage) {
    IdentityUtil.processVerifiedMessage(context, verifiedMessage);
  }

  private void handleSynchronizeStickerPackOperation(@NonNull List<StickerPackOperationMessage> stickerPackOperations) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();

    for (StickerPackOperationMessage operation : stickerPackOperations) {
      if (operation.getPackId().isPresent() && operation.getPackKey().isPresent() && operation.getType().isPresent()) {
        String packId  = Hex.toStringCondensed(operation.getPackId().get());
        String packKey = Hex.toStringCondensed(operation.getPackKey().get());

        switch (operation.getType().get()) {
          case INSTALL:
            jobManager.add(new StickerPackDownloadJob(packId, packKey, false));
            break;
          case REMOVE:
            DatabaseFactory.getStickerDatabase(context).uninstallPack(packId);
            break;
        }
      } else {
        Log.w(TAG, "Received incomplete sticker pack operation sync.");
      }
    }
  }

  // this is not ported
  private void handleSynchronizeSentMessage(@NonNull SignalServiceContent content,
                                            @NonNull SignalServiceEnvelope envelope, // retained envelope (presealed)
                                            @NonNull SentTranscriptMessage message)
          throws StorageFailedException

  {
    try {
      GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);

      Long threadId = null;

      if (message.isRecipientUpdate()) {
        handleGroupRecipientUpdate(message);
      } else if (message.getMessage().isEndSession()) {
        threadId = handleSynchronizeSentEndSessionMessage(message);
      } else if (message.getMessage().isGroupUpdate()) {
        threadId = GroupMessageProcessor.process(context, envelope/*content*/, message.getMessage(), true);
      } else if (message.getMessage().isExpirationUpdate()) {
        threadId = handleSynchronizeSentExpirationUpdate(message);
      } else if (message.getMessage().getAttachments().isPresent() || message.getMessage().getQuote().isPresent() || message.getMessage().getPreviews().isPresent() || message.getMessage().getSticker().isPresent()) {
        threadId = handleSynchronizeSentMediaMessage(message);
      } else {
        threadId = handleSynchronizeSentTextMessage(message);
      }

      if (message.getMessage().getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false))) {
        handleUnknownGroupMessage(envelope/*content*/, message.getMessage().getGroupInfo().get());
      }

      if (message.getMessage().getProfileKey().isPresent()) {
        Recipient recipient = null;

        if      (message.getDestination().isPresent())            recipient = Recipient.from(context, Address.fromExternal(context, message.getDestination().get()), false);
        else if (message.getMessage().getGroupInfo().isPresent()) recipient = Recipient.from(context, Address.fromSerialized(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false)), false);


        if (recipient != null && !recipient.isSystemContact() && !recipient.isProfileSharing()) {
          DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipient, true);
        }
      }

      if (threadId != null) {
        DatabaseFactory.getThreadDatabase(context).setRead(threadId, true);
        MessageNotifier.updateNotification(context);
      }

      MessageNotifier.setLastDesktopActivityTimestamp(message.getTimestamp());
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  // incoming request from another synchronised device to forward this device's list/group info
  private void handleSynchronizeRequestMessage(@NonNull RequestMessage message)
  {
    if (message.isContactsRequest()) {
      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new MultiDeviceContactUpdateJob(context, true));
      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new RefreshUnidentifiedDeliveryAbilityJob());
    }

    if (message.isGroupsRequest()) {
      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new MultiDeviceGroupUpdateJob());
    }

    if (message.isBlockedListRequest()) {
      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new MultiDeviceBlockedUpdateJob());
    }

    if (message.isConfigurationRequest()) {
        ApplicationContext.getInstance(context)
         .getJobManager()
         .add(new MultiDeviceConfigurationUpdateJob(TextSecurePreferences.isReadReceiptsEnabled(context),
                                                           TextSecurePreferences.isTypingIndicatorsEnabled(context),
                                                           TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(context),
                                                           TextSecurePreferences.isLinkPreviewsEnabled(context)));

      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new MultiDeviceStickerPackSyncJob());
      }
  }


  // other synchronised device has read referenced message --> mark it as read here as well, and set off the expiry mechanism which currently only kicks in on read messages
  private void handleSynchronizeReadMessage(@NonNull List<ReadMessage> readMessages, long envelopeTimestamp)
  {
    for (ReadMessage readMessage : readMessages) {
      List<Pair<Long, Long>> expiringText = DatabaseFactory.getSmsDatabase(context).setTimestampRead(new SyncMessageId(Address.fromExternal(context, readMessage.getSender()), readMessage.getTimestamp()), envelopeTimestamp);
      List<Pair<Long, Long>> expiringMedia = DatabaseFactory.getMmsDatabase(context).setTimestampRead(new SyncMessageId(Address.fromExternal(context, readMessage.getSender()), readMessage.getTimestamp()), envelopeTimestamp);

      for (Pair<Long, Long> expiringMessage : expiringText) {
        ApplicationContext.getInstance(context)
                .getExpiringMessageManager()
                .scheduleDeletion(expiringMessage.first, false, envelopeTimestamp, expiringMessage.second);
      }

      for (Pair<Long, Long> expiringMessage : expiringMedia) {
        ApplicationContext.getInstance(context)
                .getExpiringMessageManager()
                .scheduleDeletion(expiringMessage.first, true, envelopeTimestamp, expiringMessage.second);
      }
    }

    MessageNotifier.setLastDesktopActivityTimestamp(envelopeTimestamp);
    MessageNotifier.cancelDelayedNotifications();
    MessageNotifier.updateNotification(context);
  }


  private void handleSynchronizeMessageTimerReadMessage(@NonNull MessageTimerReadMessage timerMessage, long envelopeTimestamp) {
    SyncMessageId messageId = new SyncMessageId(Address.fromExternal(context, timerMessage.getSender()), timerMessage.getTimestamp());

    DatabaseFactory.getMmsDatabase(context).markRevealStarted(messageId, envelopeTimestamp);
    ApplicationContext.getInstance(context).getRevealableMessageManager().scheduleIfNecessary();

    MessageNotifier.setLastDesktopActivityTimestamp(envelopeTimestamp);
    MessageNotifier.cancelDelayedNotifications();
    MessageNotifier.updateNotification(context);
  }

  // IMPORTANT PORTING NOTE: reimplemented in UfsrvMessageUtils.processMessageCommandSayWithMedia() message with attachment
  //check as in https://github.com/signalapp/Signal-Android/commit/5f31762220fa5ca4a00138e74500bbe4b5232578
  /*private void handleMediaMessage(@NonNull SignalServiceContent content,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    notifyTypingStoppedFromIncomingMessage(getMessageDestination(content., message), content.getSender(), content.getSenderDevice());

    Optional<InsertResult> insertResult;

    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    database.beginTransaction();

    try {
      Optional<QuoteModel>        quote          = getValidatedQuote(message.getQuote());
      Optional<List<Contact>>     sharedContacts = getContacts(message.getSharedContacts());
      Optional<List<LinkPreview>> linkPreviews   = getLinkPreviews(message.getPreviews(), message.getBody().or(""));
      Optional<Attachment>        sticker        = getStickerAttachment(message.getSticker());
      IncomingMediaMessage        mediaMessage   = new IncomingMediaMessage(Address.fromExternal(context, content.getSender()),
                                                                            message.getTimestamp(), -1,
                                                                            message.getExpiresInSeconds() * 1000L, false,
                                                                            message.getMessageTimerInSeconds() * 1000,
                                                                            content.isNeedsReceipt(),
                                                                            message.getBody(),
                                                                            message.getGroupInfo(),
                                                                            message.getAttachments(),
                                                                            quote,
                                                                            sharedContacts,
                                                                            linkPreviews,
                                                                            sticker,
                                                                            null);


      insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

      if (insertResult.isPresent()) {
        List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());
        List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
        List<DatabaseAttachment> attachments        = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

        forceStickerDownloadIfNecessary(stickerAttachments);

        for (DatabaseAttachment attachment : attachments) {
          ApplicationContext.getInstance(context)
                  .getJobManager()
                  .add(new AttachmentDownloadJob(insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
        }

        if (smsMessageId.isPresent()) {
          DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
        }

        database.setTransactionSuccessful();
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    } finally {
      database.endTransaction();
    }

    if (insertResult.isPresent()) {
      MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      if (message.getMessageTimerInSeconds() > 0) {
        ApplicationContext.getInstance(context).getRevealableMessageManager().scheduleIfNecessary();
      }
    }
  }*/

  private long handleSynchronizeSentExpirationUpdate(@NonNull SentTranscriptMessage message)
          throws MmsException
  {
    MmsDatabase database   = DatabaseFactory.getMmsDatabase(context);
    Recipient   recipient  = getSyncMessageDestination(message);

    OutgoingExpirationUpdateMessage expirationUpdateMessage = new OutgoingExpirationUpdateMessage(recipient,
            message.getTimestamp(),
            message.getMessage().getExpiresInSeconds() * 1000, (String)null);//support for ufsrcomman. this should be converted to native ibjecy as ppsed to encoded

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    long messageId = database.insertMessageOutbox(expirationUpdateMessage, threadId, false, null);

    database.markAsSent(messageId, true);

    DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient, message.getMessage().getExpiresInSeconds());

    return threadId;
  }

  private long handleSynchronizeSentMediaMessage(@NonNull SentTranscriptMessage message)
          throws MmsException
  {
    MmsDatabase                 database        = DatabaseFactory.getMmsDatabase(context);
    Recipient                   recipients      = getSyncMessageDestination(message);
    Optional<QuoteModel>        quote           = getValidatedQuote(message.getMessage().getQuote());
    Optional<Attachment>        sticker         = getStickerAttachment(message.getMessage().getSticker());
    Optional<List<Contact>>     sharedContacts  = getContacts(message.getMessage().getSharedContacts());
    Optional<List<LinkPreview>> previews        = getLinkPreviews(message.getMessage().getPreviews(), message.getMessage().getBody().or(""));
    long                        messageTimer    = message.getMessage().getMessageTimerInSeconds() * 1000;
    List<Attachment>            syncAttachments = messageTimer == 0 ? PointerAttachment.forPointers(message.getMessage().getAttachments()) : Collections.emptyList();

    if (sticker.isPresent()) {
      syncAttachments.add(sticker.get());
    }

    OutgoingMediaMessage mediaMessage = new OutgoingMediaMessage(recipients, message.getMessage().getBody().orNull(),
                                                                 syncAttachments,
                                                                 message.getTimestamp(), -1,
                                                                 message.getMessage().getExpiresInSeconds() * 1000,
                                                                 messageTimer,
                                                                 ThreadDatabase.DistributionTypes.DEFAULT, quote.orNull(),
                                                                 sharedContacts.or(Collections.emptyList()),
                                                                 previews.or(Collections.emptyList()),
                                                                 Collections.emptyList(), Collections.emptyList(),
                                                                 (SignalServiceProtos.UfsrvCommandWire)null); //

    mediaMessage = new OutgoingSecureMediaMessage(mediaMessage);

    if (recipients.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);

    database.beginTransaction();

    try {
      long messageId = database.insertMessageOutbox(mediaMessage, threadId, false, GroupReceiptDatabase.STATUS_UNKNOWN, null);

      if (recipients.getAddress().isGroup()) {
        updateGroupReceiptStatus(message, messageId, recipients.getAddress().toGroupString());
      }

      database.markAsSent(messageId, true);
      database.markUnidentified(messageId, message.isUnidentified(recipients.getAddress().serialize()));

      List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(messageId);
      List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
      List<DatabaseAttachment> attachments        = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

      forceStickerDownloadIfNecessary(stickerAttachments);

      for (DatabaseAttachment attachment : attachments) {
        ApplicationContext.getInstance(context)
                .getJobManager()
                .add(new AttachmentDownloadJob(messageId, attachment.getAttachmentId(), false));
      }

      if (message.getMessage().getExpiresInSeconds() > 0) {
        database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
        ApplicationContext.getInstance(context)
                .getExpiringMessageManager()
                .scheduleDeletion(messageId, true,
                                  message.getExpirationStartTimestamp(),
                                  message.getMessage().getExpiresInSeconds() * 1000L);
      }

      if (recipients.isLocalNumber()) {
        SyncMessageId id = new SyncMessageId(recipients.getAddress(), message.getTimestamp());
        DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(id, System.currentTimeMillis());
        DatabaseFactory.getMmsSmsDatabase(context).incrementReadReceiptCount(id, System.currentTimeMillis());
      }

      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
    return threadId;
  }

  private void handleGroupRecipientUpdate(@NonNull SentTranscriptMessage message) {
    Recipient recipient = getSyncMessageDestination(message);

    if (!recipient.isGroupRecipient()) {
      Log.w(TAG, "Got recipient update for a non-group message! Skipping.");
      return;
    }

    MmsSmsDatabase database = DatabaseFactory.getMmsSmsDatabase(context);
    MessageRecord  record   = database.getMessageFor(message.getTimestamp(),
                                                     Address.fromSerialized(TextSecurePreferences.getLocalNumber(context)));

    if (record == null) {
      Log.w(TAG, "Got recipient update for non-existing message! Skipping.");
      return;
    }

    if (!record.isMms()) {
      Log.w(TAG, "Recipient update matched a non-MMS message! Skipping.");
      return;
    }

    updateGroupReceiptStatus(message, record.getId(), recipient.getAddress().toGroupString());
  }

  private void updateGroupReceiptStatus(@NonNull SentTranscriptMessage message, long messageId, @NonNull String groupString) {
    GroupReceiptDatabase receiptDatabase = DatabaseFactory.getGroupReceiptDatabase(context);
    List<Recipient>      members         = DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupString, false);
    Map<String, Integer> localReceipts   = Stream.of(receiptDatabase.getGroupReceiptInfo(messageId))
            .collect(Collectors.toMap(info -> info.getAddress().serialize(), GroupReceiptInfo::getStatus));

    for (String address : message.getRecipients()) {
      //noinspection ConstantConditions
      if (localReceipts.containsKey(address) && localReceipts.get(address) < GroupReceiptDatabase.STATUS_UNDELIVERED) {
        receiptDatabase.update(Address.fromSerialized(address), messageId, GroupReceiptDatabase.STATUS_UNDELIVERED, message.getTimestamp());
      } else if (!localReceipts.containsKey(address)) {
        receiptDatabase.insert(Collections.singletonList(Address.fromSerialized(address)), messageId, GroupReceiptDatabase.STATUS_UNDELIVERED, message.getTimestamp());
      }
    }

    for (Recipient member : members) {
      receiptDatabase.setUnidentified(member.getAddress(), messageId, message.isUnidentified(member.getAddress().serialize()));
    }
  }

  // message to individual or a group without a media attachment
  //IMPORTANT PORTING NOTE: see UfsrvMessageUtils.processMessageCommandSay()
  private void handleTextMessage(@NonNull SignalServiceEnvelope envelope,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    SmsDatabase database  = DatabaseFactory.getSmsDatabase(context);
    String      body      = message.getBody().isPresent() ? message.getBody().get() : "";
    Recipient   recipient = getMessageDestination(envelope, message);

    if (message.getExpiresInSeconds() != recipient.getExpireMessages()) {
      handleExpirationUpdate(envelope, message, Optional.absent());
    }

    Long threadId;

    if (smsMessageId.isPresent() && !message.getGroupInfo().isPresent()) {
      threadId = database.updateBundleMessageBody(smsMessageId.get(), body).second;
    } else {
      notifyTypingStoppedFromIncomingMessage(recipient, envelope.getSourceAddress().toString()/*content.getSender()*/, envelope.getSourceDevice()/*content.getSenderDevice()*/);
      IncomingTextMessage textMessage = new IncomingTextMessage(Address.fromExternal(context, envelope.getSource()),
                                                                envelope.getSourceDevice(),
                                                                message.getTimestamp(), body,
                                                                message.getGroupInfo(),
                                                                message.getExpiresInSeconds() * 1000,
                                                                message.getMessageTimerInSeconds() * 1000L,false);

      textMessage = new IncomingEncryptedMessage(textMessage, body);
      Optional<InsertResult> insertResult = database.insertMessageInbox(textMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());
    }

    if (threadId != null) {
      MessageNotifier.updateNotification(context, threadId);
    }
  }

  private long handleSynchronizeSentTextMessage(@NonNull SentTranscriptMessage message)
          throws MmsException
  {

    Recipient recipient       = getSyncMessageDestination(message);
    String    body            = message.getMessage().getBody().or("");
    long      expiresInMillis = message.getMessage().getExpiresInSeconds() * 1000;

    if (recipient.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long    threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    boolean isGroup   = recipient.getAddress().isGroup();

    MessagingDatabase database;
    long              messageId;

    if (isGroup) {
      OutgoingMediaMessage outgoingMediaMessage = new OutgoingMediaMessage(recipient, new SlideDeck(), body, message.getTimestamp(), -1, expiresInMillis, 0, ThreadDatabase.DistributionTypes.DEFAULT, null, Collections.emptyList(), Collections.emptyList());
      outgoingMediaMessage = new OutgoingSecureMediaMessage(outgoingMediaMessage);

      messageId = DatabaseFactory.getMmsDatabase(context).insertMessageOutbox(outgoingMediaMessage, threadId, false, GroupReceiptDatabase.STATUS_UNKNOWN, null);
      database  = DatabaseFactory.getMmsDatabase(context);

      updateGroupReceiptStatus(message, messageId, recipient.getAddress().toGroupString());
    } else {
      OutgoingTextMessage outgoingTextMessage = new OutgoingEncryptedMessage(recipient, body, expiresInMillis);

      messageId = DatabaseFactory.getSmsDatabase(context).insertMessageOutbox(threadId, outgoingTextMessage, false, message.getTimestamp(), null);
      database  = DatabaseFactory.getSmsDatabase(context);
      database.markUnidentified(messageId, message.isUnidentified(recipient.getAddress().serialize()));
    }

    database.markAsSent(messageId, true);

    if (expiresInMillis > 0) {
      database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
      ApplicationContext.getInstance(context)
              .getExpiringMessageManager()
              .scheduleDeletion(messageId, isGroup, message.getExpirationStartTimestamp(), expiresInMillis);
    }

    if (recipient.isLocalNumber()) {
      SyncMessageId id = new SyncMessageId(recipient.getAddress(), message.getTimestamp());
      DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(id, System.currentTimeMillis());
      DatabaseFactory.getMmsSmsDatabase(context).incrementReadReceiptCount(id, System.currentTimeMillis());
    }

    return threadId;
  }

  // IMPORTANT PORTING NOTE for the next few methods check mirrored implementation in UfsrvMessageUtils in the context of e2ee implementation
  private void handleInvalidVersionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                           @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidVersionKeyExchange(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsInvalidVersionKeyExchange(smsMessageId.get());
    }
  }

  private void handleCorruptMessage(@NonNull String sender, int senderDevice, long timestamp,
                                    @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsDecryptFailed(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsDecryptFailed(smsMessageId.get());
    }
  }

  private void handleNoSessionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsNoSession(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  private void handleUnsupportedDataMessage(@NonNull String sender,
                                            int senderDevice,
                                            @NonNull Optional<SignalServiceGroup> group,
                                            long timestamp,
                                            @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp, group);

      if (insertResult.isPresent()) {
        smsDatabase.markAsUnsupportedProtocolVersion(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  private void handleInvalidMessage(@NonNull String sender,
                                    int senderDevice,
                                    @NonNull Optional<SignalServiceGroup> group,
                                    long timestamp,
                                    @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp, group);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidMessage(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  private void handleLegacyMessage(@NonNull String sender, int senderDevice, long timestamp,
                                   @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsLegacyVersion(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsLegacyVersion(smsMessageId.get());
    }
  }

  @SuppressWarnings("unused")
  private void handleDuplicateMessage(@NonNull String sender, int senderDeviceId, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    // Let's start ignoring these now
//    SmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
//
//    if (smsMessageId <= 0) {
//      Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
//      smsDatabase.markAsDecryptDuplicate(messageAndThreadId.first);
//      MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
//    } else {
//      smsDatabase.markAsDecryptDuplicate(smsMessageId);
//    }
  }


  //currently only for usr message command
  private Optional<SyncMessageId> buildSyncMessageId (SignalServiceDataMessage message)
  {
    if (message.getUfsrvCommand().hasMsgCommand()) {
      SignalServiceProtos.CommandHeader header = message.getUfsrvCommand().getMsgCommand().getHeader();

      UfsrvUid ufsrvUid = new UfsrvUid(message.getUfsrvCommand().getMsgCommand().getOriginator().getUfsrvuid().toByteArray());

      SyncMessageId syncMessageId = new SyncMessageId(Address.fromSerialized(ufsrvUid.getUfsrvUidEncoded()),
                                                      header.getWhenClient(),
                                                      ufsrvUid.getUfsrvUidEncoded(),
                                                      0,
                                                      header.getEid());

      return Optional.of(syncMessageId);
    }

    return Optional.absent();
  }

  private void handleNeedsDeliveryReceipt(@NonNull SignalServiceContent content,
                                          @NonNull SignalServiceDataMessage message)
  {
    //
    Optional<SyncMessageId>  syncMessageId  = buildSyncMessageId(message);
    if (syncMessageId.isPresent()) {
      UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier = syncMessageId.get().getUfsrvMessageIdentifier();

      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new SendDeliveryReceiptJob(syncMessageId.get().getAddress(), message.getTimestamp(), messageIdentifier));// messageIdentifier
    }
  }

  // PORTING NOTE: see UfsrvReceiptUtils.handleDeliveryreceipt
  @SuppressLint("DefaultLocale")
  private void handleDeliveryReceipt(@NonNull SignalServiceContent content,
                                     @NonNull SignalServiceReceiptMessage message)
  {
    for (long timestamp : message.getTimestamps()) {
      Log.i(TAG, String.format("Received encrypted delivery receipt: (XXXXX, %d)", timestamp));
      DatabaseFactory.getMmsSmsDatabase(context)
              .incrementDeliveryReceiptCount(new SyncMessageId(Address.fromExternal(context, content.getSender()), timestamp), System.currentTimeMillis());
    }
  }

  // PORTING NOTE: see UfsrvReceiptUtils.handleReadreceipt
  @SuppressLint("DefaultLocale")
  private void handleReadReceipt(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {
      for (long timestamp : message.getTimestamps()) {
        Log.w(TAG, String.format("Received encrypted read receipt: (XXXXX, %d)", timestamp));

        DatabaseFactory.getMmsSmsDatabase(context)
                .incrementReadReceiptCount(new SyncMessageId(Address.fromExternal(context, content.getSender()), timestamp), content.getTimestamp());
      }
    }
  }

  private void handleProfileKey(@NonNull SignalServiceContent content,
                                @NonNull SignalServiceDataMessage message)
  {
    RecipientDatabase database      = DatabaseFactory.getRecipientDatabase(context);
    Address           sourceAddress = Address.fromExternal(context, content.getSender());
    Recipient         recipient     = Recipient.from(context, sourceAddress, false);

    if (recipient.getProfileKey() == null || !MessageDigest.isEqual(recipient.getProfileKey(), message.getProfileKey().get())) {
      database.setProfileKey(recipient, message.getProfileKey().get());
      database.setUnidentifiedAccessMode(recipient, RecipientDatabase.UnidentifiedAccessMode.UNKNOWN);
      ApplicationContext.getInstance(context).getJobManager().add(new RetrieveProfileJob(recipient));
    }
  }

  private void handleTypingMessage(@NonNull SignalServiceContent content,
                                   @NonNull SignalServiceTypingMessage typingMessage)
  {
    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return;
    }

    Recipient author = Recipient.from(context, Address.fromExternal(context, content.getSender()), false);

    long threadId;

    if (typingMessage.getGroupId().isPresent()) {
      Address   groupAddress   = Address.fromExternal(context, GroupUtil.getEncodedId(typingMessage.getGroupId().get(), false));
      Recipient groupRecipient = Recipient.from(context, groupAddress, false);

      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
    } else {
      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(author);
    }

    if (threadId <= 0) {
      Log.w(TAG, "Couldn't find a matching thread for a typing message.");
      return;
    }

    if (typingMessage.isTypingStarted()) {
      Log.d(TAG, "Typing started on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStarted(context,threadId, author, content.getSenderDevice());
    } else {
      Log.d(TAG, "Typing stopped on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, content.getSenderDevice(), false);
    }
  }

  private boolean isInvalidMessage(@NonNull SignalServiceDataMessage message) {
    if (message.getMessageTimerInSeconds() > 0) {
      return !message.getAttachments().isPresent()       ||
              message.getAttachments().get().size() != 1  ||
              !MediaUtil.isImageType(message.getAttachments().get().get(0).getContentType().toLowerCase());

    }

    return false;
  }

  // to be phased out for UfsrvMessagUtils.getValidatedQuote()
  private Optional<QuoteModel> getValidatedQuote(Optional<SignalServiceDataMessage.Quote> quote) {
    if (!quote.isPresent()) return Optional.absent();

    if (quote.get().getId() <= 0) {
      Log.w(TAG, "Received quote without an ID! Ignoring...");
      return Optional.absent();
    }

    if (quote.get().getAuthor() == null) {
      Log.w(TAG, "Received quote without an author! Ignoring...");
      return Optional.absent();
    }

    Address       author  = Address.fromExternal(context, quote.get().getAuthor().getNumber());
    MessageRecord message = DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(quote.get().getId(), author);

    if (message != null) {
      Log.w(TAG, "Found matching message record...");

      List<Attachment> attachments = new LinkedList<>();

      if (message.isMms()) {
        MmsMessageRecord mmsMessage = (MmsMessageRecord) message;
        if (mmsMessage.getRevealDuration() == 0) {
          attachments = mmsMessage.getSlideDeck().asAttachments();

          if (attachments.isEmpty()) {
            attachments.addAll(Stream.of(mmsMessage.getLinkPreviews())
                                       .filter(lp -> lp.getThumbnail().isPresent())
                                       .map(lp -> lp.getThumbnail().get())
                                       .toList());
          }
        } else if (quote.get().getAttachments().size() > 0) {
          attachments.add(new TombstoneAttachment(quote.get().getAttachments().get(0).getContentType(), true));
        }
      }

      return Optional.of(new QuoteModel(quote.get().getId(), author, message.getBody(), false, attachments));
    }

    Log.w(TAG, "Didn't find matching message record...");
    return Optional.of(new QuoteModel(quote.get().getId(),
                                      author,
                                      quote.get().getText(),
                                      true,
                                      PointerAttachment.forPointers(quote.get().getAttachments())));
  }

  // porting note: see corresponding in UfsrvMessageUtils
  private Optional<Attachment> getStickerAttachment(Optional<SignalServiceDataMessage.Sticker> sticker) {
    if (!sticker.isPresent()) {
      return Optional.absent();
    }

    if (sticker.get().getPackId() == null || sticker.get().getPackKey() == null || sticker.get().getAttachment() == null) {
      Log.w(TAG, "Malformed sticker!");
      return Optional.absent();
    }

    String          packId          = Hex.toStringCondensed(sticker.get().getPackId());
    String          packKey         = Hex.toStringCondensed(sticker.get().getPackKey());
    int             stickerId       = sticker.get().getStickerId();
    StickerLocator  stickerLocator  = new StickerLocator(packId, packKey, stickerId);
    StickerDatabase stickerDatabase = DatabaseFactory.getStickerDatabase(context);
    StickerRecord   stickerRecord   = stickerDatabase.getSticker(stickerLocator.getPackId(), stickerLocator.getStickerId(), false);

    if (stickerRecord != null) {
      return Optional.of(new UriAttachment(stickerRecord.getUri(),
                                           stickerRecord.getUri(),
                                           MediaUtil.IMAGE_WEBP,
                                           AttachmentDatabase.TRANSFER_PROGRESS_DONE,
                                           stickerRecord.getSize(),
                                           StickerSlide.WIDTH,
                                           StickerSlide.HEIGHT,
                                           null,
                                           String.valueOf(new SecureRandom().nextLong()),
                                           false,
                                           false,
                                           null,
                                           stickerLocator));
    } else {
      return Optional.of(PointerAttachment.forPointer(Optional.of(sticker.get().getAttachment()), stickerLocator).get());
    }
  }

  // to be phased out for UfsrvMessageUtils.getContacts
  private Optional<List<Contact>> getContacts(Optional<List<SharedContact>> sharedContacts) {
    if (!sharedContacts.isPresent()) return Optional.absent();

    List<Contact> contacts = new ArrayList<>(sharedContacts.get().size());

    for (SharedContact sharedContact : sharedContacts.get()) {
      contacts.add(ContactModelMapper.remoteToLocal(sharedContact));
    }

    return Optional.of(contacts);
  }

  // replaced in UfsrvMessageUtils.getLinkPreviews()
  private Optional<List<LinkPreview>> getLinkPreviews(Optional<List<Preview>> previews, @NonNull String message) {
    if (!previews.isPresent()) return Optional.absent();

    List<LinkPreview> linkPreviews = new ArrayList<>(previews.get().size());

    for (Preview preview : previews.get()) {
      Optional<Attachment> thumbnail     = PointerAttachment.forPointer(preview.getImage());
      Optional<String>     url           = Optional.fromNullable(preview.getUrl());
      Optional<String>     title         = Optional.fromNullable(preview.getTitle());
      boolean              hasContent    = !TextUtils.isEmpty(title.or("")) || thumbnail.isPresent();
      boolean              presentInBody = url.isPresent() && Stream.of(LinkPreviewUtil.findWhitelistedUrls(message)).map(Link::getUrl).collect(Collectors.toSet()).contains(url.get());
      boolean              validDomain   = url.isPresent() && LinkPreviewUtil.isWhitelistedLinkUrl(url.get());

      if (hasContent && presentInBody && validDomain) {
        LinkPreview linkPreview = new LinkPreview(url.get(), title.or(""), thumbnail);
        linkPreviews.add(linkPreview);
      } else {
        Log.w(TAG, String.format("Discarding an invalid link preview. hasContent: %b presentInBody: %b validDomain: %b", hasContent, presentInBody, validDomain));
      }
    }

    return Optional.of(linkPreviews);
  }

  private Optional<InsertResult> insertPlaceholder(@NonNull String sender, int senderDevice, long timestamp) {
    return insertPlaceholder(sender, senderDevice, timestamp, Optional.absent());
  }

  private Optional<InsertResult> insertPlaceholder(@NonNull String sender, int senderDevice, long timestamp, Optional<SignalServiceGroup> group) {
    SmsDatabase         database    = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage textMessage = new IncomingTextMessage(Address.fromExternal(context, sender),
                                                              senderDevice, timestamp, "",
                                                              group, 0, 0,false);

    textMessage = new IncomingEncryptedMessage(textMessage, "");
    return database.insertMessageInbox(textMessage);
  }

  private Recipient getSyncMessageDestination(SentTranscriptMessage message) {
    if (message.getMessage().getGroupInfo().isPresent()) {
      return Recipient.from(context, Address.fromExternal(context, GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false)), false);
    } else {
      return Recipient.from(context, Address.fromExternal(context, message.getDestination().get()), false);
    }
  }

  // PORTING NOTE: THIS IS ASLO COPIED in UfsrvMessageUtils
  private Recipient getMessageDestination(SignalServiceEnvelope envelope, SignalServiceDataMessage message) {
    if (message.getGroupInfo().isPresent()) {
      return Recipient.from(context, Address.fromExternal(context, GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false)), false);
    } else {
      return Recipient.from(context, Address.fromExternal(context, envelope.getSource()), false);
    }
  }

  // also mirrored in UfsrMessageUtils
  private void notifyTypingStoppedFromIncomingMessage(@NonNull Recipient conversationRecipient, @NonNull String sender, int device) {
    Recipient author   = Recipient.from(context, Address.fromExternal(context, sender), false);
    long      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(conversationRecipient);

    if (threadId > 0) {
      Log.d(TAG, "Typing stopped on thread " + threadId + " due to an incoming message.");
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, device, true);
    }
  }

  private boolean shouldIgnore(@NonNull SignalServiceEnvelope envelope, @NonNull SignalServiceContent content) {
    if (content == null) {
      Log.w(TAG, "Got a message with null content.");
      return true;
    }

    Recipient sender = Recipient.from(context, Address.fromExternal(context, envelope.getSource()), false);

    if (content.getDataMessage().isPresent()) {
      SignalServiceDataMessage message      = content.getDataMessage().get();
      Recipient                conversation = getMessageDestination(envelope, message);

      if (conversation.isGroupRecipient() && conversation.isBlocked()) {
        return true;
      } else if (conversation.isGroupRecipient()) {
        GroupDatabase    groupDatabase = DatabaseFactory.getGroupDatabase(context);
        Optional<String> groupId       = message.getGroupInfo().isPresent() ? Optional.of(GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false))

                                                                            : Optional.absent();

        if (groupId.isPresent() && groupDatabase.isUnknownGroup(groupId.get())) {
          return false;
        }

        boolean isTextMessage    = message.getBody().isPresent();
        boolean isMediaMessage   = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent();
        boolean isExpireMessage  = message.isExpirationUpdate();
        boolean isContentMessage = !message.isGroupUpdate() && (isTextMessage || isMediaMessage || isExpireMessage);
        boolean isGroupActive    = groupId.isPresent() && groupDatabase.isActive(groupId.get());
        boolean isLeaveMessage   = message.getGroupInfo().isPresent() && message.getGroupInfo().get().getType() == SignalServiceGroup.Type.QUIT;

        return (isContentMessage && !isGroupActive) || (sender.isBlocked() && !isLeaveMessage);
      } else {
        return sender.isBlocked();
      }
    } else if (content.getCallMessage().isPresent() || content.getTypingMessage().isPresent()) {
      return sender.isBlocked();
    }

    return false;
  }

  private void resetRecipientToPush(@NonNull Recipient recipient) {
    if (recipient.isForceSmsSelection()) {
      DatabaseFactory.getRecipientDatabase(context).setForceSmsSelection(recipient, false);
    }
  }

  // porting note: see corresponding in UfsrvMessageUtils.java
  private void forceStickerDownloadIfNecessary(List<DatabaseAttachment> stickerAttachments) {
    if (stickerAttachments.isEmpty()) return;

    DatabaseAttachment stickerAttachment = stickerAttachments.get(0);

    if (stickerAttachment.getTransferState() != AttachmentDatabase.TRANSFER_PROGRESS_DONE) {
      AttachmentDownloadJob downloadJob = new AttachmentDownloadJob(messageId, stickerAttachment.getAttachmentId(), true);

      try {
        downloadJob.setContext(context);
        downloadJob.doWork();
      } catch (Exception e) {
        Log.w(TAG, "Failed to download sticker inline. Scheduling.");
        ApplicationContext.getInstance(context).getJobManager().add(downloadJob);
      }
    }
  }

  @SuppressWarnings("WeakerAccess")
  private static class StorageFailedException extends Exception {
    private final String sender;
    private final int senderDevice;

    private StorageFailedException(Exception e, String sender, int senderDevice) {
      super(e);
      this.sender       = sender;
      this.senderDevice = senderDevice;
    }

    public String getSender() {
      return sender;
    }

    public int getSenderDevice() {
      return senderDevice;
    }
  }

  public static final class Factory implements Job.Factory<PushDecryptJob> {
    @Override
    public @NonNull PushDecryptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new PushDecryptJob(parameters, data.getLong(KEY_MESSAGE_ID), data.getLong(KEY_SMS_MESSAGE_ID));
    }
  }
}

