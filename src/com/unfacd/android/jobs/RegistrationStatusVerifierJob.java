/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.jobs;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.TextUtils;

import com.unfacd.android.ApplicationContext;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobs.BaseJob;
//import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;

public class RegistrationStatusVerifierJob extends BaseJob {
  private static final String TAG = RegistrationStatusVerifierJob.class.getSimpleName();

  public static final String KEY = "RegistrationStatusVerifierJob";

  private static final String KEY_REGO_COOKIE = "rego_cookie";
  public static final String REGO_STATUS_VERIFIED_ACTION = "com.unfacd.android.rego_status_verified";
  public static final String REGO_STATUS_VERIFICATION_CODE_EXTRA = "com.unfacd.android.verification_code";

  private RegistrationStatusVerifierJob (@NonNull Job.Parameters parameters) {
    super(parameters);

  }

  public RegistrationStatusVerifierJob ()
  {
    this(new Job.Parameters.Builder()
                 .setQueue("RegistrationStatusVerifierJob")
                 .setMaxAttempts(5)
                 .build());

  }

  @Override
  public @NonNull
  Data serialize() {
    return Data.EMPTY;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    PowerManager          powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    PowerManager.WakeLock wakeLock     = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

    try {
      wakeLock.acquire();
      @Nullable String     registrationCookie  = TextSecurePreferences.getUfsrvPendingCookie(context);
      if (!TextUtils.isEmpty(registrationCookie)) {
        Optional<String> verificationCode = ApplicationDependencies.getSignalServiceAccountManager().isRegistrationStatusVerified(registrationCookie);
        if (verificationCode.isPresent()) {
          Intent broadcastIntent = new Intent(REGO_STATUS_VERIFIED_ACTION);
          broadcastIntent.putExtra(REGO_STATUS_VERIFICATION_CODE_EXTRA, verificationCode.get());
          ApplicationContext.getInstance().sendBroadcast(broadcastIntent);
        } else {
          //nothing
        }
      }
    } finally {
      if (wakeLock.isHeld()) wakeLock.release();
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onCanceled() {}

  public static class Factory implements Job.Factory<RegistrationStatusVerifierJob> {
    @Override
    public @NonNull RegistrationStatusVerifierJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new RegistrationStatusVerifierJob(parameters);
    }
  }
}