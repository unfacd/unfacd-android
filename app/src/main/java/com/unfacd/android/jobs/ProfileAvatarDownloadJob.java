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

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.ufsrvcmd.events.AppEventAvatarDownloaded;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.signal.core.util.logging.Log;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.util.Base64;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class ProfileAvatarDownloadJob extends BaseJob {

  private static final String TAG = Log.tag(ProfileAvatarDownloadJob.class);

  public static final String KEY = "ProfileAvatarDownloadJob";

  private static final String KEY_UID               = "uid";
  private static final String KEY_AVATAR_ID         = "avatar_id";
  private static final String KEY_KEY               = "key";

  public static final int MAX_PROFILE_SIZE_BYTES = 20 * 1024 * 1024;

  private String ufsrvUid;
  private String avatarId;
  private byte[] key;

  public ProfileAvatarDownloadJob(String ufsrvUid, String avatarId, byte[] key) {
    super(new Job.Parameters.Builder()
              .addConstraint(NetworkConstraint.KEY)
              .setMaxAttempts(3)
              .build());
    this.ufsrvUid = ufsrvUid;
    this.avatarId = avatarId;
    this.key      = key;
  }

  private ProfileAvatarDownloadJob(Parameters parameters, String ufsrvUid, String avatarId, byte[] key) {
    super(parameters);
    this.ufsrvUid = ufsrvUid;
    this.avatarId = avatarId;
    this.key      = key;
  }

  @Override
  public @NonNull
  Data serialize() {
    try {
      return new Data.Builder().putString(KEY_UID, ufsrvUid)
              .putString(KEY_AVATAR_ID, avatarId)
              .putString(KEY_KEY, Base64.encodeBytes(key, Base64.NO_OPTIONS))
              .build();
    } catch (Exception x) {
      Log.e(TAG, x.getMessage());
      return new Data.Builder().putString(KEY_UID, ufsrvUid)
              .putString(KEY_AVATAR_ID, avatarId)
              .build();
    }

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

    Recipient recipient = Recipient.live(new UfsrvUid(ufsrvUid).toString()).get();
    RecipientDatabase database   = SignalDatabase.recipients();

    if (key == null) {
      Log.e(TAG, "onRun(): Key was missing for: " + ufsrvUid);
    }

    Log.i(TAG, "onRun(): Downloading profile avatar for: " + ufsrvUid);

    ProfileKey profileKey = null;
    try {
      profileKey = new ProfileKey(key);
    } catch (InvalidInputException x) {
      Log.w(TAG, "Recipient profile key is gone!");
      return;
    }

    File downloadDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

    try {
      InputStream avatarStream       = ApplicationDependencies.getSignalServiceMessageReceiver().retrieveProfileAvatarUfsrv(avatarId, downloadDestination, profileKey, MAX_PROFILE_SIZE_BYTES);
      AvatarHelper.setAvatar(context, recipient.getId(), avatarStream);
    }
     finally {
      if (downloadDestination != null) downloadDestination.delete();
    }

    database.setAvatarUfId(recipient, avatarId);
    database.setProfileKey(recipient.getId(), profileKey);

    ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventAvatarDownloaded(recipient, avatarId));
  }

  @Override
  public boolean onShouldRetry(Exception e) {
    Log.w(TAG, e);
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {

  }

  public static class Factory implements Job.Factory<ProfileAvatarDownloadJob> {
    @Override
    public @NonNull ProfileAvatarDownloadJob create(@NonNull Parameters parameters, @NonNull Data data) {
      byte[] profileKey = null;

      try {
        profileKey = Base64.decode(data.getString(KEY_KEY), Base64.NO_OPTIONS);
      } catch (IOException x) {
        Log.e(TAG, "create(): ERROR decoding KEY : " + data.getString(KEY_UID));
      }

      return new ProfileAvatarDownloadJob(parameters, data.getString(KEY_UID), data.getString(KEY_AVATAR_ID), profileKey);
    }
  }
}