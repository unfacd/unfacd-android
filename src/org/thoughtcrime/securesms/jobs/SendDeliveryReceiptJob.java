package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;

import com.unfacd.android.utils.UfsrvMessageUtils;
import com.unfacd.android.utils.UfsrvReceiptUtils;

import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SendDeliveryReceiptJob extends BaseJob {

  public static final String KEY = "SendDeliveryReceiptJob";

  private static final String KEY_ADDRESS    = "address";
  private static final String KEY_MESSAGE_ID = "message_id";
  private static final String KEY_TIMESTAMP  = "timestamp";
  private static final String KEY_UFSRV_MSG_IDS   = "usfrv_msg_ids";//

  private static final String TAG = SendReadReceiptJob.class.getSimpleName();

  private String address;
  private long   messageId;
  private long   timestamp;

  // todo: check SendReadReceiptJob.java
  private UfsrvMessageUtils.UfsrvMessageIdentifier ufsrvMessageIdentifier;

  public SendDeliveryReceiptJob(@NonNull Address address, long messageId, UfsrvMessageUtils.UfsrvMessageIdentifier ufsrvMessageIdentifier) {// Ufsrv
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                 .setMaxAttempts(Parameters.UNLIMITED)
                 .build(),
         address,
         messageId,
         System.currentTimeMillis(),
         ufsrvMessageIdentifier);//
  }

  private SendDeliveryReceiptJob(@NonNull Job.Parameters parameters,
                                 @NonNull Address address,
                                 long messageId,
                                 long timestamp,
                                 UfsrvMessageUtils.UfsrvMessageIdentifier ufsrvMessageIdentifier)
  {
    super(parameters);

    this.address   = address.serialize();
    this.messageId = messageId;
    this.timestamp = timestamp;
    this.ufsrvMessageIdentifier = ufsrvMessageIdentifier;//
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_ADDRESS, address)
            .putLong(KEY_MESSAGE_ID, messageId)
            .putLong(KEY_TIMESTAMP, timestamp)
            .putString(KEY_UFSRV_MSG_IDS, ufsrvMessageIdentifier.toString())//
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, UntrustedIdentityException {
    SignalServiceMessageSender  messageSender  = ApplicationDependencies.getSignalServiceMessageSender();
    SignalServiceAddress        remoteAddress  = new SignalServiceAddress(address);
    SignalServiceReceiptMessage receiptMessage = new SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.DELIVERY,
                                                                                 Collections.singletonList(messageId),
                                                                                 timestamp,
                                                                                 Collections.singletonList(ufsrvMessageIdentifier));
//                                                                                 new LinkedList<UfsrvMessageUtils.UfsrvMessageIdentifier>(){{add(ufsrvMessageIdentifier);}}); //

    messageSender.sendReceipt(remoteAddress,
                              UnidentifiedAccessUtil.getAccessFor(context, Recipient.from(context, Address.fromSerialized(address), false)),
                              receiptMessage,
                              UfsrvReceiptUtils.buildReadReceipt(context, timestamp, receiptMessage.getUfsrvMessageIdentifiers(), SignalServiceProtos.ReceiptCommand.CommandTypes.DELIVERY));//
  }

  @Override
  public boolean onShouldRetry(Exception e) {
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
    Log.w(TAG, "Failed to send delivery receipt to: " + address);
  }

  public static final class Factory implements Job.Factory<SendDeliveryReceiptJob> {
    @Override
    public @NonNull SendDeliveryReceiptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new SendDeliveryReceiptJob(parameters,
                                        Address.fromSerialized(data.getString(KEY_ADDRESS)),
                                        data.getLong(KEY_MESSAGE_ID),
                                        data.getLong(KEY_TIMESTAMP),
                                        new UfsrvMessageUtils.UfsrvMessageIdentifier(data.getString(KEY_UFSRV_MSG_IDS)));
    }
  }
}