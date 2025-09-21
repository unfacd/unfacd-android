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

import org.thoughtcrime.securesms.crypto.UnidentifiedAccessUtil;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.signal.core.util.logging.Log;

import com.unfacd.android.location.UfsrvLocationUtils;
import com.unfacd.android.location.ufLocation;
import com.unfacd.android.ufsrvcmd.UfsrvCommand;
import com.unfacd.android.utils.UfsrvUserUtils;

import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.LocationCommand;


public class LocationRefreshJob extends BaseJob {

  private static final String TAG = Log.tag(LocationRefreshJob.class);

  public static final String KEY = "LocationRefreshJob";

  public LocationRefreshJob() {
  this(new Job.Parameters.Builder()
               .setQueue("LocationRefreshJob")
               .addConstraint(NetworkConstraint.KEY)
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
      if (ApplicationDependencies.getSignalWebSocket().isConnected()) {
        SignalServiceMessageSender messageSender = ApplicationDependencies.getSignalServiceMessageSender();
        SignalServiceAddress remoteAddress = new SignalServiceAddress(SignalStore.account().getAci(), UfsrvUserUtils.myOwnAddress().serialize());

        messageSender.sendLocation(remoteAddress,
                                   UnidentifiedAccessUtil.getAccessFor(context, UfsrvUserUtils.myOwnRecipient(false)),
                                   UfsrvLocationUtils.buildLocationCommand(context, ufLocation.getInstance(), System.currentTimeMillis(), LocationCommand.CommandTypes.LOCATION, UfsrvCommand.TransportType.LOCAL_PIPE));//AA+
      } else {
        Log.w(TAG, ">>> Websocket pipe not connected: returning...");
      }
  }

  @Override
  public void onFailure ()
  {
    Log.w(TAG, ">>onFailure: LocationRefresh failed after retry attempt exhaustion!");
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
