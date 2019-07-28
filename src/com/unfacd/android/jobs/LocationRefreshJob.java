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

import androidx.annotation.NonNull;

import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.location.ufLocation;
import org.thoughtcrime.securesms.service.IncomingMessageObserver;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;


public class LocationRefreshJob extends BaseJob {

  private static final String TAG = LocationRefreshJob.class.getSimpleName();

  public static final String KEY = "PushContentReceiveJob";

  private SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();

  public LocationRefreshJob() {
  this(new Job.Parameters.Builder()
               .setQueue("LocationRefreshJob")
               .setMaxAttempts(5)
               .build());
  }

  private LocationRefreshJob(@NonNull Job.Parameters parameters) {
    super(parameters);
  }

  @Override
  public @NonNull Data serialize() {
    return Data.EMPTY;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws Exception {
      SignalServiceMessagePipe pipe = IncomingMessageObserver.getPipe();
      if (pipe.isConnected()) {
      accountManager.sendLocation(pipe, ufLocation.getInstance());
    }
  }

  @Override
  public void onCanceled ()
  {
    Log.w(TAG, ">>onCanceled: LocationRefresh failed after retry attempt exhaustion!");
  }

  @Override
  public boolean onShouldRetry(Exception throwable) {
    if (throwable instanceof NonSuccessfulResponseCodeException) return false;
    return true;
  }

  public static class Factory implements Job.Factory<LocationRefreshJob> {
    @Override
    public @NonNull LocationRefreshJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new LocationRefreshJob(parameters);
    }
  }
}
