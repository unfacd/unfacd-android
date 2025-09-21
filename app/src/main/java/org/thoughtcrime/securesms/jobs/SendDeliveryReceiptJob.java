package org.thoughtcrime.securesms.jobs;

import com.unfacd.android.utils.UfsrvMessageUtils;
import com.unfacd.android.utils.UfsrvReceiptUtils;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.model.MessageId;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.transport.UndeliverableMessageException;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.ContentHint;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SendMessageResult;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.api.push.exceptions.ServerRejectedException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.ReceiptCommand;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class SendDeliveryReceiptJob extends BaseJob {

  public static final String KEY = "SendDeliveryReceiptJob";

  private static final String KEY_RECIPIENT              = "recipient";
  private static final String KEY_MESSAGE_SENT_TIMESTAMP = "message_id";
  private static final String KEY_TIMESTAMP              = "timestamp";
  private static final String KEY_MESSAGE_ID             = "message_db_id";
  private static final String KEY_UFSRV_MSG_IDS          = "usfrv_msg_ids";//AA+

  private static final String TAG = Log.tag(SendReadReceiptJob.class);

  private final RecipientId recipientId;
  private final long        messageSentTimestamp;
  private final long        timestamp;

  //AA+ todo: check SendReadReceiptJob.java
  private UfsrvMessageUtils.UfsrvMessageIdentifier ufsrvMessageIdentifier;

  @Nullable
  private final MessageId messageId;

  public SendDeliveryReceiptJob(@NonNull RecipientId recipientId, long messageSentTimestamp, @NonNull MessageId messageId, UfsrvMessageUtils.UfsrvMessageIdentifier ufsrvMessageIdentifier) {//AA+ Ufsrv
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                 .setMaxAttempts(Parameters.UNLIMITED)
                 .setQueue(recipientId.toQueueKey())
                 .build(),
         recipientId,
         messageSentTimestamp,
         messageId,
         System.currentTimeMillis(),
         ufsrvMessageIdentifier);//AA+
  }

  private SendDeliveryReceiptJob(@NonNull Job.Parameters parameters,
                                 @NonNull RecipientId recipientId,
                                 long messageSentTimestamp,
                                 @Nullable MessageId messageId,
                                 long timestamp,
                                 UfsrvMessageUtils.UfsrvMessageIdentifier ufsrvMessageIdentifier)
  {
    super(parameters);

    this.recipientId          = recipientId;
    this.messageSentTimestamp = messageSentTimestamp;
    this.messageId            = messageId;
    this.timestamp            = timestamp;

    this.ufsrvMessageIdentifier = ufsrvMessageIdentifier;//AA+
  }

  @Override
  public @NonNull Data serialize() {
    Data.Builder builder = new Data.Builder().putString(KEY_RECIPIENT, recipientId.serialize())
            .putLong(KEY_MESSAGE_SENT_TIMESTAMP, messageSentTimestamp)
            .putLong(KEY_TIMESTAMP, timestamp)
            .putString(KEY_UFSRV_MSG_IDS, ufsrvMessageIdentifier.toString());//AA+

    if (messageId != null) {
      builder.putString(KEY_MESSAGE_ID, messageId.serialize());
    }

    return builder.build();
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

    SignalServiceMessageSender  messageSender  = ApplicationDependencies.getSignalServiceMessageSender();
    Recipient                   recipient      = Recipient.resolved(recipientId);

    if (recipient.isSelf()) {
      Log.i(TAG, "Not sending to self, abort");
      return;
    }

    if (recipient.isUnregistered()) {
      Log.w(TAG, recipient.getId() + " is unregistered!");
      return;
    }

    SignalServiceAddress        remoteAddress  = RecipientUtil.toSignalServiceAddress(context, recipient);
    SignalServiceReceiptMessage receiptMessage = new SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.DELIVERY,
                                                                                 Collections.singletonList(messageSentTimestamp),
                                                                                 timestamp,
                                                                                 Collections.singletonList(ufsrvMessageIdentifier));
//                                                                                 new LinkedList<UfsrvMessageUtils.UfsrvMessageIdentifier>(){{add(ufsrvMessageIdentifier);}}); //AA+

    SendMessageResult result = messageSender.sendReceipt(remoteAddress,
                                                         UnidentifiedAccessUtil.getAccessFor(context, recipient),
                                                         receiptMessage,
                                                         UfsrvReceiptUtils.buildReadReceipt(context, timestamp, receiptMessage.getUfsrvMessageIdentifiers(), ReceiptCommand.CommandTypes.DELIVERY));//AA+

    if (messageId != null) {
      SignalDatabase.messageLog().insertIfPossible(recipientId, timestamp, result, ContentHint.IMPLICIT, messageId);
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
    Log.w(TAG, "Failed to send delivery receipt to: " + recipientId);
  }

  public static final class Factory implements Job.Factory<SendDeliveryReceiptJob> {
    @Override
    public @NonNull SendDeliveryReceiptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      MessageId messageId = null;

      if (data.hasString(KEY_MESSAGE_ID)) {
        messageId = MessageId.deserialize(data.getString(KEY_MESSAGE_ID));
      }

      return new SendDeliveryReceiptJob(parameters,
                                        RecipientId.from(data.getString(KEY_RECIPIENT)),
                                        data.getLong(KEY_MESSAGE_SENT_TIMESTAMP),
                                        messageId,
                                        data.getLong(KEY_TIMESTAMP),
                                        new UfsrvMessageUtils.UfsrvMessageIdentifier(data.getString(KEY_UFSRV_MSG_IDS)));
    }
  }
}