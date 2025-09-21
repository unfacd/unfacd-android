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

import com.unfacd.android.ApplicationContext;
import com.unfacd.android.locallyaddressable.LocallyAddressableUfsrvUid;
import com.unfacd.android.ufsrvcmd.events.AppEventAvatarDownloaded;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.InvalidInputException;
import org.signal.libsignal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentRecord;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.NonNull;

public class IntroMessageAvatarDownloadJob extends BaseJob {

  private static final String TAG = Log.tag(IntroMessageAvatarDownloadJob.class);

  public static final String KEY = "IntroMessageAvatarDownloadJob";

  private static final String KEY_UID               = "uid";
  private static final String KEY_ATTACHMENT_RECORD = "attachment_record";

  public static final int MAX_PROFILE_SIZE_BYTES = 20 * 1024 * 1024;

  private String ufsrvUid;
  private AttachmentRecord attachmentRecord;

  public IntroMessageAvatarDownloadJob(String ufsrvUid, AttachmentRecord attachmentRecord) {

    this(new Job.Parameters.Builder()
                           .addConstraint(NetworkConstraint.KEY)
                           .setMaxAttempts(3)
                           .build(),
         ufsrvUid, attachmentRecord);
  }

  private IntroMessageAvatarDownloadJob(@NonNull Job.Parameters parameters, String ufsrvUid, AttachmentRecord attachmentRecord) {
    super(parameters);

    this.ufsrvUid           = ufsrvUid;
    this.attachmentRecord   = attachmentRecord;
  }

  @Override
  public @NonNull
  Data serialize() {
    String attachmentRecordSerialised = null;
    if (attachmentRecord  !=  null) {
      attachmentRecordSerialised = org.whispersystems.signalservice.internal.util.Base64.encodeBytes(attachmentRecord.toByteString().toByteArray());
    }
    return new Data.Builder().putString(KEY_UID, ufsrvUid)
                             .putString(KEY_ATTACHMENT_RECORD, attachmentRecordSerialised)
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

    Recipient recipient = Recipient.live(new UfsrvUid(ufsrvUid).toString()).get();

    Log.i(TAG, "onRun(): Downloading profile avatar for: " + ufsrvUid);

    ProfileKey profileKey = null;
    try {
      profileKey = new ProfileKey(attachmentRecord.getKey().toByteArray());
    } catch (InvalidInputException x) {
      Log.w(TAG, "Recipient profile key is gone!");
      return;
    }

    File downloadDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

    try {
      InputStream avatarStream       = ApplicationDependencies.getSignalServiceMessageReceiver().retrieveProfileAvatarUfsrv(attachmentRecord.getId(), downloadDestination, profileKey, MAX_PROFILE_SIZE_BYTES);
      try {
        AvatarHelper.setAvatar(context, LocallyAddressableUfsrvUid.from(recipient.getId(), ufsrvUid), avatarStream);
      } catch (AssertionError e) {
        throw new IOException("Failed to copy stream. Likely a Conscrypt issue.", e);
      }
    }
    finally {
      if (downloadDestination != null) downloadDestination.delete();
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().post(new AppEventAvatarDownloaded(recipient, attachmentRecord.getId()));
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

  public static class Factory implements Job.Factory<IntroMessageAvatarDownloadJob> {
    @Override
    public @NonNull
    IntroMessageAvatarDownloadJob create(@NonNull Parameters parameters, @NonNull Data data) {
      AttachmentRecord attachmentRecord = null;
      try {
        byte[] attachmentRecordSerialised = org.whispersystems.signalservice.internal.util.Base64.decode(data.getString(KEY_ATTACHMENT_RECORD));
        attachmentRecord = AttachmentRecord.parseFrom(attachmentRecordSerialised);
      } catch (IOException | java.lang.IllegalStateException x) {
        Log.w(TAG, String.format("ERROR: COULD NOT CONVERT AttachmentRecord for user '%s': AVATAR RECORD FOR USER WILL BE REMOVED", data.getString(KEY_UID)));
      }

      return new IntroMessageAvatarDownloadJob(parameters, data.getString(KEY_UID), attachmentRecord);
    }
  }
}