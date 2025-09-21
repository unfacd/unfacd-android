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
import android.text.TextUtils;

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.data.json.JsonEntityProfile;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;

public class UfsrvProfileRetrieverJob extends BaseJob {
  private static final String TAG = Log.tag(RegistrationStatusVerifierJob.class);

  public static final String KEY = "UfsrvProfileRetrieverJob";
  private static final String KEY_UFSRVUID = "ufsrvuid";

  private final UfsrvUid ufsrvUid;

  private UfsrvProfileRetrieverJob (@NonNull Job.Parameters parameters, UfsrvUid ufsrvUid) {
    super(parameters);

    this.ufsrvUid = ufsrvUid;

  }

  public UfsrvProfileRetrieverJob (UfsrvUid ufsrvUid)
  {
    this(new Job.Parameters.Builder()
                 .setQueue("UfsrvProfileRetrieverJob")
                 .setMaxAttempts(5)
                 .build(), ufsrvUid);

  }

  public UfsrvProfileRetrieverJob ()
  {
    this(UfsrvUid.fromEncoded(TextSecurePreferences.getUfsrvUserId(ApplicationContext.getInstance())));

  }

  @Override
  public @NonNull
  Data serialize() {
    return new Data.Builder().putString(KEY_UFSRVUID, ufsrvUid.toString())
            .build();
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

    try {
      JsonEntityProfile profile = ApplicationDependencies.getSignalServiceAccountManager().getUfsrvProfile(ufsrvUid);
      Recipient recipient = Recipient.live(ufsrvUid.toString()).get();
      SignalDatabase.recipients().setUfsrvProfile(recipient.getId(), profile);
      if (!TextUtils.isEmpty(profile.getAvatar())) {
        byte[] profileKey = Base64.decode(profile.getProfileKey());
        ApplicationDependencies.getJobManager().add(new ProfileAvatarDownloadJob(ufsrvUid.toString(), profile.getAvatar(), profileKey));
      }
    } catch (NonSuccessfulResponseCodeException x) {
      Log.e(TAG, String.format("ERROR: Could not retrieve profile for '%s'", ufsrvUid.toString()));
    }
  }

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {}

  public static class Factory implements Job.Factory<UfsrvProfileRetrieverJob> {
    @Override
    public @NonNull UfsrvProfileRetrieverJob create(@NonNull Parameters parameters, @NonNull Data data) {
      UfsrvUid ufsrvUid = UfsrvUid.fromEncoded(data.getString(KEY_UFSRVUID));
      return new UfsrvProfileRetrieverJob(parameters, ufsrvUid);
    }
  }
}