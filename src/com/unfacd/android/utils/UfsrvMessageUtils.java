package com.unfacd.android.utils;

import com.unfacd.android.ApplicationContext;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;
import android.util.Pair;

import com.annimon.stream.Stream;
import com.annimon.stream.Collectors;

import org.thoughtcrime.securesms.attachments.TombstoneAttachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.crypto.storage.SignalProtocolStoreImpl;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.StickerDatabase;
import org.thoughtcrime.securesms.database.model.StickerRecord;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil;
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.jobs.ProfileAvatarDownloadJob;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;

import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.attachments.PointerAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactModelMapper;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.MessagingDatabase;
import org.thoughtcrime.securesms.database.MmsDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob;
import org.thoughtcrime.securesms.linkpreview.Link;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.StickerSlide;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.sms.IncomingPreKeyBundleMessage;
import org.thoughtcrime.securesms.stickers.StickerLocator;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.Hex;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.whispersystems.libsignal.DuplicateMessageException;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.InvalidMessageException;
import org.whispersystems.libsignal.InvalidVersionException;
import org.whispersystems.libsignal.LegacyMessageException;
import org.whispersystems.libsignal.NoSessionException;
import org.whispersystems.libsignal.SessionCipher;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.protocol.PreKeySignalMessage;
import org.whispersystems.libsignal.protocol.SignalMessage;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.MessagingDatabase.InsertResult;
import org.thoughtcrime.securesms.notifications.MessageNotifier;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.PushTransportDetails;

import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;


public class UfsrvMessageUtils
{
  private static final String TAG = UfsrvMessageUtils.class.getSimpleName();

  public static @Nullable
  Long processUfsrvMessageCommand(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Long messageId,
                                @NonNull Optional<Long> smsMessageId,
                                boolean outgoing)
          throws MmsException
  {
    MessageCommand msgCommand = message.getUfsrvCommand().getMsgCommand();
    if (msgCommand == null) {
      Log.e(TAG, String.format("processUfsrvMessageCommand (%d): MessageCommand was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    switch (msgCommand.getHeader().getCommand())
    {
      case MessageCommand.CommandTypes.SAY_VALUE:
        if (msgCommand.getAttachmentsCount() > 0 || message.getQuote().isPresent() || message.getSharedContacts().isPresent() || message.getPreviews().isPresent()) {
          return processMessageCommandSayWithMedia(context, envelope, message, messageId, smsMessageId, outgoing);
        } else  {
            return processMessageCommandSay(context, envelope, message, smsMessageId, outgoing);
          }

      case MessageCommand.CommandTypes.INTRO_VALUE:
        return processMessageCommandIntro(context, envelope, message, smsMessageId, outgoing);

      default:
        Log.e(TAG, String.format("processUfsrvMessageCommand (eid:'%d', type:'%d'): Received UKNOWN MESSAGE COMMAND", msgCommand.getHeader().getEid(), msgCommand.getHeader().getCommand()));
    }

    return Long.valueOf(-1);
  }

  //based off PushDecryptJob.handleTextMessage
  private static @Nullable
  Long processMessageCommandSay(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Optional<Long> smsMessageId,
                                boolean outgoing)

  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    MessageCommand msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (msgCommand.getFencesCount() <= 0) {
      Log.e(TAG, String.format("processUfsrvMessageCommand (%d): Received no fence record information: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    FenceRecord fenceRecord=msgCommand.getFences(0);
    String body;
    SmsDatabase database   = DatabaseFactory.getSmsDatabase(context);
    if (envelope.getType()== SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE) {
      body = msgCommand.getMessagesCount() > 0 ? msgCommand.getMessages(0).getMessage().toStringUtf8() : "";
    } else {
      byte [] bodyB64 = org.whispersystems.signalservice.internal.util.Base64.decode(msgCommand.getMessages(0).getMessage().toStringUtf8().getBytes(Charset.forName("UTF-8")));
      byte[] bodyBytes = decrypt(context, envelope, UfsrvUidEncodedForOriginator(msgCommand), bodyB64, smsMessageId);
      if (bodyBytes!=null) {
        body = new String(bodyBytes);
      } else {
        return Long.valueOf(-1);
      }
    }
    Recipient            recipientGroup  = groupDatabase.getGroupRecipient(fenceRecord.getFid());//we retrieve

    if (recipientGroup==null) {
      Log.e(TAG, String.format("processMessageCommandSay (fid:'%d'): COULD NOT PROCESS MSG: recipient is NULL", fenceRecord.getFid()));

      return Long.valueOf(-1);
    }

    notifyTypingStoppedFromIncomingMessage(context, recipientGroup, UfsrvUidEncodedForOriginator(envelope.getMessageCommand()), envelope.getSourceDevice());

    Optional<QuoteModel> quote        = getValidatedQuote(context, message.getQuote());

    //TODO: this needs to be updated to read off expiration updates from ufsrv command messages
    if (message.getExpiresInSeconds() != recipientGroup.getExpireMessages()) {
      //handleExpirationUpdate(masterSecret, envelope, message, Optional.<Long>absent());
    }

    Pair<Long, Long> messageAndThreadIdPair   = null;
    Optional<InsertResult> messageAndThreadId = null;

    if (smsMessageId.isPresent() && !message.getGroupInfo().isPresent()) {
      messageAndThreadIdPair = database.updateBundleMessageBody(smsMessageId.get(), body);
      if (messageAndThreadIdPair!=null) {
        //TODO: this is a temporary hack
        messageAndThreadId=Optional.of(new MessagingDatabase.InsertResult(messageAndThreadIdPair.first, messageAndThreadIdPair.second));
      }
    } else {
      IncomingTextMessage textMessage = new IncomingTextMessage(Address.fromSerialized(UfsrvUidEncodedForOriginator(envelope.getMessageCommand())),
                                                                envelope.getSourceDevice(),
                                                                msgCommand.getHeader().getWhenClient(),
                                                                body,
                                                                message.getGroupInfo(),
              /*fenceRecord.getExpireTimer(),*/message.getExpiresInSeconds() * 1000, //set in getExpireTimerIfSet in createSignalServiceMessage()
                                                                message.getMessageTimerInSeconds() * 1000L,
                                                                false,
                                                                envelope.getUfsrvCommand());

      textMessage = new IncomingEncryptedMessage(textMessage, body);
      messageAndThreadId = database.insertMessageInbox(textMessage);

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());
    }

    if (msgCommand.getHeader().getEid()>0)  {
      threadDatabase.updateEidByFid(fenceRecord.getFid(), msgCommand.getHeader().getEid());
      DatabaseFactory.getRecipientDatabase(context).setEid(recipientGroup, fenceRecord.getEid());
    }

    if (messageAndThreadId.isPresent()) {
      MessageNotifier.updateNotification(context, messageAndThreadId.get().getThreadId());

      return messageAndThreadId.get().getMessageId();
    }

    Log.e(TAG, String.format("processMessageCommandSay: COULD NOT PROCESS MSG"));

    return Long.valueOf(-1);
  }

  //based on PushDecryptJob.handleMediaMessage()
  //todo port as in https://github.com/signalapp/Signal-Android/commit/5f31762220fa5ca4a00138e74500bbe4b5232578
  private static @Nullable
  Long processMessageCommandSayWithMedia(@NonNull Context context,
                                @NonNull SignalServiceEnvelope envelope,
                                @NonNull SignalServiceDataMessage message,
                                @NonNull Long messageId,
                                @NonNull Optional<Long> smsMessageId,
                                boolean outgoing) throws MmsException
  {
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    MessageCommand msgCommand = message.getUfsrvCommand().getMsgCommand();
    if (msgCommand.getFencesCount() <= 0) {
      Log.e(TAG, String.format("processUfsrvMessageCommand (%d): Received no fence record information: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    FenceRecord fenceRecord = msgCommand.getFences(0);

    String body;
    if (envelope.getType() == SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE) {
      body = msgCommand.getMessagesCount() > 0 ? msgCommand.getMessages(0).getMessage().toStringUtf8() : "";
    } else {
      byte [] bodyB64 = org.whispersystems.signalservice.internal.util.Base64.decode(msgCommand.getMessages(0).getMessage().toStringUtf8().getBytes(Charset.forName("UTF-8")));
      byte[] bodyBytes = decrypt(context, envelope, UfsrvUidEncodedForOriginator(msgCommand), bodyB64, smsMessageId);
      if (bodyBytes!=null) {
        body = new String(bodyBytes);
      } else {
        return Long.valueOf(-1);
      }
    }

    Optional<QuoteModel> quote                 = getValidatedQuote(context, message.getQuote());
    Optional<List<Contact>> sharedContacts     = getContacts(message.getSharedContacts());
    Optional<List<LinkPreview>> linkPreviews   = getLinkPreviews(message.getPreviews(), body/*message.getBody().or("")*/); // body
    Optional<Attachment>        sticker        = getStickerAttachment(context, message.getSticker());
    Recipient            recipientGroup        = groupDatabase.getGroupRecipient(fenceRecord.getFid());


    if (recipientGroup == null) {
      Log.e(TAG, String.format("processMessageCommandSayWithMedia (fid:'%d'): COULD NOT PROCESS MSG: recipient is NULL", fenceRecord.getFid()));

      return Long.valueOf(-1);
    }

    notifyTypingStoppedFromIncomingMessage(context, recipientGroup, UfsrvUidEncodedForOriginator(envelope.getMessageCommand()), envelope.getSourceDevice());

    Optional<InsertResult> insertResult;

    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    database.beginTransaction();

    try {
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(Address.fromSerialized(UfsrvUidEncodedForOriginator(envelope.getMessageCommand())),
                                                                   msgCommand.getHeader().getWhenClient(),
                                                                   -1,
                                                                   message.getExpiresInSeconds() * 1000,
                                                                   false,
                                                                   message.getMessageTimerInSeconds() * 1000,
                                                                   false,
                                                                   Optional.of(body)/*message.getBody()*/,
                                                                   message.getGroupInfo(),
                                                                   Optional.of(UfsrvCommandUtils.getAttachmentsList(envelope.getUfsrvCommand())),
                                                                   quote,
                                                                   sharedContacts,
                                                                   linkPreviews,
                                                                   sticker,
                                                                   envelope.getUfsrvCommand());


      //TODO: this needs to be updated to read off expiration updates from ufsrv command messages
      if (message.getExpiresInSeconds() != recipientGroup.getExpireMessages()) {
        //handleExpirationUpdate(masterSecret, envelope, message, Optional.<Long>absent());
      }

      insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, threadDatabase.getThreadIdFor(null, fenceRecord.getFid())); //-1);

      if (insertResult.isPresent()) {
//      List<DatabaseAttachment> attachments = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());
        List<DatabaseAttachment> allAttachments = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());
        List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
        List<DatabaseAttachment> attachments = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

        forceStickerDownloadIfNecessary(context, messageId, stickerAttachments);

        for (DatabaseAttachment attachment : attachments) {
          ApplicationContext.getInstance(context)
                  .getJobManager()
                  .add(new AttachmentDownloadJob(insertResult.get().getMessageId(),
                                                 attachment.getAttachmentId(), false));
        }

        if (smsMessageId.isPresent()) {
          DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
        }

        if (msgCommand.getHeader().getEid() > 0) {
          threadDatabase.updateEidByFid(fenceRecord.getFid(), msgCommand.getHeader().getEid());
          DatabaseFactory.getRecipientDatabase(context).setEid(recipientGroup, fenceRecord.getEid());
        }

        database.setTransactionSuccessful();
      }
    } finally {
      database.endTransaction();
    }

    if (insertResult.isPresent()) {
      MessageNotifier.updateNotification(context, insertResult.get().getThreadId());

      if (message.getMessageTimerInSeconds() > 0) {
        ApplicationContext.getInstance(context).getRevealableMessageManager().scheduleIfNecessary();
      }

      return insertResult.get().getMessageId();
    }

    Log.e(TAG, String.format("processMessageCommandSayWithMedia: COULD NOT PROCESS MSG"));

    return null;
  }

  // porting note: see corresponding in PushDecryptJob
  static private Optional<Attachment> getStickerAttachment(Context context, Optional<SignalServiceDataMessage.Sticker> sticker) {
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
    StickerLocator stickerLocator  = new StickerLocator(packId, packKey, stickerId);
    StickerDatabase stickerDatabase = DatabaseFactory.getStickerDatabase(context);
    StickerRecord stickerRecord   = stickerDatabase.getSticker(stickerLocator.getPackId(), stickerLocator.getStickerId(), false);

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

  static private void forceStickerDownloadIfNecessary(Context context, @NonNull Long messageId, List<DatabaseAttachment> stickerAttachments) {
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

  private static @Nullable
  Long processMessageCommandIntro(@NonNull Context context,
                                         @NonNull SignalServiceEnvelope envelope,
                                         @NonNull SignalServiceDataMessage message,
                                         @NonNull Optional<Long> smsMessageId,
                                         boolean outgoing)
  {
    MessageCommand     msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (isCommandAccepted(msgCommand)) {
      Log.i(TAG, String.format("(eid:'%d', target:'%s') processMessageCommandIntro: INTRO MSG ACCEPTED BY SERVER", msgCommand.getHeader().getEid(), UfsrvUid.EncodedfromSerialisedBytes(msgCommand.getTo(0).getUfsrvuid().toByteArray())));
      DatabaseFactory.getRecipientDatabase(context).setEid(UfsrvUserUtils.myOwnRecipient(false), msgCommand.getHeader().getEid());
      //todo delete avatar blob
      Optional<Pair<Long, IntroContactDescriptor>> descriptor = DatabaseFactory.getUnfacdIntroContactsDatabase(context).getIntroContactBySender(msgCommand.getHeader().getWhenClient());
      if (descriptor.isPresent()) {
        DatabaseFactory.getUnfacdIntroContactsDatabase(context).setResponseStatus(descriptor.get().first, IntroContactDescriptor.ResponseStatus.SENT);
      }

      return Long.valueOf(-1);
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("(eid:'%d', target:'%s') processMessageCommandIntro: INTRO MSG REJECTED BY SERVER", msgCommand.getHeader().getEid(), UfsrvUid.EncodedfromSerialisedBytes(msgCommand.getTo(0).getUfsrvuid().toByteArray())));
      return Long.valueOf(-1);
    } else if (isCommandSydnced(msgCommand)) {
      String avatarId = null;
      String UfsrvUidOriginator = UfsrvUid.EncodedfromSerialisedBytes(msgCommand.getOriginator().getUfsrvuid().toByteArray());

      if (msgCommand.getOriginator().hasAvatar() && !TextUtils.isEmpty(msgCommand.getOriginator().getAvatar().getId())) {
        ApplicationContext.getInstance(context)
                .getJobManager()
                .add(new ProfileAvatarDownloadJob(UfsrvUidOriginator, msgCommand.getOriginator().getAvatar(), msgCommand.getOriginator().getAvatar().getId()));

        avatarId = msgCommand.getOriginator().getAvatar().getId();
      }

      String msg = msgCommand.getMessagesCount() > 0? msgCommand.getMessages(0).toString():"";
      IntroContactDescriptor descriptor = new IntroContactDescriptor(recipientFromMessageCommandOriginator(msgCommand, false).getAddress(),
                                                                     msg, avatarId, IntroContactDescriptor.IntroDirection.INCOMING, msgCommand.getHeader().getWhenClient());
      long introId = DatabaseFactory.getUnfacdIntroContactsDatabase(context).insertIntroContact(descriptor);
      DatabaseFactory.getUnfacdIntroContactsDatabase(context).setResponseStatus(introId, IntroContactDescriptor.ResponseStatus.UNSEEN);

      MessageNotifier.notifyIntroMessage(context, introId);

//      IntroContactNotificationBuilder.getIntroContactNotification(context, 0, recipientFromMessageCommandOriginator(msgCommand, false), introId);
      /*if (ApplicationContext.getInstance().isAppVisible()) {

        ConversationListActivity.launchMeWithIntroContactDescriptor(ApplicationContext.getInstance(), introId);
      } else {
        MessageNotifier.notifyIntroMessage(context, recipientFromMessageCommandOriginator(msgCommand, false), introId);
      }*/

      return Long.valueOf(-1);
    }

    /*GroupDatabase groupDatabase   = DatabaseFactory.getGroupDatabase(context);
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);

    MessageCommand msgCommand = message.getUfsrvCommand().getMsgCommand();
    FenceRecord fenceRecord = msgCommand.getFences(0);

    String body;
    if (envelope.getType()== SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE) {
      body = msgCommand.getMessagesCount() > 0 ? msgCommand.getMessages(0).getMessage().toStringUtf8() : "";
    } else {
      byte [] bodyB64 = org.whispersystems.signalservice.internal.util.Base64.decode(msgCommand.getMessages(0).getMessage().toStringUtf8().getBytes(Charset.forName("UTF-8")));
      byte[] bodyBytes = decrypt(context, envelope, UfsrvUidEncodedForOriginator(msgCommand), bodyB64, smsMessageId);
      if (bodyBytes!=null) {
        body = new String(bodyBytes);
      } else {
        return Long.valueOf(-1);
      }
    }

    Optional<QuoteModel> quote        = getValidatedQuote(context, message.getQuote());
    Optional<List<Contact>> sharedContacts = getContacts(message.getSharedContacts());
    Optional<List<LinkPreview>> linkPreviews   = getLinkPreviews(message.getPreviews(), body*//*message.getBody().or("")*//*); // body
    Recipient            recipientGroup  = groupDatabase.getGroupRecipient(fenceRecord.getFid());

    if (recipientGroup==null) {
      Log.e(TAG, String.format("processMessageCommandSayWithMedia (fid:'%d'): COULD NOT PROCESS MSG: recipient is NULL", fenceRecord.getFid()));

      return Long.valueOf(-1);
    }

    notifyTypingStoppedFromIncomingMessage(context, recipientGroup, UfsrvUidEncodedForOriginator(envelope.getMessageCommand()), envelope.getSourceDevice());

    MmsDatabase database     = DatabaseFactory.getMmsDatabase(context);
    IncomingMediaMessage mediaMessage = new IncomingMediaMessage(Address.fromSerialized(UfsrvUidEncodedForOriginator(envelope.getMessageCommand())),
                                                                 msgCommand.getHeader().getWhenClient(),
                                                                 -1,
                                                                 message.getExpiresInSeconds() * 1000,
                                                                 false,
                                                                 false,
                                                                 Optional.of(body)*//*message.getBody()*//*,
                                                                 message.getGroupInfo(),
                                                                 Optional.of(UfsrvCommandUtils.getAttachmentsList(envelope.getUfsrvCommand())),
                                                                 quote,
                                                                 sharedContacts,
                                                                 linkPreviews,
                                                                 envelope.getUfsrvCommand());


    //TODO: this needs to be updated to read off expiration updates from ufsrv command messages
    if (message.getExpiresInSeconds() != recipientGroup.getExpireMessages()) {
      //handleExpirationUpdate(masterSecret, envelope, message, Optional.<Long>absent());
    }

    Optional<InsertResult> messageAndThreadId = database.insertSecureDecryptedMessageInbox(mediaMessage, threadDatabase.getThreadIdFor(null, fenceRecord.getFid())); //-1);
    if (messageAndThreadId.isPresent()) {
      List<DatabaseAttachment> attachments = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(messageAndThreadId.get().getMessageId()*//*first*//*);

      for (DatabaseAttachment attachment : attachments) {
        ApplicationContext.getInstance(context)
                .getJobManager()
                .add(new AttachmentDownloadJob(messageAndThreadId.get().getMessageId()*//*messageAndThreadId.first*//*,
                                               attachment.getAttachmentId(), false));
      }

      if (smsMessageId.isPresent()) {
        DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
      }

      if (msgCommand.getHeader().getEid() > 0) {
        threadDatabase.updateEidByFid(fenceRecord.getFid(), msgCommand.getHeader().getEid());
        DatabaseFactory.getRecipientDatabase(context).setEid(recipientGroup, fenceRecord.getEid());
      }

      MessageNotifier.updateNotification(context, messageAndThreadId.get().getThreadId()*//*messageAndThreadId.second*//*);

      return messageAndThreadId.get().getMessageId();//messageAndThreadId.first;
    }
*/
    Log.e(TAG, String.format("(args:'%d') processMessageCommandIntro: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  // based off PushDecryptJob.notifyTypi...
  static private void notifyTypingStoppedFromIncomingMessage(@NonNull Context context, @NonNull Recipient conversationRecipient, @NonNull String sender, int device) {
    Recipient author   = Recipient.from(context, Address.fromExternal(context, sender), false);
    long      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(conversationRecipient);

    if (threadId > 0) {
      Log.d(TAG, "Typing stopped on thread " + threadId + " due to an incoming message.");
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, device, true);
    }
  }

  static public Optional<UfsrvCommandWire> inflateEncodedUfrsvCommand (@NonNull String ufsrvCommandEncoded)
  {
    UfsrvCommandWire ufsrvCommand=null;

    if (ufsrvCommandEncoded!=null) {
      try {
        ufsrvCommand = UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
        return Optional.fromNullable(ufsrvCommand);
      } catch (IOException e) {
        Log.d(TAG, e.getMessage());
        return Optional.absent();
      }
    }

    return Optional.absent();
  }

  static public UfsrvMessageIdentifier UfsrvMessageIdentifierFromEncoded (@NonNull String ufsrvCommandEncoded, long timestamp)
  {
    Optional<UfsrvCommandWire> ufrsvCommand = inflateEncodedUfrsvCommand(ufsrvCommandEncoded);
    if (ufrsvCommand.isPresent()) {
      switch (ufrsvCommand.get().getUfsrvtype()) {
        case UFSRV_FENCE:
          return new UfsrvMessageUtils.UfsrvMessageIdentifier(UfsrvUid.EncodedfromSerialisedBytes(ufrsvCommand.get().getFenceCommand().getOriginator().getUfsrvuid().toByteArray()),
                                            ufrsvCommand.get().getFenceCommand().getFences(0).getFid(),
                                            ufrsvCommand.get().getHeader().getEid(),
                                                              timestamp);

        case UFSRV_MESSAGE:
          return new UfsrvMessageUtils.UfsrvMessageIdentifier(UfsrvUidEncodedForOriginator(ufrsvCommand.get().getMsgCommand()),
                                                              ufrsvCommand.get().getMsgCommand().getFences(0).getFid(),
                                                              ufrsvCommand.get().getHeader().getEid(), timestamp);

      }
    }

    return new UfsrvMessageUtils.UfsrvMessageIdentifier(UfsrvUid.UndefinedUfsrvUid,0,0,0);

  }

  static public List<UfsrvMessageIdentifier> UfsrvMessageIdentifierFromReceiptCommand (@NonNull ReceiptCommand receiptCommand)
  {
    com.google.common.base.Preconditions.checkArgument(receiptCommand.getEidCount() == receiptCommand.getTimestampCount());

    Iterator eids       = receiptCommand.getEidList().iterator();
    Iterator timestamps = receiptCommand.getTimestampList().iterator();

    List<UfsrvMessageIdentifier> ufsrvMessageIdentifiers = new LinkedList<>();

    while(eids.hasNext() && timestamps.hasNext()) {
      ufsrvMessageIdentifiers.add(new UfsrvMessageIdentifier(UfsrvUid.EncodedfromSerialisedBytes(receiptCommand.getUidOriginator().toByteArray()),
                                                             receiptCommand.getFid(),
                                                             (Long)eids.next(),
                                                             (Long)timestamps.next()));
    }

    return ufsrvMessageIdentifiers;

  }

  static public class UfsrvMessageIdentifier implements Serializable
  {
    public final String uidOriginator;
    public final long   fid;
    public final long   eid;
    public long         timestamp;//of time sent by originator also used as 'message id'

    public UfsrvMessageIdentifier (String uidOriginator, long fid, long eid) {
      this.uidOriginator  = uidOriginator;
      this.eid            = eid;
      this.fid            = fid;
      this.timestamp      = 0;
    }

    public UfsrvMessageIdentifier (String uidOriginator, long fid, long eid, long timestamp) {
      this.uidOriginator  = uidOriginator;
      this.eid            = eid;
      this.fid            = fid;
      this.timestamp      = timestamp;
    }

    public UfsrvMessageIdentifier (String tokenized)
    {
      if (TextUtils.isEmpty(tokenized)) {
        this.uidOriginator  = "0";
        this.eid            = 0;
        this.fid            = 0;
        this.timestamp      = 0;
      } else {
        if(tokenized.contains(":")){
          String[] output = tokenized.split(":");
          if(output.length!=4){
            throw new IllegalArgumentException(tokenized + " - invalid format!");
          }else{
            this.uidOriginator  = (output[0]);
            this.eid            = Long.valueOf(output[1]);
            this.fid            = Long.valueOf(output[2]);
            this.timestamp      = Long.valueOf(output[3]);
          }
        }else{
          throw new IllegalArgumentException(tokenized + " - invalid format!");
        }
      }

    }

    public void setTimestamp (long timestamp)
    {
      this.timestamp = timestamp;
    }

    public String toString () {
      return new String(uidOriginator+":"+eid+":"+fid+":"+timestamp);
    }
  }

  static public List<SignalServiceProtos.AttachmentRecord> adaptAttachmentRecords (List<SignalServiceProtos.AttachmentPointer> attachmentPointers)
  {
    List<SignalServiceProtos.AttachmentRecord> attachmentRecords=new LinkedList<>();

    for (SignalServiceProtos.AttachmentPointer pointer : attachmentPointers) {
      SignalServiceProtos.AttachmentRecord.Builder attachmentRecord=SignalServiceProtos.AttachmentRecord.newBuilder();
      attachmentRecord.setContentType(pointer.getContentType());
      attachmentRecord.setId(pointer.getUfid());
      attachmentRecord.setKey(pointer.getKey());
      attachmentRecord.setDigest(pointer.getDigest());
      attachmentRecord.setSize(pointer.getSize());
      attachmentRecord.setWidth(pointer.getWidth());
      attachmentRecord.setHeight(pointer.getHeight());
      attachmentRecord.setThumbnail(pointer.getThumbnail());
      attachmentRecords.add(attachmentRecord.build());
    }

    return attachmentRecords;
  }

  // replaces getValidateQuote in PushDecryptJob
  private static Optional<QuoteModel> getValidatedQuote(Context context, Optional<SignalServiceDataMessage.Quote> quote) {
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

  // replaces getContacts(Optional<List<SharedContact>> sharedContacts) in PushDecryptJob.java
  private static Optional<List<Contact>> getContacts(Optional<List<SharedContact>> sharedContacts) {
    if (!sharedContacts.isPresent()) return Optional.absent();

    List<Contact> contacts = new ArrayList<>(sharedContacts.get().size());

    for (SharedContact sharedContact : sharedContacts.get()) {
      contacts.add(ContactModelMapper.remoteToLocal(sharedContact));
    }

    return Optional.of(contacts);
  }

  // replaces PushDecryptJob.getLinkPreview()
  private static Optional<List<LinkPreview>> getLinkPreviews(Optional<List<SignalServiceDataMessage.Preview>> previews, @NonNull String message) {
    if (!previews.isPresent()) return Optional.absent();

    List<LinkPreview> linkPreviews = new ArrayList<>(previews.get().size());

    for (SignalServiceDataMessage.Preview preview : previews.get()) {
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

  //start re-adapted e2ee based off SignalServiceCipher.decrypt
  private static byte[] decrypt(Context context, SignalServiceEnvelope envelope, String source, byte[] ciphertext, @NonNull Optional<Long> smsMessageId)
  {
    SignalProtocolStore signalProtocolStore  = new SignalProtocolStoreImpl(context);

    //todo: SignalServiceAddress.DEFAULT_DEVICE_ID limits support to one device: record device id as provided by client
    SignalProtocolAddress sourceAddress = new SignalProtocolAddress(/*envelope.getSource()*/source, /*envelope.getSourceDevice()*/SignalServiceAddress.DEFAULT_DEVICE_ID);
    SessionCipher sessionCipher = new SessionCipher(signalProtocolStore, sourceAddress);

    byte[] paddedMessage;
    try {
      if (envelope.isPreKeySignalMessage()) {
        Log.d(TAG, ">>decrypt: decrypting a PreKeySignalMessage");
        paddedMessage = sessionCipher.decrypt(new PreKeySignalMessage(ciphertext));
      }
      else if (envelope.isSignalMessage()) {
        Log.d(TAG, ">>decrypt: decrypting a SignalMessage");
        paddedMessage = sessionCipher.decrypt(new SignalMessage(ciphertext));
      }
      else {
        throw new InvalidMessageException("Unknown type: " + envelope.getType());
      }

      PushTransportDetails transportDetails = new PushTransportDetails(sessionCipher.getSessionVersion());
      return transportDetails.getStrippedPaddingMessageBody(paddedMessage);
    }  catch (InvalidVersionException e) {
      Log.w(TAG, e);
      handleInvalidVersionMessage(context, envelope, smsMessageId);
    } catch (InvalidMessageException | InvalidKeyIdException | InvalidKeyException /*| MmsException*/ e) {
      Log.w(TAG, e);
      handleCorruptMessage(context, envelope, smsMessageId);
    } catch (NoSessionException e) {
      Log.w(TAG, e);
      handleNoSessionMessage(context, envelope, smsMessageId);
    } catch (LegacyMessageException e) {
      Log.w(TAG, e);
      handleLegacyMessage(context, envelope, smsMessageId);
    } catch (DuplicateMessageException e) {
      Log.w(TAG, e);
      handleDuplicateMessage(context, envelope, smsMessageId);
    } catch (UntrustedIdentityException e) {
      Log.w(TAG, e);
      handleUntrustedIdentityMessage(context, envelope, smsMessageId);
    }

    return null;
  }

  static private void handleInvalidVersionMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidVersionKeyExchange(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsInvalidVersionKeyExchange(smsMessageId.get());
    }
  }

  static private void handleCorruptMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsDecryptFailed(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsDecryptFailed(smsMessageId.get());
    }
  }

  static private void handleNoSessionMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsNoSession(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  static private void handleLegacyMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsLegacyVersion(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsLegacyVersion(smsMessageId.get());
    }
  }

  static private void handleDuplicateMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
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

  static private void handleUntrustedIdentityMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    try {
      SmsDatabase         database          = DatabaseFactory.getSmsDatabase(context);
      Address               sourceAddress   = Address.fromExternal(context, envelope.getSource());
      byte[]                serialized      = envelope.hasLegacyMessage() ? envelope.getLegacyMessage() : envelope.getContent();
      PreKeySignalMessage   whisperMessage  = new PreKeySignalMessage(serialized);
      IdentityKey identityKey               = whisperMessage.getIdentityKey();
      String                encoded         = Base64.encodeBytes(serialized);

      IncomingTextMessage   textMessage     = new IncomingTextMessage(sourceAddress,
                                                                     envelope.getSourceDevice(),
                                                                     envelope.getTimestamp(), encoded,
                                                                     Optional.absent(), 0, 0,false,
                                                                     envelope.getUfsrvCommand());

      if (!smsMessageId.isPresent()) {
        IncomingPreKeyBundleMessage bundleMessage = new IncomingPreKeyBundleMessage(textMessage, encoded, envelope.hasLegacyMessage());
        Optional<InsertResult>      insertResult  = database.insertMessageInbox(bundleMessage);

        if (insertResult.isPresent()) {
          database.setMismatchedIdentity(insertResult.get().getMessageId(), sourceAddress, identityKey);
          MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
        }
      } else {
        database.updateMessageBody(smsMessageId.get(), encoded);
        database.markAsPreKeyBundle(smsMessageId.get());
        database.setMismatchedIdentity(smsMessageId.get(), sourceAddress, identityKey);
      }
    } catch (InvalidMessageException | InvalidVersionException e) {
      throw new AssertionError(e);
    }
  }

  static private Optional<InsertResult> insertPlaceholder(@NonNull Context context, @NonNull SignalServiceEnvelope envelope) {
    SmsDatabase         database    = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage   textMessage = new IncomingTextMessage(Address.fromExternal(context, envelope.getSource()),
                                                                envelope.getSourceDevice(),
                                                                envelope.getTimestamp(), "",
                                                                Optional.absent(), 0, 0,false,
                                                                envelope.getUfsrvCommand());

    textMessage = new IncomingEncryptedMessage(textMessage, "");
    return database.insertMessageInbox(textMessage);
  }
  // end re-adapted e2ee

  static public Recipient recipientFromMessageCommandOriginator (SignalServiceProtos.MessageCommand messageCommand, boolean async)
  {
    return Recipient.fromUfsrvUid(ApplicationContext.getInstance(), new UfsrvUid(messageCommand.getOriginator().getUfsrvuid().toByteArray()), async);
  }

  static public String UfsrvUidEncodedForOriginator (MessageCommand messageCommand)
  {
    return UfsrvUid.EncodedfromSerialisedBytes(messageCommand.getOriginator().getUfsrvuid().toByteArray());
  }

  static public boolean isCommandAccepted (MessageCommand msgCommand)
  {
    return (msgCommand.getHeader().getArgs() == SignalServiceProtos.CommandArgs.ACCEPTED.getNumber());
  }

  static public boolean isCommandRejected (MessageCommand msgCommand)
  {
    return (msgCommand.getHeader().getArgs() == SignalServiceProtos.CommandArgs.REJECTED.getNumber());
  }

  static public boolean isCommandSydnced (MessageCommand msgCommand)
  {
    return (msgCommand.getHeader().getArgs() == SignalServiceProtos.CommandArgs.SYNCED.getNumber());
  }
}
