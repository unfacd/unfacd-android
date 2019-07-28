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
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.logging.Log;

import com.unfacd.android.utils.UfsrvFenceUtils;

import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;


public class ReloadGroupJob extends BaseJob {
  private static final String TAG = ReloadGroupJob.class.getSimpleName();

  private static final String KEY_FID             = "fid";

  public static final String KEY = "ReloadGroupJob";

  private long fid;

  private ReloadGroupJob(@NonNull Job.Parameters parameters, long fid) {
    super(parameters);

    this.fid  = fid;
  }

  public ReloadGroupJob(long fid) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         fid);

  }

  @Override
  public @NonNull
  Data serialize() {
      return new Data.Builder().putLong(KEY_FID, fid).build();

  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException
  {
    Log.w("DeliveryReceiptJob", "Sending request fr group data reload...");
    sendReloadCommand();
  }

  @Override
  public void onCanceled() {
    Log.w(TAG, "Failed to send  after retry exhausted!");
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    Log.w(TAG, exception);
    if (exception instanceof NonSuccessfulResponseCodeException) return false;
    if (exception instanceof PushNetworkException)               return true;

    return false;
  }

  private void sendReloadCommand()
  {
    UfsrvFenceUtils.sendStateSyncForGroup(fid);
  }

  public static class Factory implements Job.Factory<ReloadGroupJob> {
    @Override
    public @NonNull ReloadGroupJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new ReloadGroupJob(parameters,  data.getLong(KEY_FID));
    }
  }
}
