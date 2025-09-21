package com.unfacd.android.utils;

import android.content.Context;
import android.os.Build;
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;
import com.unfacd.android.ApplicationContext;
import com.unfacd.android.guardian.GuardianDescriptor;
import com.unfacd.android.jobs.IntroMessageAvatarDownloadJob;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.ufsrvcmd.events.AppEventGuardianCommand;
import com.unfacd.android.ufsrvcmd.events.AppEventMessageEffectsCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.ui.components.intro_contact.AppEventIntroContact;
import com.unfacd.android.ui.components.intro_contact.IntroContactDescriptor;
import com.unfacd.android.ui.components.intro_contact.IntroDirection;
import com.unfacd.android.ui.components.intro_contact.ResponseStatus;

import org.signal.core.util.Hex;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.protocol.DuplicateMessageException;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.InvalidMessageException;
import org.signal.libsignal.protocol.InvalidVersionException;
import org.signal.libsignal.protocol.LegacyMessageException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.UntrustedIdentityException;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.util.Pair;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.attachments.PointerAttachment;
import org.thoughtcrime.securesms.attachments.TombstoneAttachment;
import org.thoughtcrime.securesms.attachments.UriAttachment;
import org.thoughtcrime.securesms.components.emoji.EmojiUtil;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.contactshare.ContactModelMapper;
import org.thoughtcrime.securesms.crypto.KeyStoreHelper;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase.InsertResult;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.StickerDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.UnfacdIntroContactsDatabase;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.MmsMessageRecord;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.database.model.StickerRecord;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.BadGroupIdException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob;
import org.thoughtcrime.securesms.jobs.TrimThreadJob;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.linkpreview.LinkPreviewUtil;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.mms.StickerSlide;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.sms.IncomingEncryptedMessage;
import org.thoughtcrime.securesms.sms.IncomingPreKeyBundleMessage;
import org.thoughtcrime.securesms.sms.IncomingTextMessage;
import org.thoughtcrime.securesms.stickers.StickerLocator;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.MediaUtil;
import org.thoughtcrime.securesms.util.RemoteDeleteUtil;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.messages.SignalServicePreview;
import org.whispersystems.signalservice.api.messages.shared.SharedContact;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.internal.push.PushTransportDetails;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.IntroMessageRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReportedContentRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.RevokedMessageRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs.SYNCED;

public class UfsrvMessageUtils
{
  private static final String TAG = Log.tag(UfsrvMessageUtils.class);

  public static @Nullable
  MessageId processUfsrvMessageCommand(@NonNull Context context,
                                       @NonNull SignalServiceContent content,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @NonNull Optional<Long> smsMessageId,
                                       boolean outgoing)
          throws MmsException, BadGroupIdException
  {
    MessageCommand msgCommand = message.getUfsrvCommand().getMsgCommand();
    if (msgCommand == null) {
      Log.e(TAG, String.format("processUfsrvMessageCommand (%d): MessageCommand was null: RETURNING", Thread.currentThread().getId()));
      return null;
    }

    switch (msgCommand.getHeader().getCommand())
    {
      case MessageCommand.CommandTypes.SAY_VALUE:
        if (msgCommand.getAttachmentsCount() > 0 || message.getQuote().isPresent() || message.getSharedContacts().isPresent() || message.getPreviews().isPresent() || message.getMentions().isPresent()) {
          return processMessageCommandSayWithMedia(context, envelope, message, smsMessageId, outgoing);
        } else  {
            return processMessageCommandSay(context, envelope, message, smsMessageId, outgoing);
          }

      case MessageCommand.CommandTypes.INTRO_VALUE:
        return processMessageCommandIntro(context, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.INTRO_USER_RESPONSE_VALUE:
        return processMessageCommandIntroUserResponse(context, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.FLAG_VALUE:
        return processMessageCommandReported(context, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.GUARDIAN_REQUEST_VALUE:
        return processMessageCommandGuardianRequest(context, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.GUARDIAN_LINK_VALUE:
        return processMessageCommandGuardianLink(context, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.GUARDIAN_UNLINK_VALUE:
        return processMessageCommandGuardianUnLink(context, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.EFFECT_VALUE:
        return processMessageCommandMessageEffects(context, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.REACTION_VALUE:
        return processMessageCommandMessageReaction(context, content, envelope, message, smsMessageId, outgoing);

      case MessageCommand.CommandTypes.REVOKE_VALUE:
        return processMessageCommandMessageRevoke(context, content, envelope, message);

      default:
        Log.e(TAG, String.format("processUfsrvMessageCommand (eid:'%d', type:'%d'): Received UKNOWN MESSAGE COMMAND", msgCommand.getHeader().getEid(), msgCommand.getHeader().getCommand()));
    }

    return null;
  }

  //based off PushProcessMessageJob.handleTextMessage
  private static @Nullable
  MessageId processMessageCommandSay(@NonNull Context context,
                                     @NonNull SignalServiceEnvelope envelope,
                                     @NonNull SignalServiceDataMessage message,
                                     @NonNull Optional<Long> smsMessageId,
                                     boolean outgoing)
  throws BadGroupIdException

  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    MessageCommand msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (msgCommand.getHeader().getArgs() == SignalServiceProtos.CommandArgs.ACCEPTED_VALUE && msgCommand.getHeader().getEid() > 0) {
      SignalDatabase.mms().updateMessageIdentifiersForUfsrv(msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getGid(), msgCommand.getHeader().getEid());
      Recipient recipientGroup = Recipient.live(msgCommand.getFences(0).getFid()).get();
      SignalDatabase.recipients().setEid(recipientGroup, msgCommand.getHeader().getEid());

      return null;
    }

    if (msgCommand.getFencesCount() <= 0) {
      Log.e(TAG, String.format("processMessageCommandSay (%d): Received no fence record information: RETURNING", Thread.currentThread().getId()));
      return null;
    }

    FenceRecord fenceRecord = msgCommand.getFences(0);
    String body;
    MessageDatabase database   = SignalDatabase.sms();
    if (envelope.getType() == SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE) {

      body = msgCommand.getMessagesCount() > 0 ? msgCommand.getMessages(0).getMessage().toStringUtf8() : "";
    } else {
      byte [] bodyB64 = org.whispersystems.signalservice.internal.util.Base64.decode(msgCommand.getMessages(0).getMessage().toStringUtf8().getBytes(Charset.forName("UTF-8")));
      byte[] bodyBytes = decrypt(context, envelope, UfsrvUidEncodedForOriginator(msgCommand), bodyB64, smsMessageId);
      if (bodyBytes != null) {
        body = new String(bodyBytes);
      } else {
        return null;
      }
    }

    Recipient            recipientGroup  = groupDatabase.getGroupRecipient(fenceRecord.getFid());//we retrieve

    if (recipientGroup == null || recipientGroup.getId().isUnknown()) {
      Log.e(TAG, String.format("processMessageCommandSay (fid:'%d'): COULD NOT PROCESS MSG: recipientGroup is NULL OR UNKNOWN", fenceRecord.getFid()));

      return null;
    }

    notifyTypingStoppedFromIncomingMessage(context, recipientGroup, UfsrvUidEncodedForOriginator(envelope.getMessageCommand()), envelope.getSourceDevice());

    Optional<QuoteModel> quote        = getValidatedQuote(context, message.getQuote());

    //TODO: this needs to be updated to read off expiration updates from ufsrv command messages
    if (message.getExpiresInSeconds() != recipientGroup.getExpiresInSeconds()) {
      //handleExpirationUpdate(masterSecret, envelope, message, Optional.<Long>empty());
    }

    Pair<Long, Long> messageAndThreadIdPair   = null;
    Optional<InsertResult> messageAndThreadId = null;

    if (smsMessageId.isPresent() && !message.getGroupContext().isPresent()) {
      InsertResult messageAndThreadId2 = database.updateBundleMessageBody(smsMessageId.get(), body);
      if (messageAndThreadIdPair != null) {
        //TODO: this is a temporary hack
        messageAndThreadId = Optional.of(messageAndThreadId2);
      }
    } else {
      LiveRecipient liveRecipientOriginator = Recipient.live(UfsrvUidEncodedForOriginator(envelope.getMessageCommand()));
      IncomingTextMessage textMessage = new IncomingTextMessage(liveRecipientOriginator.getId(),
                                                                envelope.getSourceDevice(),
                                                                msgCommand.getHeader().getWhenClient(),
                                                                envelope.getServerReceivedTimestamp(),
                                                                envelope.getTimestamp(),
                                                                body,
                                                                GroupUtil.idFromGroupContext(message.getGroupContext()),
//                                                              Optional.of(GroupId.v1(message.getGroupInfo().get().getGroupId())),
              /*fenceRecord.getExpireTimer(),*/                 message.getExpiresInSeconds() * 1000, //set in getExpireTimerIfSet in createSignalServiceMessage()
                                                                false,
                                                                envelope.getServerGuid(),
                                                                envelope.getUfsrvCommand());

      textMessage = new IncomingEncryptedMessage(textMessage, body);
      messageAndThreadId = database.insertMessageInbox(textMessage);

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());
    }

    if (msgCommand.getHeader().getEid() > 0)  {
      SignalDatabase.recipients().setEid(recipientGroup, msgCommand.getHeader().getEid());
    }

    if (messageAndThreadId.isPresent()) {
      ApplicationDependencies.getMessageNotifier().updateNotification(context, messageAndThreadId.get().getThreadId());

      return new MessageId(messageAndThreadId.get().getMessageId(), false);
    }

    Log.e(TAG, String.format("processMessageCommandSay: COULD NOT PROCESS MSG"));

    return null;
  }

  //based on PushProcessMessageJob.handleMediaMessage()
  //todo port as in https://github.com/signalapp/Signal-Android/commit/5f31762220fa5ca4a00138e74500bbe4b5232578
  private static @Nullable
  MessageId processMessageCommandSayWithMedia(@NonNull Context context,
                                              @NonNull SignalServiceEnvelope envelope,
                                              @NonNull SignalServiceDataMessage message,
                                              @NonNull Optional<Long> smsMessageId,
                                              boolean outgoing) throws MmsException
  {
    GroupDatabase groupDatabase = SignalDatabase.groups();
    ThreadDatabase threadDatabase = SignalDatabase.threads();

    MessageCommand msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (msgCommand.getHeader().getArgs() == SignalServiceProtos.CommandArgs.ACCEPTED_VALUE && msgCommand.getHeader().getEid() > 0) {
      SignalDatabase.mms().updateMessageIdentifiersForUfsrv(msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getGid(), msgCommand.getHeader().getEid());
      Recipient recipientGroup = Recipient.live(msgCommand.getFences(0).getFid()).get();
      SignalDatabase.recipients().setEid(recipientGroup, msgCommand.getHeader().getEid());

      return null;
    }

    if (msgCommand.getFencesCount() <= 0) {
      Log.e(TAG, String.format("processUfsrvMessageCommand (%d): Received no fence record information: RETURNING", Thread.currentThread().getId()));
      return null;
    }

    FenceRecord fenceRecord = msgCommand.getFences(0);
    SignalServiceProtos.MessageRecord messageRecord = null;

    if (msgCommand.getMessagesCount() > 0) {
      messageRecord = msgCommand.getMessages(0);
    }

    String body;
    if (envelope.getType() == SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE) {
      body = messageRecord != null ? messageRecord.getMessage().toStringUtf8() : "";
    } else {
      byte [] bodyB64 = org.whispersystems.signalservice.internal.util.Base64.decode(messageRecord.getMessage().toStringUtf8().getBytes(Charset.forName("UTF-8")));
      byte[] bodyBytes = decrypt(context, envelope, UfsrvUidEncodedForOriginator(msgCommand), bodyB64, smsMessageId);
      if (bodyBytes != null) {
        body = new String(bodyBytes);
      } else {
        return null;
      }
    }

    Optional<QuoteModel> quote                 = getValidatedQuote(context, message.getQuote());
    Optional<List<Contact>> sharedContacts     = getContacts(message.getSharedContacts());
    Optional<List<LinkPreview>> linkPreviews   = getLinkPreviews(message.getPreviews(), body/*message.getBody().orElse("")*/); //AA+ body
    Optional<List<Mention>>     mentions       = getMentions(message.getMentions());
    Optional<Attachment>        sticker        = getStickerAttachment(context, message.getSticker());
    Recipient            recipientGroup        = groupDatabase.getGroupRecipient(fenceRecord.getFid(), true);


    if (recipientGroup == null) {
      Log.e(TAG, String.format("processMessageCommandSayWithMedia (fid:'%d'): COULD NOT PROCESS MSG: recipient is NULL", fenceRecord.getFid()));

      return null;
    }

    notifyTypingStoppedFromIncomingMessage(context, recipientGroup, UfsrvUidEncodedForOriginator(envelope.getMessageCommand()), envelope.getSourceDevice());

    Optional<InsertResult> insertResult;

    MessageDatabase database = SignalDatabase.mms();
    database.beginTransaction();

    try {
      LiveRecipient liveRecipientOriginator = Recipient.live(UfsrvUidEncodedForOriginator(envelope.getMessageCommand()));
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(liveRecipientOriginator.getId(),
                                                                   msgCommand.getHeader().getWhenClient(),
                                                                   envelope.getServerReceivedTimestamp(),
                                                                   envelope.getTimestamp(),
                                                                   StoryType.NONE,
                                                                   null,
                                                                   false,
                                                                   UfsrvCommandWire.UfsrvType.UFSRV_MESSAGE_VALUE,
                                                                   TimeUnit.SECONDS.toMillis(message.getExpiresInSeconds()),
                                                                   false,
                                                                   messageRecord.getViewOnce(),
                                                                   false,
                                                                   Optional.of(body)/*message.getBody()*/,
                                                                   message.getGroupContext(),
                                                                   Optional.of(UfsrvCommandUtils.getAttachmentsList(envelope.getUfsrvCommand())),
                                                                   quote,
                                                                   sharedContacts,
                                                                   linkPreviews,
                                                                   mentions,
                                                                   sticker,
                                                                   envelope.getServerGuid(),
                                                                   msgCommand.getHeader().getGid(),//AA+
                                                                   msgCommand.getHeader().getEid(),
                                                                   msgCommand.getFences(0).getFid(),
                                                                   0,
                                                                   msgCommand.getHeader().getCommand(),
                                                                   msgCommand.getHeader().getArgs(),
                                                                   envelope.getUfsrvCommand());


      //TODO: this needs to be updated to read off expiration updates from ufsrv command messages
      if (message.getExpiresInSeconds() != recipientGroup.getExpiresInSeconds()) {
        //handleExpirationUpdate(masterSecret, envelope, message, Optional.<Long>empty());
      }

      insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, threadDatabase.getThreadIdFor(null, fenceRecord.getFid())); //-1);

      if (insertResult.isPresent()) {
        if (smsMessageId.isPresent()) {
          SignalDatabase.sms().deleteMessage(smsMessageId.get());
        }

        if (msgCommand.getHeader().getEid() > 0) {
          SignalDatabase.recipients().setEid(recipientGroup, msgCommand.getHeader().getEid());
        }

        database.setTransactionSuccessful();
      }
    } finally {
      database.endTransaction();
    }

    if (insertResult.isPresent()) {
      List<DatabaseAttachment> allAttachments = SignalDatabase.attachments().getAttachmentsForMessage(insertResult.get().getMessageId());
      List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
      List<DatabaseAttachment> attachments = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

      forceStickerDownloadIfNecessary(context, insertResult.get().getMessageId(), stickerAttachments);

      for (DatabaseAttachment attachment : attachments) {
        ApplicationDependencies.getJobManager().add(new AttachmentDownloadJob(insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
      }

      ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
      TrimThreadJob.enqueueAsync(insertResult.get().getThreadId());

      if (messageRecord != null && messageRecord.getViewOnce()) {
        ApplicationDependencies.getViewOnceMessageManager().scheduleIfNecessary();
      }

      return new MessageId(insertResult.get().getMessageId(), true);
    }

    Log.e(TAG, String.format("processMessageCommandSayWithMedia: COULD NOT PROCESS MSG"));

    return null;
  }

  //AA+ porting note: see corresponding in PushProcessMessageJob
  static public Optional<Attachment> getStickerAttachment(Context context, Optional<SignalServiceDataMessage.Sticker> sticker) {
    if (!sticker.isPresent()) {
      return Optional.empty();
    }

    if (sticker.get().getPackId() == null || sticker.get().getPackKey() == null || sticker.get().getAttachment() == null) {
      Log.w(TAG, "Malformed sticker!");
      return Optional.empty();
    }

    String          packId          = Hex.toStringCondensed(sticker.get().getPackId());
    String          packKey         = Hex.toStringCondensed(sticker.get().getPackKey());
    int             stickerId       = sticker.get().getStickerId();
    String          emoji           = sticker.get().getEmoji();
    StickerLocator  stickerLocator  = new StickerLocator(packId, packKey, stickerId, emoji);
    StickerDatabase stickerDatabase = SignalDatabase.stickers();
    StickerRecord stickerRecord   = stickerDatabase.getSticker(stickerLocator.getPackId(), stickerLocator.getStickerId(), false);

    if (stickerRecord != null) {
      return Optional.of(new UriAttachment(stickerRecord.getUri(),
                                           MediaUtil.IMAGE_WEBP,
                                           AttachmentDatabase.TRANSFER_PROGRESS_DONE,
                                           stickerRecord.getSize(),
                                           StickerSlide.WIDTH,
                                           StickerSlide.HEIGHT,
                                           null,
                                           String.valueOf(new SecureRandom().nextLong()),
                                           false,
                                           false,
                                           false,
                                           false,
                                           null,
                                           stickerLocator,
                                           null,
                                           null,
                                           null));
    } else {
      return Optional.of(PointerAttachment.forPointer(Optional.of(sticker.get().getAttachment()), stickerLocator).get());
    }
  }

  static public void forceStickerDownloadIfNecessary(Context context, @NonNull Long messageId, List<DatabaseAttachment> stickerAttachments) {
    if (stickerAttachments.isEmpty()) return;

    DatabaseAttachment stickerAttachment = stickerAttachments.get(0);

    if (stickerAttachment.getTransferState() != AttachmentDatabase.TRANSFER_PROGRESS_DONE) {
      AttachmentDownloadJob downloadJob = new AttachmentDownloadJob(messageId, stickerAttachment.getAttachmentId(), true);

      try {
        downloadJob.setContext(context);
        downloadJob.doWork();
      } catch (Exception e) {
        Log.w(TAG, "Failed to download sticker inline. Scheduling.");
        ApplicationDependencies.getJobManager().add(downloadJob);
      }
    }
  }

  /**
   * @brief
   * For incoming request the following state is recorded: handle used by sender (to), ufsrvuid of the sender (originator), timestamp invitation was sent(ts_sent), eid of the sender, status:UNSEEN, msg, avatar
   * @param context
   * @param envelope
   * @param message
   * @param smsMessageId
   * @param outgoing
   * @return
   */
  private static @Nullable
  MessageId processMessageCommandIntro(@NonNull Context context,
                                       @NonNull SignalServiceEnvelope envelope,
                                       @NonNull SignalServiceDataMessage message,
                                       @NonNull Optional<Long> smsMessageId,
                                       boolean outgoing)
  {
    MessageCommand              msgCommand    = message.getUfsrvCommand().getMsgCommand();
    UnfacdIntroContactsDatabase introDatabase = SignalDatabase.unfacdIntroContacts();

    if (isCommandAccepted(msgCommand)) {//server accepted intro request. ufsrvuid of the receiver not revealed until they accepted intro message
      Log.i(TAG, String.format("(eid:'%d', target:'%s') processMessageCommandIntro: uf INTRO MSG ACCEPTED BY SERVER", msgCommand.getHeader().getEid(), msgCommand.getIntro().getTo()));
      SignalDatabase.recipients().setEid(UfsrvUserUtils.myOwnRecipient(false), msgCommand.getHeader().getEid());
      //todo delete avatar blob
      Optional<Pair<Long, IntroContactDescriptor>> descriptor = introDatabase.getIntroContactBySender(msgCommand.getHeader().getWhenClient());
      if (descriptor.isPresent()) {
        introDatabase.setResponseStatus(descriptor.get().first(), ResponseStatus.SENT, msgCommand.getHeader().getWhen());
        introDatabase.setEid(descriptor.get().first(), msgCommand.getHeader().getEid());
      }

      return null;
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("(eid:'%d', target:'%s') processMessageCommandIntro: INTRO MSG REJECTED BY SERVER", msgCommand.getHeader().getEid(), UfsrvUid.EncodedfromSerialisedBytes(msgCommand.getTo(0).getUfsrvuid().toByteArray())));
      return null;
    } else if (isCommandSydnced(msgCommand)) {//this user received an intro request. The enclosing MessageCommand will include a UserRecord for originator.
      String avatarId = null;
      IntroMessageRecord introMessageRecord = msgCommand.getIntro();
      String UfsrvUidOriginator = introMessageRecord.getOriginator();

      Optional<Pair<Long, IntroContactDescriptor>> introDescriptorOptional = introDatabase.getIntroContactBySender(UfsrvUidOriginator);//todo also use sender timestamp/eid
      if (introDescriptorOptional.isPresent()) {
        IntroContactDescriptor introContactDescriptor = introDescriptorOptional.get().second();
        if (introContactDescriptor.isAccepted() || introContactDescriptor.isIgnored() || introContactDescriptor.isRejected()) {
          Log.e(TAG, String.format("(eid:'%d', target:'%s', status:'%s') processMessageCommandIntro: INTRO CONTACT ALREADY PROCESSED (NOTICE: FALLING THROUGH ANYWAY)", msgCommand.getHeader().getEid(), UfsrvUidOriginator, introContactDescriptor.getResponseStatus()));
//          return (long) -1;//AA todo: enable return when debugging is done
        }
      }

      if (introMessageRecord.hasAvatar() && !TextUtils.isEmpty(introMessageRecord.getAvatar().getId())) {
        ApplicationDependencies.getJobManager().add(new IntroMessageAvatarDownloadJob(UfsrvUidOriginator, introMessageRecord.getAvatar()));

        avatarId = introMessageRecord.getAvatar().getId();
      }

      String msg = introMessageRecord.hasMsg() ? introMessageRecord.getMsg().toStringUtf8() : "";

      IntroContactDescriptor descriptor = new IntroContactDescriptor(LocallyAddressableUfsrvUid.from(RecipientId.UNKNOWN, introMessageRecord.getOriginator()),
                                                                     introMessageRecord.getTo(),//handle as provided by sender
                                                                     msg, avatarId, IntroDirection.INCOMING, msgCommand.getHeader().getWhenClient(), ResponseStatus.UNSEEN, msgCommand.getHeader().getWhen(), msgCommand.getHeader().getEid());
      long introId = introDatabase.insertIntroContact(descriptor);
      introDatabase.setResponseStatus(introId, ResponseStatus.UNSEEN, msgCommand.getHeader().getWhen());//todo not necessary as it is done above

      ApplicationDependencies.getMessageNotifier().notifyIntroMessage(context, introId);
      ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventIntroContact(descriptor, introId, SYNCED));

//      IntroContactNotificationBuilder.getIntroContactNotification(context, 0, recipientFromMessageCommandOriginator(msgCommand, false), introId);
      /*if (ApplicationContext.getInstance().isAppVisible()) {

        ConversationListActivity.launchMeWithIntroContactDescriptor(ApplicationContext.getInstance(), introId);
      } else {
        ApplicationDependencies.getMessageNotifier().notifyIntroMessage(context, recipientFromMessageCommandOriginator(msgCommand, false), introId);
      }*/

      return null;
    }

    Log.e(TAG, String.format("(args:'%d') processMessageCommandIntro: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  /**
   * Confirmation of acceptance of a previously sent an IntroRequest.
   * @param context
   * @param envelope
   * @param message
   * @param smsMessageId
   * @param outgoing
   * @return
   */
  private static @Nullable
  MessageId processMessageCommandIntroUserResponse(@NonNull Context context,
                                                   @NonNull SignalServiceEnvelope envelope,
                                                   @NonNull SignalServiceDataMessage message,
                                                   @NonNull Optional<Long> smsMessageId,
                                                   boolean outgoing)
  {
    MessageCommand      msgCommand          = message.getUfsrvCommand().getMsgCommand();
    IntroMessageRecord  introMessageRecord  = msgCommand.getIntro();
    String              ufsrvUidOriginator  = introMessageRecord.getOriginator(); //only available if user accepted the intro request

    Optional<Pair<Long, IntroContactDescriptor>> introDescriptor  =SignalDatabase.unfacdIntroContacts().getIntroContactByEid(msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getEidClient());
    if (!introDescriptor.isPresent() || introDescriptor.get().first() <= 0) {
      Log.e(TAG, String.format("(args:'%d', originator:'%s', eid_client:'%d') processMessageCommandIntroUserResponse: COULD NOT FETCH IntroId", msgCommand.getHeader().getArgs(), ufsrvUidOriginator, msgCommand.getHeader().getEidClient()));

      return null;
    }

    long introId = introDescriptor.get().first();

    if (isIntroUserResponseAccepted(introMessageRecord)) {//user accepted intro request by this user (originator of intro)
      Log.i(TAG, String.format("(eid:'%d', user:'%s') processMessageCommandIntroUserResponse: uf INTRO MSG ACCEPTED BY USER", msgCommand.getHeader().getEid(), ufsrvUidOriginator));
      //todo delete avatar blob
     SignalDatabase.unfacdIntroContacts().setResponseStatus(introId, ResponseStatus.ACCEPTED, msgCommand.getHeader().getWhen());
     SignalDatabase.unfacdIntroContacts().setAddressable(introId, ufsrvUidOriginator);
      Recipient.live(ufsrvUidOriginator).resolveUfsrvUidByForcedNetwork();

      return null;
    } else if (isIntroUserResponseRejected(introMessageRecord)) {
      Log.e(TAG, String.format("(eid:'%d', user:'%s') processMessageCommandIntroUserResponse: INTRO MSG REJECTED BY USER", msgCommand.getHeader().getEid(), ufsrvUidOriginator));
     SignalDatabase.unfacdIntroContacts().setResponseStatus(introId, ResponseStatus.REJECTED, msgCommand.getHeader().getWhen());
      return null;
    } else if (isIntroUserResponseIgnored(introMessageRecord)) {
      Log.i(TAG, String.format("(eid:'%d', target:'%s') processMessageCommandIntroUserResponse: uf INTRO MSG IGNORED BY USER", msgCommand.getHeader().getEid(), msgCommand.getIntro().getTo()));
     SignalDatabase.unfacdIntroContacts().setResponseStatus(introId, ResponseStatus.IGNORED, msgCommand.getHeader().getWhen());
      return null;
    }

    ApplicationDependencies.getMessageNotifier().notifyIntroMessage(context, introId);
//      ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventIntroContact(descriptor, introId, SYNCED));

    return null;
  }

  //SQLiteQuery: SELECT _id, unique_row_id, body, type, thread_id, address, address_device_id, subject, date_sent, date_received, m_type, msg_box, status, unidentified, part_count, ct_l, tr_id, m_size, exp, st, delivery_receipt_count, read_receipt_count, mismatched_identities, network_failures, subscription_id, expires_in, expire_started, notified, transport_type, ufsrv_command, ufsrv_eid, ufsrv_gid, ufsrv_fid, ufsrv_status, ufsrv_cmd_type, ufsrv_cmd_arg, attachment_json, quote_id, quote_author, quote_body, quote_missing, quote_attachment, shared_contacts, previews, reveal_duration FROM (SELECT DISTINCT date_sent AS date_sent, date AS date_received, _id, 'SMS::' || _id || '::' || date_sent AS unique_row_id, NULL AS attachment_json, body, read, thread_id, type, address, address_device_id, subject, NULL AS m_type, NULL AS msg_box, status, NULL AS part_count, NULL AS ct_l, NULL AS tr_id, NULL AS m_size, NULL AS exp, NULL AS st, unidentified, delivery_receipt_count, read_receipt_count, mismatched_identities, subscription_id, expires_in, expire_started, notified, ufsrv_command, ufsrv_eid, ufsrv_gid, ufsrv_fid, ufsrv_status, ufsrv_cmd_type, ufsrv_cmd_arg, NULL AS network_failures, 'sms' AS transport_type, NULL AS quote_id, NULL AS quote_author, NULL AS quote_body, NULL AS quote_missing, NULL AS quote_attachment, NULL AS shared_contacts, NULL AS previews, NULL AS reveal_duration FROM sms WHERE (ufsrv_gid = 92) UNION ALL SELECT DISTINCT date AS date_sent, date_received AS date_received, mms._id AS _id, 'MMS::' || mms._id || '::' || date AS unique_row_id, json_group_array(json_object('_id', part._id, 'unique_id', part.unique_id, 'mid', part.mid,'data_size', part.data_size, 'file_name', part.file_name, '_data', part._data, 'thumbnail', part.thumbnail, 'ct', part.ct, 'cl', part.cl, 'fast_preflight_id', part.fast_preflight_id, 'voice_note', part.voice_note, 'width', part.width, 'height', part.height, 'quote', part.quote, 'cd', part.cd, 'name', part.name, 'pending_push', part.pending_push, 'caption', part.caption, 'sticker_pack_id', part.sticker_pack_id, 'sticker_pack_key', part.sticker_pack_key, 'sticker_id', part.sticker_id, 'ufid', part.ufid)) AS attachment_json, body, read, thread_id, NULL AS type, address, address_device_id, NULL AS subject, m_type, msg_box, NULL AS status, part_count, ct_l, tr_id, m_size, exp, st, unidentified, delivery_receipt_count, read_receipt_count, mismatched_identities, subscription_id, expires_in, expire_started, notified, ufsrv_command, ufsrv_eid, ufsrv_gid, ufsrv_fid, ufsrv_status, ufsrv_cmd_type, ufsrv_cmd_arg, network_failures, 'mms' AS transport_type, quote_id, quote_author, quote_body, quote_missing, quote_attachment, shared_contacts, previews, reveal_duration FROM mms LEFT OUTER JOIN part ON part.mid = mms._id WHERE (ufsrv_gid = 92) GROUP BY mms._id ORDER BY date_received ASC)
  private static @Nullable
  MessageId processMessageCommandReported(@NonNull Context context,
                                          @NonNull SignalServiceEnvelope envelope,
                                          @NonNull SignalServiceDataMessage message,
                                          @NonNull Optional<Long> smsMessageId,
                                          boolean outgoing)
  {
    MessageCommand     msgCommand = message.getUfsrvCommand().getMsgCommand();
    SignalServiceProtos.ReportedContentRecord reportedRecord = msgCommand.getReported(0);

    if (isCommandAccepted(msgCommand)) {
      Log.i(TAG, String.format("(gid:'%d') processMessageCommandReported: MSG ACCEPTED BY SERVER", msgCommand.getHeader().getGid()));
      SignalDatabase.mmsSms().setMessageStatus(reportedRecord.getGid(), reportedRecord.getStatus().getNumber());
      setGroupEidFromReportedMessage(context, reportedRecord, msgCommand.getHeader().getArgs());

      return null;
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("(gid:'%d') processMessageCommandReported: MSG REJECTED BY SERVER", reportedRecord.getGid()));

      return null;
    } else if (isCommandSydnced(msgCommand)) {
      Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s', status:'%d') processMessageCommandReported: MSG SYNCED BY SERVER", reportedRecord.getEid(), reportedRecord.getGid(), recipientFromMessageCommandOriginator(msgCommand, false).requireAddress(), reportedRecord.getStatus().getNumber()));
      SignalDatabase.mmsSms().setMessageStatus(reportedRecord.getGid(), reportedRecord.getStatus().getNumber());
      setGroupEidFromReportedMessage(context, reportedRecord, msgCommand.getHeader().getArgs());

      return insertMessageReportedLog(context, recipientFromMessageCommandOriginator(msgCommand, false), reportedRecord, msgCommand.getHeader());
    }

    Log.e(TAG, String.format("(args:'%d') processMessageCommandReported: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  static private void setGroupEidFromReportedMessage(Context context, ReportedContentRecord reportedRecord, int commandArgs)
  {
    if (reportedRecord.getFid() > 0 && reportedRecord.getEid() > 0) {
      Recipient groupRecipient = Recipient.live(reportedRecord.getFid()).get();
      SignalDatabase.recipients().setEid(groupRecipient, reportedRecord.getEid());
    } else {
      Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s', status:'%d', arg:'%d') processMessageCommandReported: ERROR: FID and/or EID NOT SET", reportedRecord.getEid(), reportedRecord.getGid(), UfsrvUid.EncodedfromSerialisedBytes(reportedRecord.getOriginator().toByteArray()), reportedRecord.getStatus().getNumber(), commandArgs));
    }
  }

  static private void setGroupEidFromGuardianRequest(Context context, FenceRecord fenceRecord, int commandArgs)
  {
    if (fenceRecord.getFid() > 0 && fenceRecord.getEid() > 0) {
      Recipient groupRecipient = Recipient.live(fenceRecord.getFid()).get();
      SignalDatabase.recipients().setEid(groupRecipient, fenceRecord.getEid());
    } else {
      Log.e(TAG, String.format("(eid:'%d', arg:'%d') setGroupEidFromGuardianRequest: ERROR: FID and/or EID NOT SET", fenceRecord.getEid(), commandArgs));
    }
  }

  static MessageId insertMessageReportedLog(Context context, Recipient recipientOriginator, ReportedContentRecord reportedRecord, SignalServiceProtos.CommandHeader commandHeader)
  {
    Recipient recipientGroup = Recipient.live(reportedRecord.getFid()).get();
    long threadId = SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId());

    SmsMessageRecord smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientOriginator,
                                                             recipientFromReportedMessageCommandOriginator(reportedRecord, false),
                                                             1,
                                                             commandHeader.getWhenClient(), commandHeader.getWhen(), commandHeader.getWhen(),
                                                             0, 0,
                                                             SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId()),
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, reportedRecord.getGid(), reportedRecord.getEid(), reportedRecord.getFid(), 0, commandHeader.getCommand(), commandHeader.getArgs());
    Pair <Long, Long>insertResult = SignalDatabase.sms().insertMessageReportedTypeLog(smsMessageRecord, threadId, true);

    return new MessageId(insertResult.first(), false);
  }

  private static @Nullable
  MessageId processMessageCommandGuardianRequest(@NonNull Context context,
                                                 @NonNull SignalServiceEnvelope envelope,
                                                 @NonNull SignalServiceDataMessage message,
                                                 @NonNull Optional<Long> smsMessageId,
                                                 boolean outgoing)
  {
    MessageCommand     msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (isCommandAccepted(msgCommand)) {
      return handleGuardianRequestAccepted(context, msgCommand);
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("() processMessageCommandGuardianRequest: MSG REJECTED BY SERVER"));

      return null;
    } else if (isCommandSydnced(msgCommand)) {
     return handleGuardianRequestSynced(context, envelope, msgCommand, smsMessageId);
    }

    Log.e(TAG, String.format("(args:'%d') processMessageCommandGuardianRequest: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  static private MessageId handleGuardianRequestAccepted(Context context, MessageCommand msgCommand)
  {
    SignalServiceProtos.GuardianRecord guardianRecord = msgCommand.getGuardian();

    Recipient recipientGuardian = recipientGuardianFromGuardianCommand(msgCommand, false);
    Log.i(TAG, String.format("(gid:'%d') handleGuardianRequestAccepted: MSG ACCEPTED BY SERVER", msgCommand.getHeader().getGid()));

    Recipient recipientGroup = Recipient.live(msgCommand.getGuardian().getFid()).get();
    long threadId = SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId());
    SmsMessageRecord smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientGuardianFromGuardianCommand(msgCommand, false),
                                                             null,
                                                             1,
                                                             msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getWhen(), msgCommand.getHeader().getWhen(),
                                                             0, 0,
                                                             threadId,
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, msgCommand.getHeader().getGid(),
                                                             guardianRecord.getOriginator().getEid(),
                                                             msgCommand.getGuardian().getFid(), 0, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs());
    Pair <Long, Long>insertResult = SignalDatabase.sms().insertGuardianTypeLog(smsMessageRecord, threadId, true);

    if (guardianRecord.getGuardian().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientGuardian, guardianRecord.getGuardian().getEid());
    }

    if (guardianRecord.getOriginator().getEid() > 0) {
      SignalDatabase.recipients().setEid(UfsrvUserUtils.myOwnRecipient(false), guardianRecord.getOriginator().getEid());
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventGuardianCommand(new GuardianDescriptor(recipientGuardian, TextSecurePreferences.getGuardianEncryptedSecret(context)), msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs()));

    return new MessageId(insertResult.first(), false);
  }

  static private MessageId handleGuardianRequestSynced(Context context, SignalServiceEnvelope envelope, MessageCommand msgCommand, Optional<Long> smsMessageId)
  {
    SignalServiceProtos.GuardianRecord guardianRecord = msgCommand.getGuardian();

    String body = "";
    if (envelope.getType() == SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE) {
      Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s', challenge:'%s') handleGuardianRequestSynced: MSG CIPHER COMPROMISED", msgCommand.getGuardian().getOriginator().getEid(), msgCommand.getHeader().getGid(), recipientOriginatorFromGuardianCommand(msgCommand, false).requireAddress(), body));
      return null;
    } else {
      byte [] bodyB64 = org.whispersystems.signalservice.internal.util.Base64.decode(msgCommand.getMessages(0).getMessage().toStringUtf8().getBytes(Charset.forName("UTF-8")));
      byte[] bodyBytes = decrypt(context, envelope, UfsrvUid.EncodedfromSerialisedBytes(msgCommand.getGuardian().getOriginator().getUfsrvuid().toByteArray()), bodyB64, smsMessageId);
      if (bodyBytes != null) {
        body = new String(bodyBytes);
      } else {
        return null;
      }
    }
    Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s', challenge:'%s') handleGuardianRequestSynced: MSG SYNCED BY SERVER", msgCommand.getHeader().getEid(), msgCommand.getHeader().getGid(), recipientFromMessageCommandOriginator(msgCommand, false).requireAddress(), body));

    Recipient recipientOriginator = recipientOriginatorFromGuardianCommand(msgCommand, false);
    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventGuardianCommand(new GuardianDescriptor(recipientOriginator, body), msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs()));

    if (guardianRecord.getGuardian().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientGuardianFromGuardianCommand(msgCommand, false), guardianRecord.getGuardian().getEid());
    }

    if (guardianRecord.getOriginator().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientOriginator, guardianRecord.getOriginator().getEid());
    }

    Recipient recipientGroup = Recipient.live(msgCommand.getGuardian().getFid()).get();
    long threadId = SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId());
    SmsMessageRecord smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientOriginatorFromGuardianCommand(msgCommand, false),
                                                             null,
                                                             1,
                                                             msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getWhen(), envelope.getServerReceivedTimestamp(),
                                                             0, 0,
                                                             SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId()),
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, msgCommand.getHeader().getGid(),
                                                             recipientOriginator.getEid(), msgCommand.getGuardian().getFid(), 0, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs());
    Pair <Long, Long>insertResult = SignalDatabase.sms().insertGuardianTypeLog(smsMessageRecord, threadId, true);

    return new MessageId(insertResult.first(), false);
  }

  private static @Nullable
  MessageId processMessageCommandGuardianLink(@NonNull Context context,
                                              @NonNull SignalServiceEnvelope envelope,
                                              @NonNull SignalServiceDataMessage message,
                                              @NonNull Optional<Long> smsMessageId,
                                              boolean outgoing)
  {
    MessageCommand     msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (isCommandAccepted(msgCommand)) {
      return handleGuardianLinkAccepted(context, msgCommand);
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("() processMessageCommandGuardianLink: MSG REJECTED BY SERVER"));

      return null;
    } else if (isCommandSydnced(msgCommand)) {
      return handleGuardianLinkSynced(context, envelope, msgCommand, smsMessageId);
    }

    Log.e(TAG, String.format("(args:'%d') processMessageCommandGuardianLink: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  static private MessageId handleGuardianLinkAccepted(Context context, MessageCommand msgCommand)
  {
    SignalServiceProtos.GuardianRecord guardianRecord = msgCommand.getGuardian();

    Recipient recipientOriginator = recipientOriginatorFromGuardianCommand(msgCommand, false);
    SignalDatabase.recipients().setGuardianStatus(recipientOriginator, RecipientDatabase.GuardianStatus.LINKED);

    Log.i(TAG, String.format("(gid:'%d') handleGuardianLinkAccepted: MSG ACCEPTED BY SERVER", msgCommand.getHeader().getGid()));
    Recipient recipientGroup = Recipient.live(msgCommand.getGuardian().getFid()).get();
    long threadId = SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId());
    SmsMessageRecord smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientOriginator,
                                                             null,
                                                             1,
                                                             msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getWhen(), msgCommand.getHeader().getWhen(),
                                                             0, 0,
                                                             SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId()),
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, msgCommand.getHeader().getGid(),
                                                             guardianRecord.getGuardian().getEid(),
                                                             msgCommand.getGuardian().getFid(), 0, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs());
    Pair <Long, Long>insertResult = SignalDatabase.sms().insertGuardianTypeLog(smsMessageRecord, threadId, true);

    if (guardianRecord.getGuardian().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientGuardianFromGuardianCommand(msgCommand, false), guardianRecord.getGuardian().getEid());
    }

    if (guardianRecord.getOriginator().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientOriginator, guardianRecord.getOriginator().getEid());
    }

    GroupManager.GroupActionResult result = createThreadAndGroupIfNecessary(context, recipientOriginator);

    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventGuardianCommand(new GuardianDescriptor(recipientOriginator, null), msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs()));

    return new MessageId(insertResult.first(), false);
  }

  static private GroupManager.GroupActionResult createThreadAndGroupIfNecessary (Context context, Recipient recipientOriginator)
  {
    GroupManager.GroupActionResult result = SignalDatabase.threads().getGuardianThreadId(context, recipientOriginator.requireAddress());
    if (result.getThreadId() == -1) {
      Set<Recipient> members = new HashSet<>();
      members.add(recipientOriginator);
      return GroupManager.createGuardianGroup(context, members, null);
    }

    return result;
  }

  //at this stage only one guardian can be had per target user
  static private MessageId handleGuardianLinkSynced(Context context, SignalServiceEnvelope envelope, MessageCommand msgCommand, Optional<Long> smsMessageId)
  {
   Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s') handleGuardianLinkSynced: MSG SYNCED BY SERVER", msgCommand.getHeader().getEid(), msgCommand.getHeader().getGid(), recipientFromMessageCommandOriginator(msgCommand, false).requireAddress()));
    SignalServiceProtos.GuardianRecord guardianRecord = msgCommand.getGuardian();

    Recipient recipientGuardian = recipientGuardianFromGuardianCommand(msgCommand, false);
    SignalDatabase.recipients().setGuardianStatus(recipientGuardian, RecipientDatabase.GuardianStatus.GUARDIAN);
    TextSecurePreferences.setGuardian(context, recipientGuardian.getUfsrvUid());

    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventGuardianCommand(null, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs()));

    if (guardianRecord.getGuardian().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientGuardian, guardianRecord.getGuardian().getEid());
    }

    if (guardianRecord.getOriginator().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientOriginatorFromGuardianCommand(msgCommand, false), guardianRecord.getOriginator().getEid());
    }

    Recipient recipientGroup = Recipient.live(msgCommand.getGuardian().getFid()).get();
    long threadId = SignalDatabase.threads().getThreadIdFor(null, recipientGroup.getUfsrvId());
    SmsMessageRecord smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientGuardian,
                                                             null,
                                                             1,
                                                             msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getWhen(), envelope.getServerReceivedTimestamp(),
                                                             0, 0,
                                                            threadId,
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, msgCommand.getHeader().getGid(),
                                                             guardianRecord.getGuardian().getEid(),
                                                             msgCommand.getGuardian().getFid(), 0, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs());
    Pair <Long, Long>insertResult = SignalDatabase.sms().insertGuardianTypeLog(smsMessageRecord, threadId, true);

    return new MessageId(insertResult.first(), false);
  }

  private static @Nullable
  MessageId processMessageCommandGuardianUnLink(@NonNull Context context,
                                                @NonNull SignalServiceEnvelope envelope,
                                                @NonNull SignalServiceDataMessage message,
                                                @NonNull Optional<Long> smsMessageId,
                                                boolean outgoing)
  {
    MessageCommand  msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (isCommandAccepted(msgCommand)) {
      return handleGuardianUnLinkAccepted(context, msgCommand);
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("() processMessageCommandGuardianUnLink: MSG REJECTED BY SERVER"));

      return null;
    } else if (isCommandSydnced(msgCommand)) {
      return handleGuardianUnLinkSynced(context, envelope, msgCommand, smsMessageId);
    }

    Log.e(TAG, String.format("(args:'%d') processMessageCommandGuardianUnLink: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  static private MessageId handleGuardianUnLinkAccepted(Context context, MessageCommand msgCommand)
  {
    SignalServiceProtos.GuardianRecord guardianRecord = msgCommand.getGuardian();

    Recipient recipientOriginator = recipientOriginatorFromGuardianCommand(msgCommand, false);
    SignalDatabase.recipients().setGuardianStatus(recipientOriginator, RecipientDatabase.GuardianStatus.UNLINKED);

    Log.i(TAG, String.format("(gid:'%d') handleGuardianUnLinkAccepted: MSG ACCEPTED BY SERVER", msgCommand.getHeader().getGid()));

    GroupManager.GroupActionResult result = SignalDatabase.threads().getGuardianThreadId(context, recipientOriginator.requireAddress());
    if (result.getThreadId() == -1) {
      Log.e(TAG, String.format("(originator(guardian):'%s') handleGuardianUnLinkAccepted: ERROR: COULD NOT LOCATE GUARDIAN THREAD", recipientOriginator.requireAddress().serialize()));
      return null;
    }

    //todo: also insert log in teh private group-for-2 if exists
    SmsMessageRecord smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientOriginator,
                                                             null,
                                                             1,
                                                             msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getWhen(), msgCommand.getHeader().getWhen(),
                                                             0, 0,
                                                             result.getThreadId(),
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, msgCommand.getHeader().getGid(),
                                                             guardianRecord.getGuardian().getEid(),
                                                             msgCommand.getGuardian().getFid(), 0, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs());
    Pair <Long, Long>insertResult = SignalDatabase.sms().insertGuardianTypeLog(smsMessageRecord, result.getThreadId(), true);

    Pair <Long, Recipient> forTwoGroup = UfsrvFenceUtils.GetThreadIdPairedGroup (recipientOriginator, UfsrvUserUtils.myOwnRecipient(false));
    if (forTwoGroup.first() == -1) {
      Log.e(TAG, String.format("(originator):'%s') handleGuardianUnLinkAccepted: ERROR: COULD NOT LOCATE ForTwo group", recipientOriginator.requireAddress().serialize()));
      return null;
    }
    smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientOriginator,
                                                             null,
                                                             1,
                                                             msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getWhen(), msgCommand.getHeader().getWhen(),
                                                             0, 0,
                                                              forTwoGroup.first(),
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, msgCommand.getHeader().getGid(),
                                                             guardianRecord.getGuardian().getEid(),
                                                             msgCommand.getGuardian().getFid(), 0, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs());
    insertResult = SignalDatabase.sms().insertGuardianTypeLog(smsMessageRecord, forTwoGroup.first(), true);

    if (guardianRecord.getGuardian().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientGuardianFromGuardianCommand(msgCommand, false), guardianRecord.getGuardian().getEid());
    }

    if (guardianRecord.getOriginator().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientOriginator, guardianRecord.getOriginator().getEid());
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventGuardianCommand(result.getGroupRecipient(), new GuardianDescriptor(recipientOriginator, null), msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs()));

    return new MessageId(insertResult.first(), false);
  }

  static private MessageId handleGuardianUnLinkSynced(Context context, SignalServiceEnvelope envelope, MessageCommand msgCommand, Optional<Long> smsMessageId)
  {
    Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s') handleGuardianUnLinkSynced: MSG SYNCED BY SERVER", msgCommand.getHeader().getEid(), msgCommand.getHeader().getGid(), recipientFromMessageCommandOriginator(msgCommand, false).requireAddress()));
    SignalServiceProtos.GuardianRecord guardianRecord = msgCommand.getGuardian();

    Recipient recipientGuardian = recipientGuardianFromGuardianCommand(msgCommand, false);
    SignalDatabase.recipients().setGuardianStatus(recipientGuardian, RecipientDatabase.GuardianStatus.NONE);
    TextSecurePreferences.setGuardian(context, null);

    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventGuardianCommand(null, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs()));

    if (guardianRecord.getGuardian().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientGuardian, guardianRecord.getGuardian().getEid());
    }

    if (guardianRecord.getOriginator().getEid() > 0) {
      SignalDatabase.recipients().setEid(recipientOriginatorFromGuardianCommand(msgCommand, false), guardianRecord.getOriginator().getEid());
    }

    Pair<Long, Recipient> forTwoGroup = UfsrvFenceUtils.GetThreadIdPairedGroup(recipientGuardian, UfsrvUserUtils.myOwnRecipient(false));
    if (forTwoGroup.first() == -1) {
      Log.e(TAG, String.format("(guardian):'%s') handleGuardianUnLinkSynced: ERROR: COULD NOT LOCATE ForTwo group", recipientGuardian.requireAddress().serialize()));
      return null;
    }

    SmsMessageRecord smsMessageRecord = new SmsMessageRecord(0, null,
                                                             recipientGuardian,
                                                             null,
                                                             1,
                                                             msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getWhen(), envelope.getServerReceivedTimestamp(),
                                                             0, 0,
                                                             forTwoGroup.first(),
                                                             0, null, 0, 0, 0, 0, false, Collections.emptyList(), false, 0, -1,
                                                             null, msgCommand.getHeader().getGid(),
                                                             guardianRecord.getGuardian().getEid(),
                                                             msgCommand.getGuardian().getFid(), 0, msgCommand.getHeader().getCommand(), msgCommand.getHeader().getArgs());
   Pair <Long, Long>insertResult = SignalDatabase.sms().insertGuardianTypeLog(smsMessageRecord, forTwoGroup.first(), true);

    return new MessageId(insertResult.first(), false);
  }

  private static @Nullable
  MessageId processMessageCommandMessageEffects(@NonNull Context context,
                                                @NonNull SignalServiceEnvelope envelope,
                                                @NonNull SignalServiceDataMessage message,
                                                @NonNull Optional<Long> smsMessageId,
                                                boolean outgoing)
  {
    MessageCommand     msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (isCommandAccepted(msgCommand)) {
      if (msgCommand.getHeader().getEid() > 0) {
        SignalDatabase.mms().updateMessageIdentifiersForUfsrv(msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getGid(), msgCommand.getHeader().getEid());
        Recipient recipientGroup = Recipient.live(msgCommand.getFences(0).getFid()).get();
        SignalDatabase.recipients().setEid(recipientGroup, msgCommand.getHeader().getEid());

        ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventMessageEffectsCommand(recipientGroup, 0, "", msgCommand.getHeader().getArgs()));

        return null;
      }
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("() processMessageCommandMessageEffects: MSG REJECTED BY SERVER"));

      return null;
    } else if (isCommandSydnced(msgCommand)) {
      return handleMessageEffectsSynced(context, envelope, msgCommand, smsMessageId);
    }

    Log.e(TAG, String.format("(args:'%d') processMessageCommandMessageEffects: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  static private MessageId handleMessageEffectsSynced(Context context, SignalServiceEnvelope envelope, MessageCommand msgCommand, Optional<Long> smsMessageId)
  {
    Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s') handleMessageEffectsSynced: MSG SYNCED BY SERVER", msgCommand.getHeader().getEid(), msgCommand.getHeader().getGid(), recipientFromMessageCommandOriginator(msgCommand, false).requireAddress()));
    SignalServiceProtos.MessageRecord messageRecord = msgCommand.getMessages(0);

    Recipient recipientGroup = Recipient.live(msgCommand.getFences(0).getFid()).get();
    ApplicationContext.getInstance().getUfsrvcmdEvents().postSticky(new AppEventMessageEffectsCommand(recipientGroup,
                                                                                                      messageRecord.getEffect().getType().getNumber(),
                                                                                                      messageRecord.getMessage().toStringUtf8(),
                                                                                                      msgCommand.getHeader().getArgs()));

    return null;
  }

  private static @Nullable
  MessageId processMessageCommandMessageReaction(@NonNull Context context,
                                                 @NonNull SignalServiceContent content,
                                                 @NonNull SignalServiceEnvelope envelope,
                                                 @NonNull SignalServiceDataMessage message,
                                                 @NonNull Optional<Long> smsMessageId,
                                                 boolean outgoing) {
    MessageCommand     msgCommand = message.getUfsrvCommand().getMsgCommand();
    SignalServiceProtos.MessageReactionRecord messageReactionRecord = msgCommand.getReaction();

    if (messageReactionRecord == null) {
      Log.e(TAG, String.format("(args:'%d') processMessageCommandMessageReaction: COULD NOT FIND REACTION RECORD", msgCommand.getHeader().getArgs()));

      return null;
    }

    if (isCommandAccepted(msgCommand)) {
      if (msgCommand.getHeader().getEid() > 0) {
        SignalDatabase.mms().updateMessageIdentifiersForUfsrv(msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getGid(), msgCommand.getHeader().getEid());
        Recipient recipientGroup = Recipient.live(messageReactionRecord.getFid()).get();
        SignalDatabase.recipients().setEid(recipientGroup, msgCommand.getHeader().getEid());

        return null;
      }
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format("() processMessageCommandMessageReaction: MSG REJECTED BY SERVER"));

      return null;
    } else if (isCommandSydnced(msgCommand)) {
      return handleMessageReactionSynced(context, content, envelope, message, msgCommand, smsMessageId);
    }

    Log.e(TAG, String.format("(args:'%d') processMessageCommandMessageReaction: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return  null;
  }

  //AA PORTING NOTE adapted from PushProcessMessageJob.handleReaction()
  static private MessageId handleMessageReactionSynced(Context context, @NonNull SignalServiceContent content,SignalServiceEnvelope envelope, @NonNull SignalServiceDataMessage message, MessageCommand msgCommand, Optional<Long> smsMessageId)
  {
    Log.e(TAG, String.format("(eid:'%d', gid:'%d', orig:'%s') handleMessageReactionSynced: MSG SYNCED BY SERVER", msgCommand.getHeader().getEid(), msgCommand.getHeader().getGid(), recipientFromMessageCommandOriginator(msgCommand, false).requireAddress()));

    // TODO: 11/07/2024 handle story reaction as per https://github.com/signalapp/Signal-Android/commit/437c1e2f21ff579140438e18c1b441c2e3a4311b
    /*if (content.getStoryMessage().isPresent()) {
      log(content.getTimestamp(), "Reaction has a story context. Treating as a story reaction.");
      handleStoryReaction(content, message, senderRecipient);
      return null;
    }*/

    SignalServiceProtos.MessageReactionRecord reactionx = msgCommand.getReaction();

    if (!EmojiUtil.isEmoji(reactionx.getEmoji())) {
      Log.w(TAG, "Reaction text is not a valid emoji! Ignoring the message.");
      return null;
    }

    Recipient recipientGroup = Recipient.live(reactionx.getFid()).get();

    Recipient     reactionAuthor = recipientFromMessageCommandOriginator(msgCommand, false);//Recipient.externalPush(context, content.getSender());
    Recipient     targetAuthor  = Recipient.live(reactionx.getTargetAuthor()).get();
    MessageRecord targetMessage = SignalDatabase.mmsSms().getMessageFor(reactionx.getTargetSentTimestamp(), targetAuthor.getId());

    if (targetMessage == null) {
      Log.w(TAG, "[handleReaction] Could not find matching message! Putting it in the early message cache. timestamp: " + reactionx.getTargetSentTimestamp() + "  author: " + targetAuthor.getId());
      ApplicationDependencies.getEarlyMessageCache().store(targetAuthor.getId(), reactionx.getTargetSentTimestamp(), content);
      return null;
    }

    if (targetMessage.isRemoteDelete()) {
      Log.w(TAG,  "[handleReaction] Found a matching message, but it's flagged as remotely deleted. timestamp: " + reactionx.getTargetSentTimestamp() + "  author: " + targetAuthor.getId());
      return null;
    }

    ThreadRecord targetThread = SignalDatabase.threads().getThreadRecord(targetMessage.getThreadId());

    if (targetThread == null) {
      Log.w(TAG, "[handleReaction] Could not find a thread for the message! timestamp: " + reactionx.getTargetSentTimestamp() + "  author: " + targetAuthor.getId());
      return null;
    }

    Recipient threadRecipient = targetThread.getRecipient().resolve();

    if (threadRecipient.isGroup() && !threadRecipient.getParticipants().contains(reactionAuthor)) {
      Log.w(TAG, "[handleReaction] Reaction author is not in the group! timestamp: " + reactionx.getTargetSentTimestamp() + "  author: " + targetAuthor.getId());
      return null;
    }

    if (!threadRecipient.isGroup() && !reactionAuthor.equals(threadRecipient) && !reactionAuthor.isSelf()) {
      Log.w(TAG, "[handleReaction] Reaction author is not a part of the 1:1 thread! timestamp: " + reactionx.getTargetSentTimestamp() + "  author: " + targetAuthor.getId());
      return null;
    }

    MessageId targetMessageId = new MessageId(targetMessage.getId(), targetMessage.isMms());

    if (reactionx.getRemove()) {
      SignalDatabase.reactions().deleteReaction(targetMessageId,  reactionAuthor.getId());
      ApplicationDependencies.getMessageNotifier().updateNotification(context);
    } else {
      ReactionRecord reactionRecord = new ReactionRecord(reactionx.getEmoji(), reactionAuthor.getId(), message.getTimestamp(), System.currentTimeMillis(), reactionx.getFid());
      SignalDatabase.reactions().addReaction(targetMessageId, reactionRecord);
      ApplicationDependencies.getMessageNotifier().updateNotification(context, targetMessage.getThreadId(), false);
    }

    return null;
  }

  private static @Nullable
  MessageId processMessageCommandMessageRevoke(@NonNull Context context,
                                               @NonNull SignalServiceContent content,
                                               @NonNull SignalServiceEnvelope envelope,
                                               @NonNull SignalServiceDataMessage message)
  {
    MessageCommand     msgCommand = message.getUfsrvCommand().getMsgCommand();

    if (msgCommand.getHeader().hasArgsError()) {
      handleMessageRevokedErrors(msgCommand);
     return null;
    }

    RevokedMessageRecord revokedMessageRecord = msgCommand.getRevoked(0);

    if (revokedMessageRecord == null) {
      Log.e(TAG, String.format(Locale.getDefault(), "(args:'%d') processMessageCommandMessageRevoke: COULD NOT FIND REVOKED MESSAGE RECORD", msgCommand.getHeader().getArgs()));

      return null;
    }

    if (isCommandAccepted(msgCommand) || isCommandSydnced(msgCommand)) {
      if (revokedMessageRecord.getGid() > 0) {
        handleMessageRevoked(context, content, envelope, message, msgCommand);
        SignalDatabase.mms().updateMessageIdentifiersForUfsrv(msgCommand.getHeader().getWhenClient(), msgCommand.getHeader().getGid(), msgCommand.getHeader().getEid());

        return null;
      }
    } else if (isCommandRejected(msgCommand)) {
      Log.e(TAG, String.format(Locale.getDefault(), "() processMessageCommandMessageRevoke: MSG REJECTED BY SERVER"));

      return null;
    }

    Log.e(TAG, String.format(Locale.getDefault(),"(args:'%d') processMessageCommandMessageRevoke: COULD NOT PROCESS MSG", msgCommand.getHeader().getArgs()));

    return null;
  }

  //PORTING NOTE adapted from PushProcessMessageJob.handleRemoteDelete()
  private static @Nullable
  MessageId handleMessageRevoked(@NonNull Context context,
                                 @NonNull SignalServiceContent content,
                                 @NonNull SignalServiceEnvelope envelope,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull MessageCommand messageCommand) {
    RevokedMessageRecord revokedMessageRecord = messageCommand.getRevoked(0);
    final Recipient senderRecipient;

    if (revokedMessageRecord.hasAuthor()) {
      senderRecipient = UfsrvFenceUtils.recipientFromUfsrvId(revokedMessageRecord.getAuthor().toByteArray());
    } else {
      senderRecipient =  UfsrvFenceUtils.recipientFromUfsrvId(messageCommand.getOriginator().getUfsrvuid().toByteArray());
    }

//    MessageRecord targetMessage = SignalDatabase.mmsSms().getMessageByGid(revokedMessageRecord.getGid(), senderRecipient.getId());//alt implementation
    MessageRecord targetMessage = SignalDatabase.mmsSms().getMessageFor(revokedMessageRecord.getWhenSent(), senderRecipient.getId());

    if (targetMessage != null && RemoteDeleteUtil.isValidReceive(targetMessage, senderRecipient, messageCommand.getHeader().getWhen())) {
      MessageDatabase db = targetMessage.isMms() ? SignalDatabase.mms() : SignalDatabase.sms();
      db.markAsRemoteDelete(targetMessage.getId());
      ApplicationDependencies.getMessageNotifier().updateNotification(context, targetMessage.getThreadId(), false);
      return new MessageId(targetMessage.getId(), targetMessage.isMms());
    } else if (targetMessage == null) {
      Log.w(TAG, "[handleMessageRevoked] Could not find matching message! timestamp: " + revokedMessageRecord.getWhenSent() + "  author: " + senderRecipient.getId());
      ApplicationDependencies.getEarlyMessageCache().store(senderRecipient.getId(), revokedMessageRecord.getWhenSent(), content);
      return null;
    } else {
      Log.w(TAG, String.format(Locale.getDefault(), "[handleMessageRevoked] Invalid remote delete! deleteTime(server): %d, targetTime(client): %d, deleteAuthor: %s, targetAuthor: %s",
                                                                 messageCommand.getHeader().getWhen(), revokedMessageRecord.getWhenSent(), senderRecipient.getId(), targetMessage.getRecipient().getId()));
      return null;
    }

  }


  private static void
  handleMessageRevokedErrors(MessageCommand msgCommand)
  {
    switch (msgCommand.getHeader().getArgsError())  {
      case MessageCommand.Errors.OWNERSHIP_VALUE:
      case MessageCommand.Errors.MESSAGE_ALREADY_REVOKED_VALUE:
      case MessageCommand.Errors.MESSAGE_NOT_IN_STORAGE_VALUE:
      case MessageCommand.Errors.TIME_THRESHOLD_LAPSED_VALUE:

        Log.e(TAG, String.format(Locale.getDefault(),"(args_error:'%d') handleMessageRevokedErrors: REVOKED MESSAGE RESPONSE ERROR", msgCommand.getHeader().getArgsError()));

      default:
        Log.e(TAG, String.format(Locale.getDefault(),"(args_error:'%d') handleMessageRevokedErrors: REVOKED MESSAGE RESPONSE ERROR: UKNOWN EERROR ARG", msgCommand.getHeader().getArgsError()));

    }
  }

  //AA based off PushProcessMessageJob.notifyTypi...
  static private void notifyTypingStoppedFromIncomingMessage(@NonNull Context context, @NonNull Recipient conversationRecipient, @NonNull String sender, int device) {
    Recipient author   = Recipient.live(sender).get();
    long      threadId = SignalDatabase.threads().getOrCreateThreadIdFor(conversationRecipient);

    if (threadId > 0) {
      Log.d(TAG, "Typing stopped on thread " + threadId + " due to an incoming message.");
      ApplicationDependencies.getTypingStatusRepository().onTypingStopped(context, threadId, author, device, true);
    }
  }

  static public Optional<UfsrvCommandWire> inflateEncodedUfrsvCommand(@NonNull String ufsrvCommandEncoded)
  {
    UfsrvCommandWire ufsrvCommand = null;

    if (ufsrvCommandEncoded != null) {
      try {
        ufsrvCommand = UfsrvCommandWire.parseFrom(Base64.decode(ufsrvCommandEncoded));
        return Optional.ofNullable(ufsrvCommand);
      } catch (IOException e) {
        Log.d(TAG, e.getMessage());
        return Optional.empty();
      }
    }

    return Optional.empty();
  }

  static public UfsrvMessageIdentifier UfsrvMessageIdentifierFromEncoded(@NonNull String ufsrvCommandEncoded, long timestamp)
  {
    Optional<UfsrvCommandWire> ufrsvCommand = inflateEncodedUfrsvCommand(ufsrvCommandEncoded);
    if (ufrsvCommand.isPresent()) {
      return UfsrvMessageIdentifierFromUfsrvCommand(ufrsvCommand.get(), timestamp);
    }

    return null;
  }

  static public UfsrvMessageIdentifier UfsrvMessageIdentifierFromUfsrvCommand(@NonNull UfsrvCommandWire ufrsvCommand, long timestamp)
  {
      switch (ufrsvCommand.getUfsrvtype()) {
        case UFSRV_FENCE:
          return new UfsrvMessageUtils.UfsrvMessageIdentifier(UfsrvUid.EncodedfromSerialisedBytes(ufrsvCommand.getFenceCommand().getOriginator().getUfsrvuid().toByteArray()),
                                            ufrsvCommand.getHeader().getGid(),
                                            ufrsvCommand.getFenceCommand().getFences(0).getFid(),
                                            ufrsvCommand.getHeader().getEid(),
                                                              timestamp);

        case UFSRV_MESSAGE:
          return new UfsrvMessageUtils.UfsrvMessageIdentifier(UfsrvUidEncodedForOriginator(ufrsvCommand.getMsgCommand()),
                                                              ufrsvCommand.getHeader().getGid(),
                                                              ufrsvCommand.getMsgCommand().getFences(0).getFid(),
                                                              ufrsvCommand.getHeader().getEid(), timestamp);

      }

    return new UfsrvMessageUtils.UfsrvMessageIdentifier(UfsrvUid.UndefinedUfsrvUid, 0, 0,0,0);

  }

  static public List<UfsrvMessageIdentifier> UfsrvMessageIdentifierFromReceiptCommand(@NonNull ReceiptCommand receiptCommand)
  {
    com.google.common.base.Preconditions.checkArgument(receiptCommand.getEidCount() == receiptCommand.getTimestampCount());

    Iterator eids       = receiptCommand.getEidList().iterator();
    Iterator timestamps = receiptCommand.getTimestampList().iterator();

    List<UfsrvMessageIdentifier> ufsrvMessageIdentifiers = new LinkedList<>();

    while (eids.hasNext() && timestamps.hasNext()) {
      ufsrvMessageIdentifiers.add(new UfsrvMessageIdentifier(UfsrvUid.EncodedfromSerialisedBytes(receiptCommand.getUidOriginator().toByteArray()),
                                                             0,
                                                             receiptCommand.getFid(),
                                                             (Long)eids.next(),
                                                             (Long)timestamps.next()));
    }

    return ufsrvMessageIdentifiers;

  }

  static public class UfsrvMessageIdentifier implements Serializable
  {
    public final String uidOriginator;
    public final long   gid;
    public final long   fid;
    public final long   eid;
    public long         timestamp;//of time sent by originator also used as 'message id'

    public UfsrvMessageIdentifier(String uidOriginator, long gid, long fid, long eid, long timestamp) {
      this.uidOriginator  = uidOriginator;
      this.gid            = gid;
      this.eid            = eid;
      this.fid            = fid;
      this.timestamp      = timestamp;
    }

    public UfsrvMessageIdentifier(String tokenized)
    {
      if (TextUtils.isEmpty(tokenized)) {
        this.uidOriginator  = "0";
        this.gid            = 0;
        this.eid            = 0;
        this.fid            = 0;
        this.timestamp      = 0;
      } else {
        if (tokenized.contains(":")) {
          String[] output = tokenized.split(":");
          if (output.length != 5){
            throw new IllegalArgumentException(tokenized + " - invalid format!");
          } else {
            this.uidOriginator  = (output[0]);
            this.fid            = Long.valueOf(output[1]);
            this.eid            = Long.valueOf(output[2]);
            this.gid            = Long.valueOf(output[3]);
            this.timestamp      = Long.valueOf(output[4]);
          }
        } else {
          throw new IllegalArgumentException(tokenized + " - invalid serialised format!");
        }
      }

    }

    public void setTimestamp (long timestamp)
    {
      this.timestamp = timestamp;
    }

    public String toString () {
      return new String(uidOriginator + ":"
                                + fid + ":"
                                + eid + ":"
                                + gid + ":"
                                + timestamp);
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

  //AA+ replaces getValidateQuote in PushProcessMessageJob
  public static Optional<QuoteModel> getValidatedQuote(Context context, Optional<SignalServiceDataMessage.Quote> quote) {
    if (!quote.isPresent()) return Optional.empty();

    if (quote.get().getId() <= 0) {
      Log.w(TAG, "Received quote without an ID! Ignoring...");
      return Optional.empty();
    }

    if (quote.get().getAuthor() == null) {
      Log.w(TAG, "Received quote without an author! Ignoring...");
      return Optional.empty();
    }

    LiveRecipient     author  = Recipient.live(quote.get().getAuthor().getNumber().get());
    MessageRecord message = SignalDatabase.mmsSms().getMessageFor(quote.get().getId(), author.getId());

    if (message != null && !message.isRemoteDelete()) {
      Log.w(TAG, "Found matching message record...");

      List<Attachment> attachments = new LinkedList<>();
      List<Mention>    mentions    = new LinkedList<>();

      if (message.isMms()) {
        MmsMessageRecord mmsMessage = (MmsMessageRecord) message;

        mentions.addAll(SignalDatabase.mentions().getMentionsForMessage(mmsMessage.getId()));

        if (!mmsMessage.isViewOnce()) {
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

      return Optional.of(new QuoteModel(quote.get().getId(), author.getId(), message.getBody(), false, attachments, mentions));
    } else if (message != null) {
      Log.w(TAG, "Found the target for the quote, but it's flagged as remotely deleted.");
    }

    Log.w(TAG, "Didn't find matching message record...");
    return Optional.of(new QuoteModel(quote.get().getId(),
                                      author.getId(),
                                      quote.get().getText(),
                                      true,
                                      PointerAttachment.forPointers(quote.get().getAttachments()),
                                      getMentions(quote.get().getMentions())));
  }

  //AA+ PORTING NOTE: from jobs/PushProcessMessageJob.java for mentions
  public static Optional<List<Mention>> getMentions(Optional<List<SignalServiceDataMessage.Mention>> signalServiceMentions) {
    if (!signalServiceMentions.isPresent()) return Optional.empty();

    return Optional.of(getMentions(signalServiceMentions.get()));
  }

  private static @NonNull List<Mention> getMentions(@Nullable List<SignalServiceDataMessage.Mention> signalServiceMentions) {
    if (signalServiceMentions == null || signalServiceMentions.isEmpty()) {
      return Collections.emptyList();
    }

    List<Mention> mentions = new ArrayList<>(signalServiceMentions.size());

    for (SignalServiceDataMessage.Mention mention : signalServiceMentions) {
      mentions.add(new Mention(Recipient.externalPush(null, mention.getUfsrvUid().toString(), false).getId(), mention.getStart(), mention.getLength()));
//      mentions.add(new Mention(Recipient.externalPush(mention.getServiceId(), null, false).getId(), mention.getStart(), mention.getLength()));
    }

    return mentions;
  }
  //AA+ replaces getContacts(Optional<List<SharedContact>> sharedContacts) in PushProcessMessageJob.java
  public static Optional<List<Contact>> getContacts(Optional<List<SharedContact>> sharedContacts) {
    if (!sharedContacts.isPresent()) return Optional.empty();

    List<Contact> contacts = new ArrayList<>(sharedContacts.get().size());

    for (SharedContact sharedContact : sharedContacts.get()) {
      contacts.add(ContactModelMapper.remoteToLocal(sharedContact));
    }

    return Optional.of(contacts);
  }

  //AA PORTING NOTE: replaces PushProcessMessageJob.getLinkPreview()
  public static Optional<List<LinkPreview>> getLinkPreviews(Optional<List<SignalServicePreview>> previews, @NonNull String message) {
    if (!previews.isPresent() || previews.get().isEmpty()) return Optional.empty();

    List<LinkPreview>     linkPreviews  = new ArrayList<>(previews.get().size());
    LinkPreviewUtil.Links urlsInMessage = LinkPreviewUtil.findValidPreviewUrls(message);

    for (SignalServicePreview preview : previews.get()) {
      Optional<Attachment> thumbnail     = PointerAttachment.forPointer(preview.getImage());
      Optional<String>     url           = Optional.ofNullable(preview.getUrl());
      Optional<String>     title         = Optional.ofNullable(preview.getTitle());
      Optional<String>     description   = Optional.ofNullable(preview.getDescription());
      boolean              hasTitle      = !TextUtils.isEmpty(title.orElse(""));
      boolean              presentInBody = url.isPresent() && urlsInMessage.containsUrl(url.get());
      boolean              validDomain   = url.isPresent() && LinkPreviewUtil.isValidPreviewUrl(url.get());

      if (hasTitle  && presentInBody && validDomain) {
        LinkPreview linkPreview = new LinkPreview(url.get(), title.orElse(""), description.orElse(""), preview.getDate(), thumbnail);
        linkPreviews.add(linkPreview);
      } else {
        Log.w(TAG, String.format("Discarding an invalid link preview. hasTitle: %b presentInBody: %b validDomain: %b", hasTitle, presentInBody, validDomain));
      }
    }

    return Optional.of(linkPreviews);
  }

  //start re-adapted e2ee based off SignalServiceCipher.decrypt
  private static byte[] decrypt(Context context, SignalServiceEnvelope envelope, String source, byte[] ciphertext, @NonNull Optional<Long> smsMessageId)
  {
    SignalProtocolStore signalProtocolStore  = ApplicationDependencies.getProtocolStore().aci();

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

      PushTransportDetails transportDetails = new PushTransportDetails();
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
    MessageDatabase smsDatabase = SignalDatabase.sms();

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidVersionKeyExchange(insertResult.get().getMessageId());
        ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsInvalidVersionKeyExchange(smsMessageId.get());
    }
  }

  static private void handleCorruptMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    MessageDatabase smsDatabase = SignalDatabase.sms();

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsDecryptFailed(insertResult.get().getMessageId());
        ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsDecryptFailed(smsMessageId.get());
    }
  }

  static private void handleNoSessionMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    MessageDatabase smsDatabase = SignalDatabase.sms();

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsNoSession(insertResult.get().getMessageId());
        ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  static private void handleLegacyMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    MessageDatabase smsDatabase = SignalDatabase.sms();

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(context, envelope);

      if (insertResult.isPresent()) {
        smsDatabase.markAsLegacyVersion(insertResult.get().getMessageId());
        ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
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
//      ApplicationDependencies.getMessageNotifier().updateNotification(context, masterSecret, messageAndThreadId.second);
//    } else {
//      smsDatabase.markAsDecryptDuplicate(smsMessageId);
//    }
  }

  static private void handleUntrustedIdentityMessage(@NonNull Context context, @NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId)
  {
    try {
      MessageDatabase         database          = SignalDatabase.sms();
      byte[]                serialized      = envelope.hasLegacyMessage() ? envelope.getLegacyMessage() : envelope.getContent();
      PreKeySignalMessage   whisperMessage  = new PreKeySignalMessage(serialized);
      IdentityKey identityKey               = whisperMessage.getIdentityKey();
      String                encoded         = Base64.encodeBytes(serialized);

      LiveRecipient liveRecipientSource = Recipient.live(envelope.getSourceIdentifier());
      IncomingTextMessage   textMessage     = new IncomingTextMessage(liveRecipientSource.getId(),
                                                                     envelope.getSourceDevice(),
                                                                     envelope.getTimestamp(), envelope.getServerReceivedTimestamp(), envelope.getTimestamp(),
                                                                      encoded,
                                                                     Optional.empty(), 0, false, envelope.getServerGuid(),
                                                                     envelope.getUfsrvCommand());

      if (!smsMessageId.isPresent()) {
        IncomingPreKeyBundleMessage bundleMessage = new IncomingPreKeyBundleMessage(textMessage, encoded, envelope.hasLegacyMessage());
        Optional<InsertResult>      insertResult  = database.insertMessageInbox(bundleMessage);

        if (insertResult.isPresent()) {
          database.addMismatchedIdentity(insertResult.get().getMessageId(), liveRecipientSource.getId(), identityKey);
          ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
        }
      } else {
        database.updateBundleMessageBody(smsMessageId.get(), encoded);
        database.addMismatchedIdentity(smsMessageId.get(), liveRecipientSource.getId(), identityKey);
      }
    } catch (InvalidMessageException | InvalidVersionException | InvalidKeyException | LegacyMessageException e) {
      throw new AssertionError(e);
    }
  }

  static private Optional<InsertResult> insertPlaceholder(@NonNull Context context, @NonNull SignalServiceEnvelope envelope) {
    MessageDatabase         database    = SignalDatabase.sms();
    LiveRecipient       liveRecipientSource = Recipient.live(envelope.getSourceIdentifier());
    IncomingTextMessage   textMessage = new IncomingTextMessage(liveRecipientSource.getId(),
                                                                envelope.getSourceDevice(),
                                                                envelope.getTimestamp(), envelope.getServerReceivedTimestamp(),  envelope.getTimestamp(), "",
                                                                Optional.empty(), 0, false, envelope.getServerGuid(),
                                                                envelope.getUfsrvCommand());

    textMessage = new IncomingEncryptedMessage(textMessage, "");
    return database.insertMessageInbox(textMessage);
  }
  // end re-adapted e2ee

  static public Recipient recipientFromMessageCommandOriginator(MessageCommand messageCommand, boolean async)
  {
    return Recipient.live(new UfsrvUid(messageCommand.getOriginator().getUfsrvuid().toByteArray()).toString()).get();
  }

  static public Recipient recipientFromIntroCommandOriginator(MessageCommand messageCommand, boolean async)
  {
    return Recipient.live(messageCommand.getIntro().getOriginator()).get();
  }

  static public Recipient recipientGuardianFromGuardianCommand(MessageCommand messageCommand, boolean async)
  {
    return Recipient.live(new UfsrvUid(messageCommand.getGuardian().getGuardian().getUfsrvuid().toByteArray()).toString()).get();
  }

  static public Recipient recipientOriginatorFromGuardianCommand(MessageCommand messageCommand, boolean async)
  {
    return Recipient.live(new UfsrvUid(messageCommand.getGuardian().getOriginator().getUfsrvuid().toByteArray()).toString()).get();
  }

  static public Recipient recipientFromReportedMessageCommandOriginator(ReportedContentRecord reportedRecord, boolean async)
  {
    return Recipient.live(new UfsrvUid(reportedRecord.getOriginator().toByteArray()).toString()).get();
  }

  static public String UfsrvUidEncodedForOriginator(MessageCommand messageCommand)
  {
    return UfsrvUid.EncodedfromSerialisedBytes(messageCommand.getOriginator().getUfsrvuid().toByteArray());
  }

  public static MessageCommand.Builder
  buildMessageCommand(@NonNull Context context, long timestamp, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor, Optional<Consumer<CommandHeader.Builder>> headerBuilderExtra)
  {
    MessageCommand.Builder        messageCommandBuilder = MessageCommand.newBuilder();
    CommandHeader.Builder         commandHeaderBuilder  = CommandHeader.newBuilder();

    commandHeaderBuilder.setWhen(timestamp);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    if (headerBuilderExtra.isPresent()) headerBuilderExtra.get().accept(commandHeaderBuilder);
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

    return  messageCommandBuilder;
  }

  public static SignalServiceProtos.GroupContext.Builder createGroupContextFromUfsrv(GroupId groupId,
                                                                                     long fid,
                                                                                     SignalServiceProtos.GroupContext.Type Type)
  {

    byte[] id = groupId.getDecodedId();
    SignalServiceProtos.GroupContext.Builder builder = SignalServiceProtos.GroupContext.newBuilder()
                                                                                       .setId(ByteString.copyFrom(id))
                                                                                       .setType(Type)
                                                                                       .addAllMembers(Collections.emptyList());
    return builder;
  }

  static public boolean isCommandAccepted(MessageCommand msgCommand)
  {
    return (msgCommand.getHeader().getArgs() == CommandArgs.ACCEPTED.getNumber());
  }

  static public boolean isIntroUserResponseAccepted(IntroMessageRecord introMessageRecord)
  {
    return (introMessageRecord.getUserResponseType() == IntroMessageRecord.UserResponseType.ACCEPTED);
  }

  static public boolean isIntroUserResponseRejected(IntroMessageRecord introMessageRecord)
  {
    return (introMessageRecord.getUserResponseType() == IntroMessageRecord.UserResponseType.REJECTED);
  }

  static public boolean isIntroUserResponseIgnored(IntroMessageRecord introMessageRecord)
  {
    return (introMessageRecord.getUserResponseType() == IntroMessageRecord.UserResponseType.IGNORED);
  }

  static public boolean isCommandRejected(MessageCommand msgCommand)
  {
    return (msgCommand.getHeader().getArgs() == CommandArgs.REJECTED.getNumber());
  }

  static public boolean isCommandSydnced(MessageCommand msgCommand)
  {
    return (msgCommand.getHeader().getArgs() == CommandArgs.SYNCED.getNumber());
  }

  public static Pair<byte[], KeyStoreHelper.SealedData> createAndStoreGuardianRequestSecret(@NonNull Context context) {
    SecureRandom random = new SecureRandom();
    byte[]       secret = new byte[32];

    random.nextBytes(secret);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      KeyStoreHelper.SealedData encryptedSecret = KeyStoreHelper.seal(secret);
      return new Pair<>(secret, encryptedSecret);
    } else {
      //noop
    }

    return new Pair<>(secret, null);
  }
}
