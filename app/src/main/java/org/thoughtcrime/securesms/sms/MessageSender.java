/**
 * Copyright (C) 2011 Whisper Systems
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
package org.thoughtcrime.securesms.sms;

import android.content.Context;
import android.location.Location;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.annimon.stream.Stream;
import com.google.protobuf.ByteString;
import com.unfacd.android.fence.FencePermissions;
import com.unfacd.android.jobs.MessageCommandEndSessionJob;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvCallUtils;
import com.unfacd.android.utils.UfsrvCommandUtils;
import com.unfacd.android.utils.UfsrvMessageUtils;

import org.greenrobot.eventbus.EventBus;
import org.jetbrains.annotations.NotNull;
import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.groups.GroupMasterKey;
import org.thoughtcrime.securesms.attachments.Attachment;
import org.thoughtcrime.securesms.attachments.AttachmentId;
import org.thoughtcrime.securesms.attachments.DatabaseAttachment;
import org.thoughtcrime.securesms.contactshare.Contact;
import org.thoughtcrime.securesms.database.AttachmentDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase;
import org.thoughtcrime.securesms.database.MessageDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.MmsSmsDatabase;
import org.thoughtcrime.securesms.database.NoSuchMessageException;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.ReactionRecord;
import org.thoughtcrime.securesms.database.model.SmsMessageRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobs.AttachmentCompressionJob;
import org.thoughtcrime.securesms.jobs.AttachmentCopyJob;
import org.thoughtcrime.securesms.jobs.AttachmentMarkUploadedJob;
import org.thoughtcrime.securesms.jobs.AttachmentUploadJob;
import org.thoughtcrime.securesms.jobs.MmsSendJob;
import org.thoughtcrime.securesms.jobs.ProfileKeySendJob;
import org.thoughtcrime.securesms.jobs.PushDistributionListSendJob;
import org.thoughtcrime.securesms.jobs.PushGroupSendJob;
import org.thoughtcrime.securesms.jobs.PushMediaSendJob;
import org.thoughtcrime.securesms.jobs.PushTextSendJob;
import org.thoughtcrime.securesms.jobs.ReactionSendJob;
import org.thoughtcrime.securesms.jobs.RemoteDeleteSendJob;
import org.thoughtcrime.securesms.jobs.ResumableUploadSpecJob;
import org.thoughtcrime.securesms.jobs.SmsSendJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.linkpreview.LinkPreview;
import org.thoughtcrime.securesms.mms.MmsException;
import org.thoughtcrime.securesms.mms.OutgoingGroupUpdateMessage;
import org.thoughtcrime.securesms.mms.OutgoingMediaMessage;
import org.thoughtcrime.securesms.mms.OutgoingSecureMediaMessage;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.service.ExpiringMessageManager;
import org.thoughtcrime.securesms.util.ParcelUtil;
import org.thoughtcrime.securesms.util.SignalLocalMetrics;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment;
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.util.Preconditions;
import org.whispersystems.signalservice.internal.push.PushServiceSocket;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CallCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandHeader;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.FenceRecord;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.LocationRecord;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.MessageCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;
import static org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserRecord;

public class MessageSender {

  private static final String TAG = Log.tag(MessageSender.class);

  @WorkerThread
  public static void sendProfileKey(final Context context, final long threadId) {
    ApplicationDependencies.getJobManager().add(ProfileKeySendJob.create(context, threadId, false));
  }

  public static long send(final Context context,
                          final OutgoingTextMessage message,
                          final long threadId,
                          final boolean forceSms,
                          @Nullable final String metricId,
                          final SmsDatabase.InsertListener insertListener, boolean isIntegritySensitive)
  {
    Log.i(TAG, "Sending text message to " + message.getRecipient().getId() + ", thread: " + threadId);
    MessageDatabase database    = SignalDatabase.sms();
    Recipient   recipient   = message.getRecipient();
    boolean     keyExchange = message.isKeyExchange();

    long allocatedThreadId = SignalDatabase.threads().getOrCreateValidThreadId(recipient, threadId);
    long messageId         = database.insertMessageOutbox(allocatedThreadId,
                                                          applyUniversalExpireTimerIfNecessary(context, recipient, message, allocatedThreadId),
                                                          forceSms,
                                                          System.currentTimeMillis(),
                                                          insertListener);

    SignalLocalMetrics.IndividualMessageSend.onInsertedIntoDatabase(messageId, metricId);

    sendTextMessage(context, recipient, forceSms, keyExchange, messageId);
    onMessageSent();
    SignalDatabase.threads().update(threadId, true);

    return allocatedThreadId;
  }

  public static long send(@NotNull final Context context,
                          @NotNull final OutgoingMediaMessage message,
                          final long threadId,
                          final boolean forceSms,
                          @Nullable final String metricId,
                          @Nullable final SmsDatabase.InsertListener insertListener,
                          @NotNull final UfsrvCommand.TransportType transportType,
                          boolean isIntegritySensitive)//AA+
  {
    try {
      ThreadDatabase threadDatabase = SignalDatabase.threads();
      MessageDatabase database       = SignalDatabase.mms();

      long      allocatedThreadId = threadDatabase.getOrCreateValidThreadId(message.getRecipient(), threadId, message.getDistributionType());
      Recipient recipient         = message.getRecipient();
      long      messageId         = database.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, recipient, message, allocatedThreadId), allocatedThreadId, forceSms, insertListener);

      if (message.getRecipient().isGroup() && message.getAttachments().isEmpty() && message.getLinkPreviews().isEmpty() && message.getSharedContacts().isEmpty()) {
        SignalLocalMetrics.GroupMessageSend.onInsertedIntoDatabase(messageId, metricId);
      } else {
        SignalLocalMetrics.GroupMessageSend.cancel(metricId);
      }

      sendMediaMessage(context, recipient, forceSms, messageId, Collections.emptyList(), transportType, isIntegritySensitive);
      onMessageSent();
      threadDatabase.update(threadId, true);

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }

  //AA+ see send above
  public static long send(final Context context,
                          final OutgoingTextMessage message,
                          final long fid,
                          final long threadId,
                          final boolean forceSms,
                          final SmsDatabase.InsertListener insertListener)
  {
    Log.i(TAG, "Sending media message to " + message.getRecipient().getId() + ", thread: " + threadId);
    MessageDatabase database   = SignalDatabase.sms();
    Recipient       recipient  = message.getRecipient();

    long allocatedThreadId = SignalDatabase.threads().getOrCreateValidThreadId(recipient, threadId);
    long messageId = database.insertMessageOutbox(allocatedThreadId, applyUniversalExpireTimerIfNecessary(context, recipient, message, allocatedThreadId), forceSms, System.currentTimeMillis(), insertListener);

    JobManager jobManager = ApplicationDependencies.getJobManager();
    jobManager.add(new MessageCommandEndSessionJob(fid, messageId));

    return allocatedThreadId;
  }
  //

  public static long sendPushWithPreUploadedMedia(final Context context,
                                                  final OutgoingMediaMessage message,
                                                  final Collection<PreUploadResult> preUploadResults,
                                                  final long threadId,
                                                  final SmsDatabase.InsertListener insertListener)
  {
    Log.i(TAG, "Sending media message with pre-uploads to " + message.getRecipient().getId() + ", thread: " + threadId);
    Preconditions.checkArgument(message.getAttachments().isEmpty(), "If the media is pre-uploaded, there should be no attachments on the message.");

    try {
      ThreadDatabase     threadDatabase     = SignalDatabase.threads();
      MessageDatabase    mmsDatabase        = SignalDatabase.mms();
      AttachmentDatabase attachmentDatabase = SignalDatabase.attachments();

      long allocatedThreadId;

      if (threadId == -1) {
        allocatedThreadId = threadDatabase.getOrCreateThreadIdFor(message.getRecipient(), message.getDistributionType());
      } else {
        allocatedThreadId = threadId;
      }

      Recipient recipient = message.getRecipient();
      long      messageId = mmsDatabase.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, recipient, message, allocatedThreadId),
                                                            allocatedThreadId,
                                                            false,
                                                            insertListener);

      List<AttachmentId> attachmentIds = Stream.of(preUploadResults).map(PreUploadResult::getAttachmentId).toList();
      List<String>       jobIds        = Stream.of(preUploadResults).map(PreUploadResult::getJobIds).flatMap(Stream::of).toList();

      attachmentDatabase.updateMessageId(attachmentIds, messageId, message.getStoryType().isStory());

      sendMediaMessage(context, recipient, false, messageId, jobIds, UfsrvCommand.TransportType.API_SERVICE, false);
      onMessageSent();
      threadDatabase.update(threadId, true);

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }

  public static void sendMediaBroadcast(@NonNull Context context,
                                        @NonNull List<OutgoingSecureMediaMessage> messages,
                                        @NonNull Collection<PreUploadResult> preUploadResults,
                                        @NonNull List<OutgoingStoryMessage> storyMessages)
  {
    Log.i(TAG, "Sending media broadcast to " + Stream.of(messages).map(m -> m.getRecipient().getId()).toList());
    Preconditions.checkArgument(messages.size() > 0, "No messages!");
    Preconditions.checkArgument(Stream.of(messages).allMatch(m -> m.getAttachments().isEmpty()), "Messages can't have attachments! They should be pre-uploaded.");

    JobManager                 jobManager             = ApplicationDependencies.getJobManager();
    AttachmentDatabase         attachmentDatabase     = SignalDatabase.attachments();
    MessageDatabase            mmsDatabase            = SignalDatabase.mms();
    ThreadDatabase             threadDatabase         = SignalDatabase.threads();
    List<AttachmentId>         preUploadAttachmentIds = Stream.of(preUploadResults).map(PreUploadResult::getAttachmentId).toList();
    List<String>               preUploadJobIds        = Stream.of(preUploadResults).map(PreUploadResult::getJobIds).flatMap(Stream::of).toList();
    List<Long>                 messageIds             = new ArrayList<>(messages.size());
    List<String>               messageDependsOnIds    = new ArrayList<>(preUploadJobIds);

    mmsDatabase.beginTransaction();
    try {
      OutgoingSecureMediaMessage primaryMessage   = messages.get(0);
      long                       primaryThreadId  = threadDatabase.getOrCreateThreadIdFor(primaryMessage.getRecipient(), primaryMessage.getDistributionType());
      long                       primaryMessageId = mmsDatabase.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, primaryMessage.getRecipient(), primaryMessage, primaryThreadId),
                                                                                    primaryThreadId,
                                                                                    false,
                                                                                    null);

      attachmentDatabase.updateMessageId(preUploadAttachmentIds, primaryMessageId, primaryMessage.getStoryType().isStory());
      messageIds.add(primaryMessageId);

      List<DatabaseAttachment> preUploadAttachments = Stream.of(preUploadAttachmentIds)
                                                            .map(attachmentDatabase::getAttachment)
                                                            .toList();

      if (messages.size() > 0) {
        List<OutgoingSecureMediaMessage> secondaryMessages    = messages.subList(1, messages.size());
        List<List<AttachmentId>>         attachmentCopies     = new ArrayList<>();

        for (int i = 0; i < preUploadAttachmentIds.size(); i++) {
          attachmentCopies.add(new ArrayList<>(messages.size()));
        }

        for (OutgoingSecureMediaMessage secondaryMessage : secondaryMessages) {
          long               allocatedThreadId = threadDatabase.getOrCreateThreadIdFor(secondaryMessage.getRecipient(), secondaryMessage.getDistributionType());
          long               messageId         = mmsDatabase.insertMessageOutbox(applyUniversalExpireTimerIfNecessary(context, secondaryMessage.getRecipient(), secondaryMessage, allocatedThreadId),
                                                                                 allocatedThreadId,
                                                                                 false,
                                                                                 null);
          List<AttachmentId> attachmentIds     = new ArrayList<>(preUploadAttachmentIds.size());

          for (int i = 0; i < preUploadAttachments.size(); i++) {
            AttachmentId attachmentId = attachmentDatabase.insertAttachmentForPreUpload(preUploadAttachments.get(i)).getAttachmentId();
            attachmentCopies.get(i).add(attachmentId);
            attachmentIds.add(attachmentId);
          }

          attachmentDatabase.updateMessageId(attachmentIds, messageId, secondaryMessage.getStoryType().isStory());
          messageIds.add(messageId);
        }

        for (int i = 0; i < attachmentCopies.size(); i++) {
          Job copyJob = new AttachmentCopyJob(preUploadAttachmentIds.get(i), attachmentCopies.get(i));
          jobManager.add(copyJob, preUploadJobIds);
          messageDependsOnIds.add(copyJob.getId());
        }
      }

      for (final OutgoingStoryMessage storyMessage : storyMessages) {
        OutgoingSecureMediaMessage message = storyMessage.getOutgoingSecureMediaMessage();

        if (!message.getStoryType().isStory()) {
          throw new AssertionError("Only story messages can be sent via this method.");
        }

        long                         allocatedThreadId   = threadDatabase.getOrCreateThreadIdFor(message.getRecipient(), message.getDistributionType());
        long                         messageId           = mmsDatabase.insertMessageOutbox(storyMessage.getOutgoingSecureMediaMessage(), allocatedThreadId, false, null);
        Optional<DatabaseAttachment> preUploadAttachment = preUploadAttachments.stream()
                                                                               .filter(a -> a.getAttachmentId().equals(storyMessage.getPreUploadResult().getAttachmentId()))
                                                                               .findFirst();

        if (!preUploadAttachment.isPresent()) {
          Log.w(TAG, "Dropping story message without pre-upload attachment.");
          mmsDatabase.markAsSentFailed(messageId);
        } else {
          AttachmentId attachmentCopyId = attachmentDatabase.insertAttachmentForPreUpload(preUploadAttachment.get()).getAttachmentId();
          attachmentDatabase.updateMessageId(Collections.singletonList(attachmentCopyId), messageId, true);
          messageIds.add(messageId);
          messages.add(storyMessage.getOutgoingSecureMediaMessage());

          Job copyJob = new AttachmentCopyJob(storyMessage.getPreUploadResult().getAttachmentId(), Collections.singletonList(attachmentCopyId));
          jobManager.add(copyJob, preUploadJobIds);
          messageDependsOnIds.add(copyJob.getId());
        }
      }

      for (int i = 0; i < messageIds.size(); i++) {
        long                       messageId = messageIds.get(i);
        OutgoingSecureMediaMessage message   = messages.get(i);
        Recipient                  recipient = message.getRecipient();

        if (recipient.isDistributionList()) {
          List<RecipientId> members = SignalDatabase.distributionLists().getMembers(recipient.requireDistributionListId());
          SignalDatabase.storySends().insert(messageId, members, message.getSentTimeMillis(), message.getStoryType().isStoryWithReplies());
        }
      }

      onMessageSent();
      mmsDatabase.setTransactionSuccessful();
    } catch (MmsException e) {
      Log.w(TAG, "Failed to send messages.", e);
    } finally {
      mmsDatabase.endTransaction();
    }

    for (int i = 0; i < messageIds.size(); i++) {
      long      messageId = messageIds.get(i);
      Recipient recipient = messages.get(i).getRecipient();

      if (isLocalSelfSend(context, recipient, false)) {
        sendLocalMediaSelf(context, messageId);
      } else if (recipient.isPushGroup()) {
        jobManager.add(new PushGroupSendJob(messageId, recipient.getId(), null, true, UfsrvCommand.TransportType.API_SERVICE, false), messageDependsOnIds, recipient.getId().toQueueKey());//AA+ transport
      } else if (recipient.isDistributionList()) {
        jobManager.add(new PushDistributionListSendJob(messageId, recipient.getId(), true), messageDependsOnIds, recipient.getId().toQueueKey());
      } else {
        jobManager.add(new PushMediaSendJob(messageId, recipient, true, UfsrvCommand.TransportType.API_SERVICE, false), messageDependsOnIds, recipient.getId().toQueueKey());//AA+ transport*/
      }
    }
  }

  /**
   * @return A result if the attachment was enqueued, or null if it failed to enqueue or shouldn't
   *         be enqueued (like in the case of a local self-send).
   */
  public static @Nullable PreUploadResult preUploadPushAttachment(@NonNull Context context, @NonNull Attachment attachment, @Nullable Recipient recipient) {
    if (isLocalSelfSend(context, recipient, false)) {
      return null;
    }
    Log.i(TAG, "Pre-uploading attachment for " + (recipient != null ? recipient.getId() : "null"));

    try {
      AttachmentDatabase attachmentDatabase = SignalDatabase.attachments();
      DatabaseAttachment databaseAttachment = attachmentDatabase.insertAttachmentForPreUpload(attachment);

      Job compressionJob         = AttachmentCompressionJob.fromAttachment(databaseAttachment, false, -1);
      Job resumableUploadSpecJob = new ResumableUploadSpecJob();
      Job uploadJob              = new AttachmentUploadJob(databaseAttachment.getAttachmentId());

      ApplicationDependencies.getJobManager()
              .startChain(compressionJob)
              .then(resumableUploadSpecJob)
              .then(uploadJob)
              .enqueue();

      return new PreUploadResult(databaseAttachment.getAttachmentId(), Arrays.asList(compressionJob.getId(), resumableUploadSpecJob.getId(), uploadJob.getId()));
    } catch (MmsException e) {
      Log.w(TAG, "preUploadPushAttachment() - Failed to upload!", e);
      return null;
    }
  }

 /* public static void sendNewReaction(@NonNull Context context, long messageId, boolean isMms, @NonNull String emoji, long fid) {//AA+ fid
    MessageDatabase db       = isMms ? SignalDatabase.mms() : SignalDatabase.sms();
    ReactionRecord    reaction = new ReactionRecord(emoji, Recipient.self().getId(), System.currentTimeMillis(), System.currentTimeMillis(), fid);

    db.addReaction(messageId, reaction);

    try {
      ApplicationDependencies.getJobManager().add(ReactionSendJob.create(context, messageId, isMms, reaction, false));
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendNewReaction] Could not find message! Ignoring.");
    }
  }

  public static void sendReactionRemoval(@NonNull Context context, long messageId, boolean isMms, @NonNull ReactionRecord reaction) {
    MessageDatabase db = isMms ? SignalDatabase.mms() : SignalDatabase.sms();

    db.deleteReaction(messageId, reaction.getAuthor());

    try {
      ApplicationDependencies.getJobManager().add(ReactionSendJob.create(context, messageId, isMms, reaction, true));
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendReactionRemoval] Could not find message! Ignoring.");
    }
  }*/

  public static void sendNewReaction(@NonNull Context context, @NonNull MessageId messageId, @NonNull String emoji, long fid) {//AA+ fid
    ReactionRecord reaction = new ReactionRecord(emoji, Recipient.self().getId(), System.currentTimeMillis(), System.currentTimeMillis(), fid);//AA+
    SignalDatabase.reactions().addReaction(messageId, reaction);

    try {
      ApplicationDependencies.getJobManager().add(ReactionSendJob.create(context, messageId, reaction, false));
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendNewReaction] Could not find message! Ignoring.");
    }
  }

  public static void sendReactionRemoval(@NonNull Context context, @NonNull MessageId messageId, @NonNull ReactionRecord reaction) {
    SignalDatabase.reactions().deleteReaction(messageId, reaction.getAuthor());

    try {
      ApplicationDependencies.getJobManager().add(ReactionSendJob.create(context, messageId, reaction, true));
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendReactionRemoval] Could not find message! Ignoring.");
    }
  }

  public static void sendRemoteDelete(@NonNull Context context, long messageId, boolean isMms) {
    MessageDatabase db = isMms ? SignalDatabase.mms() : SignalDatabase.sms();
//    db.markAsRemoteDelete(messageId); //AA- marked upon receiving backend confirmation
    db.markAsSending(messageId);

    try {
      ApplicationDependencies.getJobManager().add(RemoteDeleteSendJob.create(context, messageId, isMms));
      onMessageSent();
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "[sendNewReaction] Could not find message! Ignoring.");
    }
  }

  public static void resendGroupMessage(Context context, MessageRecord messageRecord, RecipientId filterRecipientId) {
    if (!messageRecord.isMms()) throw new AssertionError("Not Group");
    sendGroupPush(context, messageRecord.getRecipient(), messageRecord.getId(), filterRecipientId, Collections.emptyList(), UfsrvCommand.TransportType.API_SERVICE, false);
    onMessageSent();
  }

  public static void resend(Context context, MessageRecord messageRecord) {
    long       messageId   = messageRecord.getId();
    boolean    forceSms    = messageRecord.isForcedSms();
    boolean    keyExchange = messageRecord.isKeyExchange();
    Recipient  recipient   = messageRecord.getRecipient();

    if (messageRecord.isMms()) {
      sendMediaMessage(context, recipient, forceSms, messageId, Collections.emptyList(), UfsrvCommand.TransportType.API_SERVICE, false);
    } else {
      sendTextMessage(context, recipient, forceSms, keyExchange, messageId);

      onMessageSent();
    }
  }

  public static void onMessageSent()
  {
    EventBus.getDefault().postSticky(MessageSentEvent.INSTANCE);
  }

  private static @NonNull OutgoingTextMessage applyUniversalExpireTimerIfNecessary(@NonNull Context context, @NonNull Recipient recipient, @NonNull OutgoingTextMessage outgoingTextMessage, long threadId) {
    if (outgoingTextMessage.getExpiresIn() == 0 && RecipientUtil.setAndSendUniversalExpireTimerIfNecessary(context, recipient, threadId)) {
      return outgoingTextMessage.withExpiry(TimeUnit.SECONDS.toMillis(SignalStore.settings().getUniversalExpireTimer()));
    }
    return outgoingTextMessage;
  }

  private static @NonNull OutgoingMediaMessage applyUniversalExpireTimerIfNecessary(@NonNull Context context, @NonNull Recipient recipient, @NonNull OutgoingMediaMessage outgoingMediaMessage, long threadId) {
    if (!outgoingMediaMessage.isExpirationUpdate() && outgoingMediaMessage.getExpiresIn() == 0 && RecipientUtil.setAndSendUniversalExpireTimerIfNecessary(context, recipient, threadId)) {
      return outgoingMediaMessage.withExpiry(TimeUnit.SECONDS.toMillis(SignalStore.settings().getUniversalExpireTimer()));
    }
    return outgoingMediaMessage;
  }

  private static void sendMediaMessage(Context context, Recipient recipient, boolean forceSms, long messageId, @NonNull Collection<String> uploadJobIds, UfsrvCommand.TransportType transportType, boolean isIntegritySensitive)
  {
    if (isLocalSelfSend(context, recipient, forceSms)) {
      Log.d(TAG, String.format("sendMediaMessage: selfSend message..."));
      sendLocalMediaSelf(context, messageId);
    } else if (recipient.isPushGroup()) {
      Log.d(TAG, String.format("sendMediaMessage: isGroupPushSend() true..."));
      sendGroupPush(context, recipient, messageId, null, uploadJobIds, transportType, isIntegritySensitive);//AA+
    } else if (recipient.isDistributionList()) {
      sendDistributionList(context, recipient, messageId, uploadJobIds);
    } else if (!forceSms && isPushMediaSend(context, recipient)) {
      Log.d(TAG, String.format("sendMediaMessage: isPushMediaSend() true..."));//mms
      sendMediaPush(context, recipient, messageId, uploadJobIds, transportType, isIntegritySensitive);//AA+
    } else {
      Log.d(TAG, String.format("sendMediaMessage: Sending sms on MsgId: "+messageId+"'"));//mms
      sendMms(context, messageId);
    }
  }

  private static void sendTextMessage(Context context, Recipient recipient,
                                      boolean forceSms, boolean keyExchange,
                                      long messageId)
  {
    if (isLocalSelfSend(context, recipient, forceSms)) {
      Log.d(TAG, String.format("sendTextMessage: Invoking sendSelfText()... MsgId: "+messageId+"'"));//mms
      sendLocalTextSelf(context, messageId);
    } else if (!forceSms && isPushTextSend(context, recipient, keyExchange)) {
      Log.d(TAG, String.format("sendTextMessage: isPushTextSend... MsgId: "+messageId+"'"));//mms
      sendTextPush(recipient, messageId);
    } else {
      Log.d(TAG, String.format("sendTextMessage: Invoking sendSms()... MsgId: "+messageId+"'"));//mms
      sendSms(recipient, messageId);
    }
  }

  private static void sendTextPush(Recipient recipient, long messageId) {
    ApplicationDependencies.getJobManager().add(new PushTextSendJob(messageId, recipient));
  }

  private static void sendMediaPush(Context context, Recipient recipient, long messageId, @NonNull Collection<String> uploadJobIds, UfsrvCommand.TransportType transportType, boolean isIntegritySensitive) {//AA+
    JobManager jobManager = ApplicationDependencies.getJobManager();

    if (uploadJobIds.size() > 0) {
      Job mediaSend = new PushMediaSendJob(messageId, recipient, true, transportType, isIntegritySensitive);
      jobManager.add(mediaSend, uploadJobIds);
    } else {
      PushMediaSendJob.enqueue(context, jobManager, messageId, recipient, transportType, isIntegritySensitive);
    }
  }

  private static void sendGroupPush(Context context, Recipient recipient, long messageId, RecipientId filterRecipientId, @NonNull Collection<String> uploadJobIds, UfsrvCommand.TransportType transportType, boolean isIntegritySensitive) {
    JobManager jobManager = ApplicationDependencies.getJobManager();

    if (uploadJobIds.size() > 0) {
      Job groupSend = new PushGroupSendJob(messageId, recipient.getId(), filterRecipientId, !uploadJobIds.isEmpty(), transportType, isIntegritySensitive);//AA+
      jobManager.add(groupSend, uploadJobIds, uploadJobIds.isEmpty() ? null : recipient.getId().toQueueKey());
    } else {
      PushGroupSendJob.enqueue(context, jobManager, messageId, recipient.getId(), filterRecipientId, transportType, isIntegritySensitive);//AA+
    }
  }

  public static
  FenceCommand.Builder buildFenceCommandJoinInvitationResponse(Context context, long fid, long timeNowInMillis, boolean accepted)
  {
   FenceCommand.Builder fenceCommandBuilder    = FenceCommand.newBuilder();
   CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();

    FenceRecord.Builder fenceBuilder  = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);


    //build/link header
    commandHeaderBuilder.setCommand(FenceCommand.CommandTypes.JOIN_VALUE);
    commandHeaderBuilder.setWhen(timeNowInMillis);

    if (accepted) commandHeaderBuilder.setArgs(CommandArgs.INVITED_VALUE);
    else  commandHeaderBuilder.setArgs(CommandArgs.REJECTED_VALUE);//SHOULD BE REJECTED

    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }

  private static
  FenceCommand.Builder buildFenceCommandForUserAcceptedJoinLinkJoinResponse(long fid, String linkjoin_nonce, long timeNowInMillis)
  {
    FenceCommand.Builder fenceCommandBuilder    = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();

    FenceRecord.Builder fenceBuilder  = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);
    fenceBuilder.setDescription(linkjoin_nonce);


    //build/link header
    commandHeaderBuilder.setCommand(FenceCommand.CommandTypes.JOIN_VALUE);
    commandHeaderBuilder.setWhen(timeNowInMillis);

    commandHeaderBuilder.setArgs(CommandArgs.LINK_BASED_VALUE);

    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }

  //This user, who's linkjoin request was accepted, is joining the group
  public static void
  sendFenceCommandJoinAcceptedLinkJoin(@NonNull Context context, @NonNull GroupId groupId, @NonNull GroupDatabase.GroupRecord groupRecord)
  {
    long      timeNowInMillis = System.currentTimeMillis();
    Recipient groupRecipient = Recipient.live(SignalDatabase.recipients().getOrInsertFromGroupId(groupId)).get();
    long      threadId = SignalDatabase.threads().getThreadIdFor(null, groupRecord.getFid());

    FenceCommand fenceCommand = MessageSender.buildFenceCommandForUserAcceptedJoinLinkJoinResponse(groupRecord.getFid(), groupRecord.getDescription(), timeNowInMillis).build();
    SignalServiceProtos.UfsrvCommandWire.Builder ufsrvCommandBuilder = SignalServiceProtos.UfsrvCommandWire.newBuilder()
                                                                                                           .setFenceCommand(fenceCommand)
                                                                                                           .setUfsrvtype(SignalServiceProtos.UfsrvCommandWire.UfsrvType.UFSRV_FENCE);
    SignalServiceProtos.GroupContext groupContext = SignalServiceProtos.GroupContext.newBuilder()
                                                                                    .setId(ByteString.copyFrom(groupId.getDecodedId()))
                                                                                    .setType(SignalServiceProtos.GroupContext.Type.UPDATE)
                                                                                    .setFenceMessage(fenceCommand)
                                                                                    .build();

    OutgoingGroupUpdateMessage outgoingMessage = new OutgoingGroupUpdateMessage(groupRecipient, groupContext, null, timeNowInMillis, 0, false, null, Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), ufsrvCommandBuilder.build());
    MessageSender.send(context, outgoingMessage, threadId, false, null, null, UfsrvCommand.TransportType.API_SERVICE, false);
  }

  public static
  FenceCommand.Builder buildFenceCommandForType(@Nullable Collection<Recipient> recipients, long fid, long timeNowInMillis, FenceCommand.CommandTypes commandType, int commandArg)
  {
    FenceCommand.Builder fenceCommandBuilder    = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();

    FenceRecord.Builder fenceBuilder  = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);

    Stream.of(recipients).forEach(r -> {
      UserRecord.Builder userRecordBuilder  = UserRecord.newBuilder();
      userRecordBuilder.setUfsrvuid(ByteString.copyFrom(r.getUfrsvUidRaw()));
      userRecordBuilder.setUsername("*");
      fenceBuilder.addInvitedMembers(userRecordBuilder.build());
    });

    fenceCommandBuilder.addFences(fenceBuilder.build());

    //build/link header
    commandHeaderBuilder.setCommand(commandType.getNumber());
    commandHeaderBuilder.setWhen(timeNowInMillis);

    commandHeaderBuilder.setArgs(commandArg);

    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    return fenceCommandBuilder;
  }
  //

  public static
  FenceCommand.Builder buildFenceCommandInvitationRevoked(Context context, long fid, long timeNowInMillis)
  {
    FenceCommand.Builder fenceCommandBuilder    = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();

    FenceRecord.Builder fenceBuilder  = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);


    //build/link header
    commandHeaderBuilder.setCommand(FenceCommand.CommandTypes.INVITE_DELETED_VALUE);
    commandHeaderBuilder.setWhen(timeNowInMillis);

    commandHeaderBuilder.setArgs(CommandArgs.SET_VALUE);

    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }

  //AA+
  public static
  FenceCommand.Builder buildFenceCommandGeoInvitationResponse(Context context, long fid, boolean accepted)
  {
    SignalServiceProtos.FenceCommand.Builder fenceCommandBuilder    = SignalServiceProtos.FenceCommand.newBuilder();
    SignalServiceProtos.CommandHeader.Builder commandHeaderBuilder  = SignalServiceProtos.CommandHeader.newBuilder();
    String fenceCname;

    SignalServiceProtos.FenceRecord.Builder fenceBuilder  = SignalServiceProtos.FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(System.currentTimeMillis());

    //build/link header
    commandHeaderBuilder.setCommand(SignalServiceProtos.FenceCommand.CommandTypes.JOIN_VALUE);

    if (accepted) commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.GEO_BASED_VALUE);
    else  commandHeaderBuilder.setArgs(SignalServiceProtos.CommandArgs.DENIED_VALUE);//SHOULD BE REJECTED

    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }
  //

  //AA+
  /**
   * Preassmble an outgoing FenceCommand for a request for group syncing. JOIN-syncing type request is typically when a fence does not have internal state recorded in the
   * client. STATE-syncing is for an alredy existing fence for which we are syncing its internal state, for example we were disconnected.
   *
   * @param context
   * @param fid
   * @param accepted
   * @param join_sync
   * @return
   */
  public static FenceCommand.Builder
  buildFenceCommandStateSyncedResponse(Context context, long fid, long timeNowInMillis, boolean accepted, boolean join_sync)
  {
    FenceCommand.Builder fenceCommandBuilder    = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();

    FenceRecord.Builder fenceBuilder  = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setCreated(timeNowInMillis);

    if (accepted) commandHeaderBuilder.setArgs(CommandArgs.SYNCED_VALUE);
    else          commandHeaderBuilder.setArgs(CommandArgs.REJECTED_VALUE);

    if (join_sync)  commandHeaderBuilder.setCommand(FenceCommand.CommandTypes.JOIN_VALUE);
    else            commandHeaderBuilder.setCommand(FenceCommand.CommandTypes.STATE_VALUE);

    commandHeaderBuilder.setWhen(timeNowInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }

  //

  //AA+
  /**
   * This is unbuilt command, although it has most of its sub components built
   *
   * @param recipientsInvited
   * @param fname
   * @param groupDescription
   * @param fid
   * @param avatar
   * @param privacyMode
   * @param expiryTimer
   * @param location
   * @param context
   * @return
   */
  public static FenceCommand.Builder
  buildFenceCommand(Optional<List<Recipient>> recipient, Optional<List<Recipient>> recipientsInvited, Optional<GroupMasterKey> groupMasterKey, @Nullable String fname, Optional<String> groupDescription, Optional<Long> fid, Optional<byte[]> avatar, @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor, Optional<FenceRecord.PrivacyMode> privacyMode, Optional<FenceRecord.DeliveryMode> deliveryMode, Optional<FenceRecord.JoinMode> joinMode, int memberLimit, int expiryTimer, Optional<ufLocation> location, Context context,
          @Nullable RecipientDatabase.GroupPermission[] groupPermissions,
          long timeSentInMillis)
{
    FenceCommand.Builder  fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder   fenceBuilder          = FenceRecord.newBuilder();
    String                fenceCname            = null;

    if (!fid.isPresent() || fid.get() == 0) {
      //only do this when new group is being created
      if (location.isPresent()) fenceCname = location.get().getBaseLocationPrefix() + fname;
      else {
        fenceCname = ufLocation.getInstance().getBaseLocationPrefix() + fname;
      }
      fenceBuilder.setCreated(System.currentTimeMillis());
    }

    if (groupMasterKey.isPresent()) {
      fenceBuilder.setFkey(ByteString.copyFrom(groupMasterKey.get().serialize()));
    }

    if (fenceCname != null) fenceBuilder.setCname(fenceCname);
    if (fname != null)      fenceBuilder.setFname(fname);
    if(groupDescription.isPresent()) {
      fenceBuilder.setDescription(groupDescription.get());
    }
    if (privacyMode.isPresent())  fenceBuilder.setPrivacyMode(privacyMode.get());
    if (deliveryMode.isPresent())  fenceBuilder.setDeliveryMode(deliveryMode.get());
    if (joinMode.isPresent())  fenceBuilder.setJoinMode(joinMode.get());

    if (memberLimit != -1)  fenceBuilder.setMaxmembers(memberLimit);

    fenceBuilder.setExpireTimer(expiryTimer);

    fenceBuilder.setFid(fid.orElse(Long.valueOf(0)));

    if (groupPermissions != null) {
      RecipientDatabase.GroupPermission groupPermission;

      groupPermission = groupPermissions[FencePermissions.PRESENTATION.getValue()-1];
      fenceBuilder.setPresentation(FenceRecord.Permission.newBuilder()
        .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
        .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission = groupPermissions[FencePermissions.MEMBERSHIP.getValue()-1];
      fenceBuilder.setMembership(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission = groupPermissions[FencePermissions.MESSAGING.getValue()-1];
      fenceBuilder.setMessaging(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission = groupPermissions[FencePermissions.ATTACHING.getValue()-1];
      fenceBuilder.setAttaching(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

      groupPermission = groupPermissions[FencePermissions.CALLING.getValue()-1];
      fenceBuilder.setCalling(FenceRecord.Permission.newBuilder()
         .setListSemantics(FenceRecord.Permission.ListSemantics.values()[groupPermission.getListSemantics().getValue()])
         .setType(FenceRecord.Permission.Type.values()[groupPermission.getPermission().getValue()]).build());

    }

    if (recipient.isPresent()) {
      fenceBuilder.addAllMembers(UfsrvCommandUtils.recipientsToUserRecordList(recipient.get()));
    }

    if (recipientsInvited.isPresent()) {
      fenceBuilder.addAllInvitedMembers(UfsrvCommandUtils.recipientsToUserRecordList(recipientsInvited.get()));
    }

    //only do it for new fences
    if (!fid.isPresent() || fid.get() == 0) {//location bloc
      Location myLoc;
      android.location.Address myAddr;
      String baseloc;
      if (location.isPresent()) {
        myLoc = location.get().getMyLocation();
        myAddr = location.get().getMyAddress();
        baseloc = location.get().getBaseLocationPrefix();
      } else {
        ufLocation uflocation = ufLocation.getInstance();
        myLoc = uflocation.getMyLocation();
        myAddr = uflocation.getMyAddress();
        baseloc = uflocation.getBaseLocationPrefix();
      }

      if (myLoc != null) {
        LocationRecord.Builder locationBuilder = LocationRecord.newBuilder();
        locationBuilder.setSource(LocationRecord.Source.BY_USER);

        locationBuilder.setLatitude(myLoc.getLatitude());
        locationBuilder.setLongitude(myLoc.getLongitude());

        locationBuilder.setBaseloc(baseloc);

        Log.d(TAG, String.format("buildFenceCommand: Baseloc: '%s'. fid:'%d'", baseloc, fid.isPresent() ? fid.get() : 0));

        if (myAddr != null) {
          locationBuilder.setAdminArea(!TextUtils.isEmpty(myAddr.getAdminArea())? myAddr.getAdminArea() : "");
          locationBuilder.setCountry(!TextUtils.isEmpty(myAddr.getCountryName())? myAddr.getCountryName() : "");
          locationBuilder.setLocality(!TextUtils.isEmpty(myAddr.getLocality())? myAddr.getLocality() : "");
        }

        fenceBuilder.setLocation(locationBuilder.build());
      }
    }

    //build/link header
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder;
  }


  public static FenceCommand
  buildFenceCommandInviteFinal(Context context,
                               @NonNull List<Recipient> recipientInvited,
                               @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                               long fid,
                               long timeSentInMillis)
{
    FenceCommand.Builder  fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder   fenceBuilder          = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);

    fenceBuilder.addAllInvitedMembers(UfsrvCommandUtils.recipientsToUserRecordList(recipientInvited));

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder.build();
  }

  static {

  }
  public static FenceCommand
  buildFenceCommandPermissionFinal(@NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                                   @NonNull Recipient recipient,
                                   long fid,
                                   FencePermissions fencePermission,
                                   long timeSentInMillis)
  {
    FenceCommand.Builder            fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder           commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder             fenceBuilder          = FenceRecord.newBuilder();
    FenceRecord.Permission.Builder  permissionBuilder     = FenceRecord.Permission.newBuilder();
    UserRecord.Builder              userRecordBuilder     = UserRecord.newBuilder();

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    userRecordBuilder.setUsername("0");

    permissionBuilder.addUsers(userRecordBuilder.build());

    fenceBuilder.setFid(fid);
    switch (fencePermission)
    {
      case PRESENTATION:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.PRESENTATION);
        permissionBuilder.setType(FenceRecord.Permission.Type.PRESENTATION);
        fenceBuilder.setPresentation(permissionBuilder.build());
        break;
      case MEMBERSHIP:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.MEMBERSHIP);
        permissionBuilder.setType(FenceRecord.Permission.Type.MEMBERSHIP);
        fenceBuilder.setMembership(permissionBuilder.build());
        break;
      case MESSAGING:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.MESSAGING);
        permissionBuilder.setType(FenceRecord.Permission.Type.MESSAGING);
        fenceBuilder.setMessaging(permissionBuilder.build());
        break;
      case ATTACHING:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.ATTACHING);
        permissionBuilder.setType(FenceRecord.Permission.Type.ATTACHING);
        fenceBuilder.setAttaching(permissionBuilder.build());
        break;
      case CALLING:
        fenceCommandBuilder.setType(FenceRecord.Permission.Type.CALLING);
        permissionBuilder.setType(FenceRecord.Permission.Type.CALLING);
        fenceBuilder.setCalling(permissionBuilder.build());
        break;
      default:
        return null;
    }

    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());

    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());
    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder.build();
  }

  public static FenceCommand
  buildFenceCommandMessageExpirayFinal(Context context,
                                       @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                                       long expiry_timer,
                                       long fid,
                                       long timeSentInMillis)
  {
    FenceCommand.Builder  fenceCommandBuilder   = FenceCommand.newBuilder();
    CommandHeader.Builder commandHeaderBuilder  = CommandHeader.newBuilder();
    FenceRecord.Builder   fenceBuilder          = FenceRecord.newBuilder();

    fenceBuilder.setFid(fid);
    fenceBuilder.setExpireTimer(expiry_timer);
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setCommand(commandArgDescriptor.getCommand());
    commandHeaderBuilder.setArgs(commandArgDescriptor.getArg());
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvFenceCommand());
    fenceCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceCommandBuilder.addFences(fenceBuilder.build());

    return fenceCommandBuilder.build();
  }


  /**
   * Primary interface for invoking fence join by client.
   *
   * @param context
   * @param recipient        user creating a new grou[ and specifying starting invite list
   * @param fname
   * @param groupDescription
   * @param fid              -1 server to allocate
   */
  public static FenceCommand
  buildFenceCommandFinal(Context context,
                         Optional<List<Recipient>> recipient,
                         Optional<List<Recipient>> recipientsInvited,
                         @NonNull String fname,
                         Optional<String> groupDescription,
                         Optional<Long> fid,
                         Optional<byte[]> avatar,
                         @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor,
                         Optional<FenceRecord.PrivacyMode> privacyMode,
                         Optional<ufLocation> location,
                         long timeSentInMillis)
{
  return buildFenceCommand(recipient, recipientsInvited, Optional.empty(), fname, groupDescription, fid, avatar, commandArgDescriptor, privacyMode, Optional.of(FenceRecord.DeliveryMode.MANY), Optional.of(FenceRecord.JoinMode.OPEN), -1, 0, location, context,
                           null, timeSentInMillis).build();

  }

  //AA+ singular msg
  public static ReceiptCommand.Builder
  buildReceiptCommand(Context context,
                      Optional<List<Recipient>> recipient,
                      long timeSentInMillis,
                      UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier,
                      ReceiptCommand.CommandTypes receiptType)
  {
    CommandHeader.Builder commandHeaderBuilder      = CommandHeader.newBuilder();
    ReceiptCommand.Builder receiptCommandBuilder    = ReceiptCommand.newBuilder();

    commandHeaderBuilder.setCommand(receiptType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvReceiptCommand());//todo: this has to be defined somewhere else
    receiptCommandBuilder.setHeader(commandHeaderBuilder.build());

    receiptCommandBuilder.setType(receiptType);
    receiptCommandBuilder.setFid(messageIdentifier.fid);
    receiptCommandBuilder.setUidOriginator(ByteString.copyFrom(Recipient.live(new UfsrvUid(messageIdentifier.uidOriginator).toString()).get().getUfrsvUidRaw()));

    receiptCommandBuilder.addAllEid(Arrays.asList(messageIdentifier.eid));
    receiptCommandBuilder.addAllTimestamp(Arrays.asList(messageIdentifier.timestamp));

    return receiptCommandBuilder;
  }

  //AA+ multi msg from the same originator
  public static ReceiptCommand.Builder
  buildReceiptCommand(Context context,
                      Optional<List<Recipient>> recipient,
                      long timeSentInMillis,
                      List<UfsrvMessageUtils.UfsrvMessageIdentifier> messageIdentifiers,
                      ReceiptCommand.CommandTypes receiptType)
  {

    if (messageIdentifiers == null) {
       Log.e(TAG, "buildReceiptCommand(multi): messageIdentifiers null: NOT SENDING RECEIPT");

      return null;
    }

    CommandHeader.Builder commandHeaderBuilder      = CommandHeader.newBuilder();
    ReceiptCommand.Builder receiptCommandBuilder    = ReceiptCommand.newBuilder();

    //build/link header
    commandHeaderBuilder.setCommand(receiptType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvReceiptCommand());//todo: this has to be defined somewhere else
    receiptCommandBuilder.setHeader(commandHeaderBuilder.build());
//
    receiptCommandBuilder.setType(receiptType);
    receiptCommandBuilder.setFid(messageIdentifiers.get(0).fid);
    receiptCommandBuilder.setUidOriginator(ByteString.copyFrom(Recipient.live(new UfsrvUid(messageIdentifiers.get(0).uidOriginator).toString()).get().getUfrsvUidRaw()));
    List<Long> eids = new LinkedList<>();
    for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier: messageIdentifiers) {
      eids.add(messageIdentifier.eid);
    }
    receiptCommandBuilder.addAllEid(eids);

    List<Long> msgids = new LinkedList<>();
    for (UfsrvMessageUtils.UfsrvMessageIdentifier messageIdentifier: messageIdentifiers) {
      msgids.add(messageIdentifier.timestamp);
    }
    receiptCommandBuilder.addAllTimestamp(msgids);

    return receiptCommandBuilder;
  }

  //AA+ returns a completely encapusalted type (e.g Message) command structure, which can be sents along as is or incorporated into a UfsrCommandWire
  public static MessageCommand
  buildMessageCommandFinal(Context context,
                           Optional<List<Recipient>> recipient,
                           String body,
                           Optional<List<SignalServiceAttachment>> attachments,
                           Optional<SignalServiceDataMessage.Quote> quote,
                           long timeSentInMillis,
                           int command,
                           long fid)
{
    return buildMessageCommand(context, recipient, body, attachments, quote, timeSentInMillis, command, fid, false).build();
  }

  //AA+
  public static MessageCommand.Builder
  buildMessageCommand(Context context,
                       Optional<List<Recipient>> recipient,
                       String body,
                       Optional<List<SignalServiceAttachment>> attachments,
                       Optional<SignalServiceDataMessage.Quote> quote,
                       long timeSentInMillis,
                       int command,
                       long fid,
                       boolean isE2ee)
  {

    CommandHeader.Builder commandHeaderBuilder                = SignalServiceProtos.CommandHeader.newBuilder();
    MessageCommand.Builder messageCommandBuilder              = SignalServiceProtos.MessageCommand.newBuilder();

    commandHeaderBuilder.setCommand(command);
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvMessageCommand());//todo: this has to be defined somewhere else
    messageCommandBuilder.setHeader(commandHeaderBuilder.build());

//    if (!isE2ee) {
//      SignalServiceProtos.MessageRecord.Builder messageRecordBuilder  = SignalServiceProtos.MessageRecord.newBuilder();
//      if (!TextUtils.isEmpty(body)) messageRecordBuilder.setMessage(ByteString.copyFromUtf8(body));
//      messageCommandBuilder.addMessages(messageRecordBuilder.build());
//    }

    if (fid > 0) {
      SignalServiceProtos.FenceRecord.Builder fenceBuilder = SignalServiceProtos.FenceRecord.newBuilder();
      fenceBuilder.setFid(fid);
      messageCommandBuilder.addFences(fenceBuilder.build());
    }

    SignalServiceProtos.UserRecord.Builder fromBuilder = SignalServiceProtos.UserRecord.newBuilder();
    fromBuilder.setUsername(SignalStore.account().getE164());
    fromBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context))));
    messageCommandBuilder.setOriginator(fromBuilder.build());

    //build/link to members
    if(recipient.isPresent()) {
      //this will be done for direct e2e encryption
     // messageCommandBuilder.addAllTo(recipientsToUserRecordList(recipient.get()));
    }

    if(attachments.isPresent()) {
      //we cannot process attachements from here, as we need to upload them first and obtain ids. so that gets done further downstream at
      //createMessageContent(SignalServiceDataMessage message, @NonNull  UfsrvCommand ufCommand) and included in DataMessage proto
    }

    return messageCommandBuilder;
  }

  //AA+
  //for established group
  public static CallCommand.Builder
  buildCallCommand(Context context, long timeSentInMillis, CallCommand.CommandTypes commandType, Recipient recipientGroup)
  {
    CommandHeader.Builder commandHeaderBuilder        = CommandHeader.newBuilder();
    CallCommand.Builder   callCommandBuilder          = CallCommand.newBuilder();
    FenceRecord.Builder   fenceRecordBuilder          = FenceRecord.newBuilder();
    UserRecord.Builder    userRecordBuilder           = UserRecord.newBuilder();
    UserRecord.Builder    userRecordToBuilder         = UserRecord.newBuilder();

    Recipient recipientCalled = UfsrvCallUtils.getCallableRecipient(recipientGroup.requireGroupId()).get();
    long fid = recipientGroup.getUfsrvId();

    commandHeaderBuilder.setCommand(commandType.getNumber());
    commandHeaderBuilder.setWhen(timeSentInMillis);
    commandHeaderBuilder.setUname(TextSecurePreferences.getUfsrvUsername(context));
    commandHeaderBuilder.setCid(Long.parseLong(TextSecurePreferences.getUfsrvCid(context)));
    commandHeaderBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(Recipient.self().requireUfsrvUid())));
    callCommandBuilder.setHeader(commandHeaderBuilder.build());

    fenceRecordBuilder.setFid(fid);
    callCommandBuilder.setFence(fenceRecordBuilder.build());
    Log.d(TAG, String.format(Locale.getDefault(), "buildCallCommand (type:'%d'): Building for fid:'%d'", commandType.getNumber(), fid));

    userRecordBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(Recipient.self().requireUfsrvUid())));
    userRecordBuilder.setUsername(SignalStore.account().getE164());
    callCommandBuilder.setOriginator(userRecordBuilder.build());

    userRecordToBuilder.setUfsrvuid(ByteString.copyFrom(recipientCalled.getUfrsvUidRaw()));
    userRecordToBuilder.setUsername(recipientCalled.getUfsrvUid());
    callCommandBuilder.addTo(userRecordToBuilder);

    return callCommandBuilder;
  }

  //not suported yet, similar to new group create with an invite
  public static CallCommand.Builder
  buildCallCommand(Context context, Recipient recipient, long timeSentInMillis, int command)
  {
    CommandHeader.Builder commandHeaderBuilder        = CommandHeader.newBuilder();
    CallCommand.Builder   callCommandBuilder          = CallCommand.newBuilder();
    FenceRecord.Builder   fenceRecordBuilder          = FenceRecord.newBuilder();
    UserRecord.Builder    userRecordOriginatorBuilder = UserRecord.newBuilder();
    UserRecord.Builder    userRecordToBuilder         = UserRecord.newBuilder();

    commandHeaderBuilder.setCommand(command);
    commandHeaderBuilder.setWhen(timeSentInMillis);
//    commandHeaderBuilder.setPath(PushServiceSocket.getUfsrvMessageCommand());//todo: this has to be defined somewhere else
    callCommandBuilder.setHeader(commandHeaderBuilder.build());

    callCommandBuilder.setFence(fenceRecordBuilder.build());

    userRecordOriginatorBuilder.setUfsrvuid(ByteString.copyFrom(UfsrvUid.DecodeUfsrvUid(TextSecurePreferences.getUfsrvUserId(context))));
    callCommandBuilder.setOriginator(userRecordOriginatorBuilder.build());

    userRecordToBuilder.setUfsrvuid(ByteString.copyFrom(recipient.getUfrsvUidRaw()));
    callCommandBuilder.addTo(userRecordToBuilder.build());

    return callCommandBuilder;
  }
//

  private static void sendDistributionList(Context context, Recipient recipient, long messageId, @NonNull Collection<String> uploadJobIds) {
    JobManager jobManager = ApplicationDependencies.getJobManager();

    if (uploadJobIds.size() > 0) {
      Job groupSend = new PushDistributionListSendJob(messageId, recipient.getId(), !uploadJobIds.isEmpty());
      jobManager.add(groupSend, uploadJobIds, uploadJobIds.isEmpty() ? null : recipient.getId().toQueueKey());
    } else {
      PushDistributionListSendJob.enqueue(context, jobManager, messageId, recipient.getId());
    }
  }

  private static void sendSms(Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    jobManager.add(new SmsSendJob(messageId, recipient));
  }

  private static void sendMms(Context context, long messageId) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    MmsSendJob.enqueue(context, jobManager, messageId);
  }

  private static boolean isPushTextSend(Context context, Recipient recipient, boolean keyExchange) {
    if (!SignalStore.account().isRegistered()) {
      return false;
    }

    if (keyExchange) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

  private static boolean isPushMediaSend(Context context, Recipient recipient) {
    if (!SignalStore.account().isRegistered()) {
      return false;
    }

    if (recipient.isGroup()) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

//  private static boolean isPushDestination(Context context, Recipient destination) {
//    if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED) {
//      return true;
//    } else if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.NOT_REGISTERED) {
//      return false;
//    } else {
//      try {
//        RecipientDatabase.RegisteredState state = ContactDiscovery.refresh(context, destination, false);
//        return state == RecipientDatabase.RegisteredState.REGISTERED;
//      } catch (IOException e1) {
//        Log.w(TAG, e1);
//        return false;
//      }
//    }
//  }

  private static boolean isPushDestination(Context context, Recipient destination) {
    if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED) {
      return true;
    } else if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.NOT_REGISTERED) {
      return false;
    } else {
      try {
        SignalServiceAccountManager   accountManager =  ApplicationDependencies.getSignalServiceAccountManager();
        Optional<ContactTokenDetails> registeredUser = accountManager.getContact(destination.getUfsrvUid());

        if (!registeredUser.isPresent()) {
          SignalDatabase.recipients().setRegistered(destination.getId(), RecipientDatabase.RegisteredState.NOT_REGISTERED);
          return false;
        } else {
          SignalDatabase.recipients().setRegistered(destination.getId(), RecipientDatabase.RegisteredState.REGISTERED);
          return true;
        }
      } catch (IOException e1) {
        Log.w(TAG, e1);
        return false;
      }
    }
  }

  public static boolean isLocalSelfSend(@NonNull Context context, @Nullable Recipient recipient, boolean forceSms) {
    return recipient != null                     &&
            recipient.isSelf()                   &&
            !forceSms                            &&
            SignalStore.account().isRegistered() &&
            !TextSecurePreferences.isMultiDevice(context);
  }

  private static void sendLocalMediaSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager  = ApplicationDependencies.getExpiringMessageManager();
      MessageDatabase        mmsDatabase        = SignalDatabase.mms();
      MmsSmsDatabase         mmsSmsDatabase     = SignalDatabase.mmsSms();
      OutgoingMediaMessage   message            = mmsDatabase.getOutgoingMessage(messageId);
      SyncMessageId          syncId             = new SyncMessageId(Recipient.self().getId(), message.getSentTimeMillis());
      List<Attachment>       attachments        = new LinkedList<>();


      attachments.addAll(message.getAttachments());

      attachments.addAll(Stream.of(message.getLinkPreviews())
                                 .map(LinkPreview::getThumbnail)
                                 .filter(Optional::isPresent)
                                 .map(Optional::get)
                                 .toList());

      attachments.addAll(Stream.of(message.getSharedContacts())
                                 .map(Contact::getAvatar).withoutNulls()
                                 .map(Contact.Avatar::getAttachment).withoutNulls()
                                 .toList());

      List<AttachmentCompressionJob> compressionJobs = Stream.of(attachments)
              .map(a -> AttachmentCompressionJob.fromAttachment((DatabaseAttachment) a, false, -1))
              .toList();

      List<AttachmentMarkUploadedJob> fakeUploadJobs = Stream.of(attachments)
              .map(a -> new AttachmentMarkUploadedJob(messageId, ((DatabaseAttachment) a).getAttachmentId()))
              .toList();

      ApplicationDependencies.getJobManager().startChain(compressionJobs)
              .then(fakeUploadJobs)
              .enqueue();

      mmsDatabase.markAsSent(messageId, true);
      mmsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementViewedReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        mmsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }
    } catch (NoSuchMessageException | MmsException e) {
      Log.w(TAG, "Failed to update self-sent message.", e);
    }
  }

  private static void sendLocalTextSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager = ApplicationDependencies.getExpiringMessageManager();
      MessageDatabase        smsDatabase       = SignalDatabase.sms();
      MmsSmsDatabase         mmsSmsDatabase    = SignalDatabase.mmsSms();
      SmsMessageRecord       message           = smsDatabase.getSmsMessage(messageId);
      SyncMessageId          syncId            = new SyncMessageId(Recipient.self().getId(), message.getDateSent());

      smsDatabase.markAsSent(messageId, true);
      smsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0) {
        smsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(message.getId(), message.isMms(), message.getExpiresIn());
      }
    } catch (NoSuchMessageException e) {
      Log.w(TAG, "Failed to update self-sent message.", e);
    }
  }

  public static class PreUploadResult implements Parcelable {
    private final AttachmentId       attachmentId;
    private final Collection<String> jobIds;

    PreUploadResult(@NonNull AttachmentId attachmentId, @NonNull Collection<String> jobIds) {
      this.attachmentId = attachmentId;
      this.jobIds       = jobIds;
    }

    private PreUploadResult(Parcel in) {
      this.attachmentId = in.readParcelable(AttachmentId.class.getClassLoader());
      this.jobIds       = ParcelUtil.readStringCollection(in);
    }

    public @NonNull AttachmentId getAttachmentId() {
      return attachmentId;
    }

    public @NonNull Collection<String> getJobIds() {
      return jobIds;
    }

    public static final Creator<PreUploadResult> CREATOR = new Parcelable.Creator<PreUploadResult>() {
      @Override
      public PreUploadResult createFromParcel(Parcel in) {
        return new PreUploadResult(in);
      }

      @Override
      public PreUploadResult[] newArray(int size) {
        return new PreUploadResult[size];
      }
    };

    @Override
    public int describeContents() {
      return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
      dest.writeParcelable(attachmentId, flags);
      ParcelUtil.writeStringCollection(dest, jobIds);
    }
  }

  public enum MessageSentEvent {
    INSTANCE
  }
}
