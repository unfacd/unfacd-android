package org.thoughtcrime.securesms.jobs;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.utils.UfsrvCommandUtils;

import android.annotation.SuppressLint;
import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;

public abstract class PushReceivedJob extends BaseJob {

  private static final String TAG = PushReceivedJob.class.getSimpleName();

  public static final Object RECEIVE_LOCK = new Object();

  protected PushReceivedJob(Job.Parameters parameters) {
    super(parameters);
  }

  public void processEnvelope(@NonNull SignalServiceEnvelope envelope) {
    synchronized (RECEIVE_LOCK) {
      Address   source    = Address.fromSerialized(UfsrvCommandUtils.getOriginatorUserId(envelope.getUfsrvCommand()).toString());
      if (!source.isUndefined()) { //
        Recipient recipient = Recipient.from(context, source, false);

        if (!isActiveNumber(recipient)) {
          //
          ContactTokenDetails contactTokenDetails = new ContactTokenDetails();
//        contactTokenDetails.setUsername(UfsrvCommandUtils.getOriginatorUsername(envelope.getUfsrvCommand())); //phase it out
          contactTokenDetails.setUfsrvUid(UfsrvCommandUtils.getOriginatorUserId(envelope.getUfsrvCommand()).getUfsrvUidEncoded());
          //

          DatabaseFactory.getRecipientDatabase(context).setRegistered(recipient, RecipientDatabase.RegisteredState.REGISTERED, contactTokenDetails);// contactToken
          ApplicationContext.getInstance(context).getJobManager().add(new DirectoryRefreshJob(recipient, false));
        }
      }

      if (envelope.isReceipt()) {
        handleReceipt(envelope);
      } else if (envelope.isUfsrvMessage() || envelope.isPreKeySignalMessage() || envelope.isSignalMessage() || envelope.isUnidentifiedSender()) { // isUsfrvMessage
        handleMessage(envelope);
      } else {
        Log.w(TAG, "Received envelope of unknown type: " + envelope.getType());
      }
    }
  }

  private void handleMessage(SignalServiceEnvelope envelope) {
    new PushDecryptJob(context).processMessage(envelope);
  }

  @SuppressLint("DefaultLocale")
  private void handleReceipt(SignalServiceEnvelope envelope) {
    Log.i(TAG, String.format("Received receipt: (XXXXX, %d)", envelope.getTimestamp()));
    DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(new SyncMessageId(Address.fromExternal(context, envelope.getSource()),
                                                                                               envelope.getTimestamp()), System.currentTimeMillis());
  }

  private boolean isActiveNumber(@NonNull Recipient recipient) {
    return recipient.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED;
  }
}