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
import com.unfacd.android.ufsrvcmd.events.AppEventAvatarDownloaded;
import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.logging.Log;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.push.exceptions.PushNetworkException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.AttachmentRecord;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ProfileAvatarDownloadJob extends BaseJob {

  private static final String TAG = ProfileAvatarDownloadJob.class.getSimpleName();

  public static final String KEY = "ProfileAvatarDownloadJob";

  private static final String KEY_UID               = "uid";
  private static final String KEY_ATTACHMENT_RECORD = "attachment_record";
  private static final String KEY_AUX_ID = "aux_id";

  public static final int MAX_PROFILE_SIZE_BYTES = 20 * 1024 * 1024;

  private String ufsrvUid;
  private AttachmentRecord attachmentRecord;
  private String auxId;

  public ProfileAvatarDownloadJob(String ufsrvUid, AttachmentRecord attachmentRecord) {

    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         ufsrvUid, attachmentRecord, "");
  }

  public ProfileAvatarDownloadJob(String ufsrvUid, AttachmentRecord attachmentRecord, String auxId) {

    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(3)
                 .build(),
         ufsrvUid, attachmentRecord, auxId);
  }

  private ProfileAvatarDownloadJob(@NonNull Job.Parameters parameters, String ufsrvUid, AttachmentRecord attachmentRecord, String auxId) {
    super(parameters);

    this.ufsrvUid           = ufsrvUid;
    this.attachmentRecord   = attachmentRecord;
    this.auxId              = auxId;
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
              .putString(KEY_AUX_ID, auxId)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    Recipient recipient = Recipient.fromUfsrvUid(context, new UfsrvUid(ufsrvUid), false);
    RecipientDatabase database   = DatabaseFactory.getRecipientDatabase(context);

    Log.i(TAG, "onRun(): Downloading profile avatar for: " + ufsrvUid);

    if (attachmentRecord == null) {
      Log.w(TAG, "WARNING: ATTACHMENT RECORD SET TO NULL: Removing profile avatar for: " + recipient.getAddress().serialize());

      if (TextUtils.isEmpty(auxId)) {
        AvatarHelper.delete(context, recipient.getAddress());
        database.setAvatarUfId(recipient, null);
      }

      return;
    }

    byte[]            profileKey = attachmentRecord.getKey().toByteArray();
    if (profileKey == null) {
      Log.w(TAG, "Recipient profile key is gone!");
      return;
    }

    File downloadDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());

    try {
      InputStream avatarStream       = ApplicationDependencies.getSignalServiceMessageReceiver().retrieveProfileAvatarUfsrv(attachmentRecord.getId(), downloadDestination, profileKey, MAX_PROFILE_SIZE_BYTES);
      File        decryptDestination = File.createTempFile("avatar", "jpg", context.getCacheDir());
      Util.copy(avatarStream, new FileOutputStream(decryptDestination));
      if (TextUtils.isEmpty(auxId))  {
        decryptDestination.renameTo(AvatarHelper.getAvatarFile(context, recipient.getAddress()));
      } else {
        decryptDestination.renameTo(AvatarHelper.getAvatarFile(context, Address.fromSerialized(auxId)));
      }
    }
     finally {
      if (downloadDestination != null) downloadDestination.delete();
    }

    if (TextUtils.isEmpty(auxId)) {
      database.setAvatarUfId(recipient, attachmentRecord.getId());
      database.setProfileKey(recipient, attachmentRecord.getKey().toByteArray());
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
  public void onCanceled() {

  }

  public static class Factory implements Job.Factory<ProfileAvatarDownloadJob> {
    @Override
    public @NonNull ProfileAvatarDownloadJob create(@NonNull Parameters parameters, @NonNull Data data) {
      AttachmentRecord attachmentRecord = null;
      try {
        byte[] attachmentRecordSerialised = org.whispersystems.signalservice.internal.util.Base64.decode(data.getString(KEY_ATTACHMENT_RECORD));
        attachmentRecord = AttachmentRecord.parseFrom(attachmentRecordSerialised);
      } catch (IOException | java.lang.IllegalStateException x) {
        Log.w(TAG, String.format("ERROR: COULD NOT CONVERT AttachmentRecord for user '%s': AVATAR RECORD FOR USER WILL BE REMOVED", data.getString(KEY_UID)));
      }

      return new ProfileAvatarDownloadJob(parameters,   data.getString(KEY_UID), attachmentRecord, data.getString(KEY_AUX_ID));
    }
  }
}