package org.thoughtcrime.securesms.jobs;

import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.contacts.sync.ContactDiscovery;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DirectoryRefreshJob extends BaseJob {

  public static final String KEY = "DirectoryRefreshJob";

  private static final String TAG = Log.tag(DirectoryRefreshJob.class);

  private static final String KEY_RECIPIENT           = "recipient";
  private static final String KEY_NOTIFY_OF_NEW_USERS = "notify_of_new_users";
  private static final String KEY_IS_E164NUMBER       = "is_e164number";

  @Nullable private Recipient recipient;
  private boolean   notifyOfNewUsers;
  private boolean   isE164Number = false;//AA+

  public DirectoryRefreshJob(boolean notifyOfNewUsers) {
    this(null, notifyOfNewUsers, false);
  }

  public DirectoryRefreshJob(@Nullable Recipient recipient,
                             boolean notifyOfNewUsers,
                             boolean isE164Number)
  {
    this(new Job.Parameters.Builder()
                 .setQueue(StorageSyncJob.QUEUE_KEY)
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(10)
                 .build(),
         recipient,
         notifyOfNewUsers,
         isE164Number);
  }

  private DirectoryRefreshJob(@NonNull Job.Parameters parameters, @Nullable Recipient recipient, boolean notifyOfNewUsers, boolean isE164Number) {
    super(parameters);

    this.recipient        = recipient;
    this.notifyOfNewUsers = notifyOfNewUsers;
    this.isE164Number     = isE164Number;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_RECIPIENT, recipient != null ? recipient.getId().serialize() : null)
            .putBoolean(KEY_NOTIFY_OF_NEW_USERS, notifyOfNewUsers)
            .putBoolean(KEY_IS_E164NUMBER, isE164Number)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected boolean shouldTrace() {
    return true;
  }

  @Override
  public void onRun() throws IOException {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (recipient == null) {
      Log.i(TAG, "DirectoryRefreshJob.onRun() -> null recipient");
      ContactDiscovery.refreshAll(context, notifyOfNewUsers);
    } else if (isE164Number) {
      Log.i(TAG, String.format("DirectoryRefreshJob.onRun() -> e164: %s", recipient.requireUfsrvUid()));
      ContactDiscovery.refresh(context, recipient, notifyOfNewUsers);
    } else {
      Log.i(TAG, String.format("DirectoryRefreshJob.onRun() -> non-e164: %s", recipient.requireUfsrvUid()));
      ContactDiscovery.refreshWithContactDetails(LocallyAddressableUfsrvUid.from(recipient.getId(), UfsrvUid.fromEncoded(recipient.getUfsrvUid())));
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {}

  public static final class Factory implements Job.Factory<DirectoryRefreshJob> {

    @Override
    public @NonNull DirectoryRefreshJob create(@NonNull Parameters parameters, @NonNull Data data) {
      String serialized = data.getString(KEY_RECIPIENT);
      Recipient recipient = serialized != null ? Recipient.resolved(RecipientId.from(serialized)) : null;
      boolean notifyOfNewUsers = data.getBoolean(KEY_NOTIFY_OF_NEW_USERS);
      boolean isE164Number = false;
      try {
        isE164Number = data.getBoolean(KEY_IS_E164NUMBER);
      } catch (IllegalStateException x) {
        Log.d(TAG, x.getMessage());
      }
      return new DirectoryRefreshJob(parameters, recipient, notifyOfNewUsers, isE164Number);
    }
  }
}