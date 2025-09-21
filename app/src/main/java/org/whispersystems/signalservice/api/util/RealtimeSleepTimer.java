package org.whispersystems.signalservice.api.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;

import org.signal.core.util.PendingIntentFlags;
import org.signal.core.util.logging.Log;

import androidx.core.content.ContextCompat;

/**
 * A sleep timer that is based on elapsed realtime, so
 * that it works properly, even in low-power sleep modes.
 *
 */
public class RealtimeSleepTimer implements SleepTimer {
  private static final String TAG = Log.tag(RealtimeSleepTimer.class);

  private final AlarmReceiver alarmReceiver;
  private final Context context;

  public RealtimeSleepTimer(Context context) {
    this.context = context;
    alarmReceiver = new RealtimeSleepTimer.AlarmReceiver();
  }

  @Override
  public void sleep(long millis) {
    ContextCompat.registerReceiver(context, alarmReceiver,
                                   new IntentFilter(AlarmReceiver.WAKE_UP_THREAD_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);

    final long startTime = System.currentTimeMillis();
    alarmReceiver.setAlarm(millis);

    while (System.currentTimeMillis() - startTime < millis) {
      try {
        synchronized (this) {
          wait(millis - System.currentTimeMillis() + startTime);
        }
      } catch (InterruptedException e) {
        Log.w(TAG, e);
      }
    }

    context.unregisterReceiver(alarmReceiver);
  }

  private class AlarmReceiver extends BroadcastReceiver {
    private static final String WAKE_UP_THREAD_ACTION = "org.whispersystems.signalservice.api.util.RealtimeSleepTimer.AlarmReceiver.WAKE_UP_THREAD";

    private void setAlarm(long millis) {
      final Intent        intent        = new Intent(WAKE_UP_THREAD_ACTION);
      final PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntentFlags.mutable());
      final AlarmManager  alarmManager  = (AlarmManager)context.getSystemService(Context.ALARM_SERVICE);

      Log.w(TAG, "Setting alarm to wake up in " + millis + "ms.");

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                               SystemClock.elapsedRealtime() + millis,
                                               pendingIntent);
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
        alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                              SystemClock.elapsedRealtime() + millis,
                              pendingIntent);
      } else {
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                         SystemClock.elapsedRealtime() + millis,
                         pendingIntent);
      }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      Log.w(TAG, "Waking up.");

      synchronized (RealtimeSleepTimer.this) {
        RealtimeSleepTimer.this.notifyAll();
      }
    }
  }
}
