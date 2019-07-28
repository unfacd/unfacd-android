package org.thoughtcrime.securesms.jobs;


import androidx.annotation.NonNull;
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.ufsrvuid.UfsrvUid;
import com.unfacd.android.utils.UfsrvMessageUtils;
import com.unfacd.android.utils.UfsrvReceiptUtils;

import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.crypto.UntrustedIdentityException;
import org.whispersystems.signalservice.api.messages.SignalServiceReceiptMessage;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SendReadReceiptJob extends BaseJob {

  private static final String TAG = SendReadReceiptJob.class.getSimpleName();

  public static final String KEY = "SendReadReceiptJob";

  private static final String KEY_ADDRESS     = "address";
  private static final String KEY_MESSAGE_IDS = "message_ids";
  private static final String KEY_TIMESTAMP   = "timestamp";
  private static final String KEY_UFSRV_MSG_IDS   = "usfrv_msg_ids";//

  private String     address;
  private List<Long> messageIds;
  private long       timestamp;

  //
  private  List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers;

  // address: original sender for whom this user is sending a 'read receipt' for a previously sent message by 'address'
  public SendReadReceiptJob(Address address, List<Long> messageIds, List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers) { //
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                 .setMaxAttempts(Parameters.UNLIMITED)
                 .build(),
         address,
         messageIds,
         System.currentTimeMillis(),
         ufsrvMessageIdentifiers);//
  }

  private SendReadReceiptJob(@NonNull Job.Parameters parameters,
                             @NonNull Address address,
                             @NonNull List<Long> messageIds,
                             long timestamp,
                             List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers) //
  {
    super(parameters);

    this.address    = address.serialize();
    this.messageIds = messageIds;
    this.timestamp  = timestamp;

    this.ufsrvMessageIdentifiers  = ufsrvMessageIdentifiers; //A++
  }

  @Override
  public @NonNull Data serialize() {
    long[] ids = new long[messageIds.size()];
    for (int i = 0; i < ids.length; i++) {
      ids[i] = messageIds.get(i);
    }

    //
    String[] encoded_ids = new String[ufsrvMessageIdentifiers.size()];
    for (int i = 0; i < encoded_ids.length; i++) {
      encoded_ids[i] = ufsrvMessageIdentifiers.get(i).toString();
    }//

    return new Data.Builder().putString(KEY_ADDRESS, address)
            .putLongArray(KEY_MESSAGE_IDS, ids)
            .putLong(KEY_TIMESTAMP, timestamp)
            .putStringArray(KEY_UFSRV_MSG_IDS,encoded_ids)//
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, UntrustedIdentityException {
    if (!TextSecurePreferences.isReadReceiptsEnabled(context) || messageIds.isEmpty()) return;

    //
    if (ufsrvMessageIdentifiers.get(0).uidOriginator.equals(UfsrvUid.UndefinedUfsrvUid)) {
      Log.i(TAG, "Ufsrv originated message: Not sending receipt...");
      return;
    }
    //

    SignalServiceMessageSender  messageSender  = ApplicationDependencies.getSignalServiceMessageSender();
    SignalServiceAddress        remoteAddress  = new SignalServiceAddress(address);
    SignalServiceReceiptMessage receiptMessage = new SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.READ, messageIds, timestamp,
                                                                                 ufsrvMessageIdentifiers);// last arg

    messageSender.sendReceipt(remoteAddress,
                              UnidentifiedAccessUtil.getAccessFor(context, Recipient.from(context, Address.fromSerialized(address), false)),
                              receiptMessage,
                              UfsrvReceiptUtils.buildReadReceipt(context, timestamp, receiptMessage.getUfsrvMessageIdentifiers(), SignalServiceProtos.ReceiptCommand.CommandTypes.READ));// uf
  }

  @Override
  public boolean onShouldRetry(Exception e) {
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onCanceled() {
    Log.w(TAG, "Failed to send read receipts to: " + address);
  }

  public static final class Factory implements Job.Factory<SendReadReceiptJob> {
    @Override
    public @NonNull SendReadReceiptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      Address    address    = Address.fromSerialized(data.getString(KEY_ADDRESS));
      long       timestamp  = data.getLong(KEY_TIMESTAMP);
      long[]     ids        = data.hasLongArray(KEY_MESSAGE_IDS) ? data.getLongArray(KEY_MESSAGE_IDS) : new long[0];
      List<Long> messageIds = new ArrayList<>(ids.length);

      for (long id : ids) {
        messageIds.add(id);
      }

      //
      String[] ufsrvIds = data.getStringArray(KEY_UFSRV_MSG_IDS);
      List<UfsrvMessageUtils.UfsrvMessageIdentifier> ufsrvMessageIdentifiers = new ArrayList<>(ufsrvIds.length);
      for (String encoded_id : ufsrvIds) {
        ufsrvMessageIdentifiers.add(new UfsrvMessageUtils.UfsrvMessageIdentifier(encoded_id));
      }
      //

      return new SendReadReceiptJob(parameters, address, messageIds, timestamp, ufsrvMessageIdentifiers);
    }
  }
}