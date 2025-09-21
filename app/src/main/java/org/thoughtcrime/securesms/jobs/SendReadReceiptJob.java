package org.thoughtcrime.securesms.jobs;


import android.app.Application;

import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvMessageUtils;
import com.unfacd.android.utils.UfsrvReceiptUtils;

import org.signal.core.util.ListUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.MessageDatabase.MarkedMessageInfo;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.JobManager;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

public class SendReadReceiptJob extends BaseJob {

  private static final String TAG = Log.tag(SendReadReceiptJob.class);

  static final int MAX_TIMESTAMPS = 500;

  public static final String KEY = "SendReadReceiptJob";

  private static final String KEY_THREAD                  = "thread";
  private static final String KEY_ADDRESS                 = "address";
  private static final String KEY_RECIPIENT               = "recipient";
  private static final String KEY_MESSAGE_SENT_TIMESTAMPS = "message_ids";
  private static final String KEY_MESSAGE_IDS             = "message_db_ids";
  private static final String KEY_TIMESTAMP               = "timestamp";
  private static final String KEY_UFSRV_MSG_IDS           = "usfrv_msg_ids";//AA+

  private final long            threadId;
  private final RecipientId     recipientId;
  private final List<Long>      messageSentTimestamps;
  private final long            timestamp;
  private final List<MessageId> messageIds;

  private  List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers;//AA+

  //AA address: original sender for whom this user is sending a 'read receipt' for a previously sent message by 'address'
  @VisibleForTesting
  public SendReadReceiptJob(long threadId, @NonNull RecipientId recipientId, List<Long> messageSentTimestamps, List<MessageId> messageIds, List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers) { //AA+
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                 .setMaxAttempts(Parameters.UNLIMITED)
                 .setQueue(recipientId.toQueueKey())
                 .build(),
         threadId,
         recipientId,
         ensureSize(messageSentTimestamps, MAX_TIMESTAMPS),
         ensureSize(messageIds, MAX_TIMESTAMPS),
         System.currentTimeMillis(),
         ufsrvMessageIdentifiers);//AA+
  }

  private SendReadReceiptJob(@NonNull Job.Parameters parameters,
                             long threadId,
                             @NonNull RecipientId recipientId,
                             @NonNull List<Long> messageSentTimestamps,
                             @NonNull List<MessageId> messageIds,
                             long timestamp,
                             List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers) //AA+
  {
    super(parameters);

    this.threadId              = threadId;
    this.recipientId           = recipientId;
    this.messageSentTimestamps = messageSentTimestamps;
    this.messageIds            = messageIds;
    this.timestamp             = timestamp;

    this.ufsrvMessageIdentifiers  = ufsrvMessageIdentifiers; //A++
  }

  /**
   * Enqueues all the necessary jobs for read receipts, ensuring that they're all within the
   * maximum size.
   */
  public static void enqueue(long threadId, @NonNull RecipientId recipientId, List<MarkedMessageInfo> markedMessageInfos, List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers)
  {//AA+
    if (recipientId.equals(Recipient.self().getId())) {
      return;
    }

    JobManager jobManager = ApplicationDependencies.getJobManager();
    List<List<MarkedMessageInfo>> messageIdChunks = ListUtil.chunk(markedMessageInfos, MAX_TIMESTAMPS);

    if (messageIdChunks.size() > 1) {
      Log.w(TAG, "Large receipt count! Had to break into multiple chunks. Total count: " + markedMessageInfos.size());
    }

    for (List<MarkedMessageInfo> chunk : messageIdChunks) {
      List<Long> sentTimestamps = chunk.stream().map(info -> info.getSyncMessageId().getTimetamp()).collect(Collectors.toList());
      List<MessageId> messageIds = chunk.stream().map(MarkedMessageInfo::getMessageId).collect(Collectors.toList());

      jobManager.add(new SendReadReceiptJob(threadId, recipientId, sentTimestamps, messageIds, ufsrvMessageIdentifiers));//AA+
    }
  }

  @Override
  public @NonNull Data serialize() {
      long[] sentTimestamps = new long[messageSentTimestamps.size()];
      for (int i = 0; i < sentTimestamps.length; i++) {
        sentTimestamps[i] = messageSentTimestamps.get(i);
      }

      List<String> serializedMessageIds = messageIds.stream().map(MessageId::serialize).collect(Collectors.toList());

    //AA+
    String[] encoded_ids = new String[ufsrvMessageIdentifiers.size()];
    for (int i = 0; i < encoded_ids.length; i++) {
      encoded_ids[i] = ufsrvMessageIdentifiers.get(i).toString();
    }//

    return new Data.Builder().putString(KEY_RECIPIENT, recipientId.serialize())
            .putLong(KEY_THREAD, threadId)
            .putLongArray(KEY_MESSAGE_SENT_TIMESTAMPS, sentTimestamps)
            .putStringListAsArray(KEY_MESSAGE_IDS, serializedMessageIds)
            .putLong(KEY_TIMESTAMP, timestamp)
            .putStringArray(KEY_UFSRV_MSG_IDS, encoded_ids)//AA+
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, UntrustedIdentityException, UndeliverableMessageException {
    //AA+
    if (recipientId.isUnknown()) {
      throw new IOException("Invalid RecipientId -1");
    }
    //

    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

      if (!TextSecurePreferences.isReadReceiptsEnabled(context) || messageSentTimestamps.isEmpty()) return;

    //AA+
    if (ufsrvMessageIdentifiers.get(0).uidOriginator.equals(UfsrvUid.UndefinedUfsrvUid)) {
      Log.i(TAG, "Ufsrv originated message: Not sending receipt...");
      return;
    }
    //

    //AA- currently disabled see https://github.com/signalapp/Signal-Android/commit/6933ca50a758848cec543fbc33093229d99ab44a
//    if (!RecipientUtil.isMessageRequestAccepted(context, threadId)) {
//      Log.w(TAG, "Refusing to send receipts to untrusted recipient");
//      return;
//    }

    Recipient recipient = Recipient.resolved(recipientId);

    if (recipient.isSelf()) {
      Log.i(TAG, "Not sending to self, aborting.");
    }

    if (recipient.isBlocked()) {
      Log.w(TAG, "Refusing to send receipts to blocked recipient");
      return;
    }

    if (recipient.isGroup()) {
      Log.w(TAG, "Refusing to send receipts to group");
      return;
    }

    if (recipient.isDistributionList()) {
      Log.w(TAG, "Refusing to send receipts to distribution list");
      return;
    }

    if (recipient.isUnregistered()) {
      Log.w(TAG, recipient.getId() + " not registered!");
      return;
    }

    SignalServiceMessageSender  messageSender  = ApplicationDependencies.getSignalServiceMessageSender();
    SignalServiceAddress        remoteAddress  = RecipientUtil.toSignalServiceAddress(context, recipient);
    SignalServiceReceiptMessage receiptMessage = new SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.READ, messageSentTimestamps, timestamp,
                                                                                 ufsrvMessageIdentifiers);//AA+ last arg

    SendMessageResult result = messageSender.sendReceipt(remoteAddress,
                                                         UnidentifiedAccessUtil.getAccessFor(context, Recipient.resolved(recipientId)),
                                                         receiptMessage,
                                                         UfsrvReceiptUtils.buildReadReceipt(context, timestamp, receiptMessage.getUfsrvMessageIdentifiers(), SignalServiceProtos.ReceiptCommand.CommandTypes.READ));//AA+ uf

    if (Util.hasItems(messageIds)) {
      SignalDatabase.messageLog().insertIfPossible(recipientId, timestamp, result, ContentHint.IMPLICIT, messageIds);
    }
  }

  @Override
  public boolean onShouldRetry(Exception e) {
    if (e instanceof ServerRejectedException) return false;
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to send read receipts to: " + recipientId);
  }

  static <E> List<E> ensureSize(@NonNull List<E> list, int maxSize) {
    if (list.size() > maxSize) {
      throw new IllegalArgumentException("Too large! Size: " + list.size() + ", maxSize: " + maxSize);
    }
    return list;
  }

  public static final class Factory implements Job.Factory<SendReadReceiptJob> {
    private final Application application;

    public Factory(@NonNull Application application) {
      this.application = application;
    }

    @Override
    public @NonNull SendReadReceiptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      long            timestamp      = data.getLong(KEY_TIMESTAMP);
      long[]          ids            = data.hasLongArray(KEY_MESSAGE_SENT_TIMESTAMPS) ? data.getLongArray(KEY_MESSAGE_SENT_TIMESTAMPS) : new long[0];
      List<Long>      sentTimestamps = new ArrayList<>(ids.length);
      List<String>    rawMessageIds  = data.hasStringArray(KEY_MESSAGE_IDS) ? data.getStringArrayAsList(KEY_MESSAGE_IDS) : Collections.emptyList();
      List<MessageId> messageIds     = rawMessageIds.stream().map(MessageId::deserialize).collect(Collectors.toList());
      long            threadId       = data.getLong(KEY_THREAD);
      RecipientId     recipientId    = data.hasString(KEY_RECIPIENT) ? RecipientId.from(data.getString(KEY_RECIPIENT))
                                                                     : Recipient.external(application, data.getString(KEY_ADDRESS)).getId();

      for (long id : ids) {
        sentTimestamps.add(id);
      }

      //AA+
      String[] ufsrvIds = data.getStringArray(KEY_UFSRV_MSG_IDS);
      List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers = new ArrayList<>(ufsrvIds.length);
      for (String encoded_id : ufsrvIds) {
        ufsrvMessageIdentifiers.add(new UfsrvMessageUtils.UfsrvMessageIdentifier(encoded_id));
      }
      //

      return new SendReadReceiptJob(parameters, threadId, recipientId, sentTimestamps, messageIds, timestamp, ufsrvMessageIdentifiers);
    }
  }
}