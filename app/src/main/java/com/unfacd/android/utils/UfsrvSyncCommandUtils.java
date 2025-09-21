package com.unfacd.android.utils;

import android.content.Context;

import com.annimon.stream.Stream;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.Mention;
import org.thoughtcrime.securesms.database.model.StoryType;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.mms.IncomingMediaMessage;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.QuoteModel;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.messages.SignalServiceContent;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.SyncCommand;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UfsrvCommandWire;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

import java.util.List;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class UfsrvSyncCommandUtils
{
  private static final String TAG = Log.tag(UfsrvSyncCommandUtils.class);

  public static @Nullable
  Long processUfsrvSyncCommand(@NonNull Context context,
                               @NonNull SignalServiceContent content,
                               @NonNull SignalServiceEnvelope envelope,
                               @NonNull SignalServiceDataMessage message,
                               @NonNull Optional<Long> smsMessageId,
                               boolean outgoing)
          throws MmsException
  {
    SyncCommand synCommand = message.getUfsrvCommand().getSyncCommand();
    if (synCommand == null) {
      Log.e(TAG, String.format("processUfsrvSyncCommand (%d): Command was null: RETURNING", Thread.currentThread().getId()));
      return Long.valueOf(-1L);
    }

    Recipient recipientOriginator = recipientFromSyncCommandOriginator(synCommand.getOriginator(), false);
    GroupManager.GroupActionResult result = SignalDatabase.threads().getGuardianThreadId(context, recipientOriginator.requireAddress());
    if (result.getThreadId() == -1) {
      Log.e(TAG, String.format("processUfsrvSyncCommand (address_originator:'%s', address_group:'%s'): NO CORRESPONDING THREAD ID FOUND FOR GUARDIAN", recipientOriginator.requireAddress(), result.getGroupRecipient().requireAddress()));
      return Long.valueOf(-1L);
    }

    UfsrvCommandWire commandWire = synCommand.getOriginatorCommand(0);
    switch (commandWire.getUfsrvtype()) {
      case UFSRV_MESSAGE:
        return processSyncCommandForMessage(context, envelope, message, result, outgoing);
      case UFSRV_FENCE:
      case UFSRV_CALL:
      default:
    }

    return (long) -1;
  }


  private static @Nullable
  Long processSyncCommandForMessage(@NonNull Context context,
                                    @NonNull SignalServiceEnvelope envelope,
                                    @NonNull SignalServiceDataMessage message,
                                    @NonNull GroupManager.GroupActionResult groupResult,
                                    boolean outgoing) throws MmsException
  {
    SyncCommand syncCommand = message.getUfsrvCommand().getSyncCommand();
    MessageCommand messageCommand = syncCommand.getOriginatorCommandList().get(0).getMsgCommand();

    String body = "";
    if (envelope.getType() == SignalServiceProtos.Envelope.Type.UNKNOWN_VALUE) {
      body = messageCommand.getMessagesCount() > 0 ? messageCommand.getMessages(0).getMessage().toStringUtf8() : "";
    } else {
      //e2ee
    }

    FenceRecord fenceRecord = messageCommand.getFences(0);
    //currently just triggers retrieval of fence descriptor if necessary
    Recipient recipientFence = Recipient.live(fenceRecord.getFid()).get();
    //todo check fence eid against internal recipient eid and refetch
    Recipient recipientOriginator = recipientFromSyncCommandOriginator(message.getUfsrvCommand().getSyncCommand().getOriginator(), false);

    Optional<QuoteModel> quote                 = UfsrvMessageUtils.getValidatedQuote(context, message.getQuote());
    Optional<List<Contact>> sharedContacts     = UfsrvMessageUtils.getContacts(message.getSharedContacts());
    Optional<List<LinkPreview>> linkPreviews   = UfsrvMessageUtils.getLinkPreviews(message.getPreviews(), body);
    Optional<Attachment>        sticker        = UfsrvMessageUtils.getStickerAttachment(context, message.getSticker());
    Optional<List<Mention>>     mentions       = UfsrvMessageUtils.getMentions(message.getMentions());

    Optional<MessageDatabase.InsertResult> insertResult;

    MessageDatabase database = SignalDatabase.mms();
    database.beginTransaction();

    try {
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(recipientOriginator.getId(),
                                                                   syncCommand.getHeader().getWhen(),
                                                                   envelope.getServerReceivedTimestamp(),
                                                                   envelope.getTimestamp(),
                                                                   StoryType.NONE,
                                                                   null,
                                                                   false,
                                                                   UfsrvCommandWire.UfsrvType.UFSRV_SYNC_VALUE, //AA notice reuse of field
                                                                   message.getExpiresInSeconds() * 1000,
                                                                   false,
                                                                   message.isViewOnce(),
                                                                   false,
                                                                   Optional.of(body),
                                                                   message.getGroupContext(),
                                                                   Optional.of(UfsrvCommandUtils.getAttachmentsList(envelope.getUfsrvCommand())),
                                                                   quote,
                                                                   sharedContacts,
                                                                   linkPreviews,
                                                                   mentions,
                                                                   sticker,
                                                                   envelope.getServerGuid(),
                                                                   messageCommand.getHeader().getGid(),//AA+
                                                                   messageCommand.getHeader().getEid(),
                                                                   messageCommand.getFences(0).getFid(),
                                                                   0,
                                                                   messageCommand.getHeader().getCommand(),
                                                                   messageCommand.getHeader().getArgs(),
                                                                   envelope.getUfsrvCommand());


      insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, groupResult.getThreadId());

      if (insertResult.isPresent()) {
        List<DatabaseAttachment> allAttachments = SignalDatabase.attachments().getAttachmentsForMessage(insertResult.get().getMessageId());
        List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
        List<DatabaseAttachment> attachments = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

        UfsrvMessageUtils.forceStickerDownloadIfNecessary(context, insertResult.get().getMessageId(), stickerAttachments);

        for (DatabaseAttachment attachment : attachments) {
          ApplicationDependencies.getJobManager().add(new AttachmentDownloadJob(insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
        }

//        if (smsMessageId.isPresent()) {
//          SignalDatabase.sms().deleteMessage(smsMessageId.get());
//        }

        database.setTransactionSuccessful();
      }
    } finally {
      database.endTransaction();
    }

    if (insertResult.isPresent()) {
      ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());

      return insertResult.get().getMessageId();
    }

    return null;
  }

  static public Recipient recipientFromSyncCommandOriginator (UserRecord userRecord, boolean async)
  {
    return Recipient.live(new UfsrvUid(userRecord.getUfsrvuid().toByteArray()).toString()).get();
  }
}
