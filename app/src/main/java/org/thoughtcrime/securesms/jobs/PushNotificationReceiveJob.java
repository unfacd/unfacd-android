package org.thoughtcrime.securesms.jobs;

import android.content.Context;
import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.messages.BackgroundMessageRetriever;
import org.thoughtcrime.securesms.messages.RestStrategy;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;

public final class PushNotificationReceiveJob extends BaseJob {

  public static final String KEY                = "PushNotificationReceiveJob";
  private static final String KEY_FORCE_RUN     = "force_run";//AA+

  private static final String TAG = Log.tag(PushNotificationReceiveJob.class);

  private static final String KEY_FOREGROUND_SERVICE_DELAY = "foreground_delay";

  private final long foregroundServiceDelayMs;

  private final boolean         isForceRun; //AA+

  public PushNotificationReceiveJob() {
    this(BackgroundMessageRetriever.DO_NOT_SHOW_IN_FOREGROUND, false);//AA+ false
  }

  public PushNotificationReceiveJob(boolean isForceRun) {
    this(BackgroundMessageRetriever.DO_NOT_SHOW_IN_FOREGROUND, isForceRun);//AA+ i
  }

  private PushNotificationReceiveJob(long foregroundServiceDelayMs) {
    this(foregroundServiceDelayMs, false);
  }

  //AA++ force run
  public PushNotificationReceiveJob(long foregroundServiceDelayMs, boolean isForceRun) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setQueue("__notification_received")
                 .setMaxAttempts(3)
                 .setMaxInstancesForFactory(1)
                 .build(), foregroundServiceDelayMs, isForceRun);
  }

  private PushNotificationReceiveJob(@NonNull Job.Parameters parameters, long foregroundServiceDelayMs, boolean isForceRun) {
    super(parameters);
    this.foregroundServiceDelayMs = foregroundServiceDelayMs;
    this.isForceRun = isForceRun;//AA+
  }

  public static Job withDelayedForegroundService(long foregroundServiceAfterMs) {
    return new PushNotificationReceiveJob(foregroundServiceAfterMs);
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_FOREGROUND_SERVICE_DELAY, foregroundServiceDelayMs)
            .putBoolean(KEY_FORCE_RUN, isForceRun)
            .build();//AA++
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    BackgroundMessageRetriever retriever = ApplicationDependencies.getBackgroundMessageRetriever();
    boolean          result    = retriever.retrieveMessages(context, foregroundServiceDelayMs, isForceRun, new RestStrategy());

    if (result) {
      Log.i(TAG, "Successfully pulled messages.");
    } else {
      throw new PushNetworkException("Failed to pull messages.");
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    Log.w(TAG, e);
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "***** Failed to download pending message!");
//    MessageNotifier.notifyMessagesPending(getContext());
  }

  public static final class Factory implements Job.Factory<PushNotificationReceiveJob> {
    @Override
    public @NonNull PushNotificationReceiveJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new PushNotificationReceiveJob(parameters, data.getLongOrDefault(KEY_FOREGROUND_SERVICE_DELAY, BackgroundMessageRetriever.DO_NOT_SHOW_IN_FOREGROUND), data.getBoolean(KEY_FORCE_RUN));//AA+
    }
  }
}