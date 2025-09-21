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

import android.graphics.Bitmap;
import android.text.TextUtils;

import com.unfacd.android.mapable.GroupAvatarHelper;
import com.unfacd.android.mapable.MapableGroup;
import com.unfacd.android.mapable.MapableThing;
import com.unfacd.android.mapable.MapableThingProvider;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.jobs.BaseJob;
import org.thoughtcrime.securesms.mms.AttachmentStreamUriLoader.AttachmentModel;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.BitmapDecodingException;
import org.thoughtcrime.securesms.util.BitmapUtil;
import org.signal.libsignal.protocol.InvalidMessageException;
import java.util.Optional;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.push.exceptions.MissingConfigurationException;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.util.Base64;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import androidx.annotation.NonNull;

public class MapableAvatarDownloadJob extends BaseJob {

  public static final String KEY = "MapableAvatarDownloadJob";

  private static final int MAX_AVATAR_SIZE = 20 * 1024 * 1024;

  private static final String KEY_FID             = "fid";
  private static final String KEY_FENCE_RECORD    = "fenceRecord";

  private long fid;
  private String fenceRecordEncoded;

  private static final String TAG = Log.tag(MapableAvatarDownloadJob.class);

  private MapableThing mapableThing;



  public MapableAvatarDownloadJob(long fid, String fenceRecordEncoded) {
    this(new Job.Parameters.Builder()
                           .addConstraint(NetworkConstraint.KEY)
                           .setMaxAttempts(1)
                           .setQueue(TAG +  "-" + fid)
                           .build(),
         fid, fenceRecordEncoded);
  }

  private MapableAvatarDownloadJob(@NonNull Job.Parameters parameters, long fid, String fenceRecordEncoded) {
    super(parameters);

    this.fid = fid;
    this.fenceRecordEncoded = fenceRecordEncoded;

  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_FID, fid)
            .putString(KEY_FENCE_RECORD, fenceRecordEncoded)
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

    if (fid <= 0) {
      Log.e(TAG,"MapableAvatarDownloadJob: FID was not set: returning...");
      return;
    }
    if (TextUtils.isEmpty(fenceRecordEncoded)) {
      Log.e(TAG, String.format("MapableAvatarDownloadJob fid:'%d': FenceRecord not provided...",  fid));
      return;
    }

    SignalServiceProtos.FenceRecord fenceRecord   = null;
    Optional<MapableGroup>          mapableGroup  = MapableThingProvider.getMapbleGroup(fid);
    try {
      fenceRecord = SignalServiceProtos.FenceRecord.parseFrom(Base64.decode(fenceRecordEncoded));
    } catch (IOException x) {
      Log.e (TAG, x.getMessage());
    }

    SignalServiceAttachmentPointer  avatarPointer      = new SignalServiceAttachmentPointer(fenceRecord.getAvatar().getId(),
                                                                 0,
                                                                 fenceRecord.getAvatar().getContentType(),
                                                                 fenceRecord.getAvatar().getKey().toByteArray(),
                                                                 fenceRecord.getAvatar().hasDigest() ? Optional.of(fenceRecord.getAvatar().getDigest().toByteArray()) : Optional.empty(),
                                                                 fenceRecord.getAvatar().hasFileName()? Optional.of(fenceRecord.getAvatar().getFileName()):Optional.empty(),
                                                                 false,
                                                                                            (fenceRecord.getAvatar().getFlags() & SignalServiceProtos.AttachmentPointer.Flags.BORDERLESS_VALUE) != 0,
                                                                                            (fenceRecord.getAvatar().getFlags() & SignalServiceProtos.AttachmentPointer.Flags.GIF_VALUE) != 0);

    InputStream input = downLoadAvatar(avatarPointer, Long.valueOf(fid));
    if (input != null) {
      try {
        Bitmap avatar = BitmapUtil.createScaledBitmap(context, new AttachmentModel(GroupAvatarHelper.getGroupAvatarFile(context, constructAvatarFilePrefix(fid, avatarPointer)), avatarPointer.getKey(), 0, avatarPointer.getDigest()), 500, 500);

        mapableGroup.get().setAvatar(avatar);
      } catch (BitmapDecodingException x) {
        Log.d(TAG, x);
      }
      input.close();
    }
  }

  private InputStream downLoadAvatar(SignalServiceAttachmentPointer attachment, Long fid)
  {
    File downloadDestination;
    try {
      downloadDestination = GroupAvatarHelper.getGroupAvatarFile(context, constructAvatarFilePrefix(fid, attachment));

      InputStream avatarStream = ApplicationDependencies.getSignalServiceMessageReceiver().retrieveAttachment(attachment, downloadDestination, ProfileAvatarDownloadJob.MAX_PROFILE_SIZE_BYTES, null);
      return avatarStream;
    } catch (IOException | InvalidMessageException | MissingConfigurationException x) {
      Log.e(TAG, x.getMessage());
    }
    finally {
//      if (downloadDestination != null) downloadDestination.delete();
    }

    return null;
  }

  static public String constructAvatarFilePrefix (Long fid, SignalServiceAttachmentPointer  avatarPointer)
  {
    return fid + ":" + avatarPointer.getUfId();
  }

  @Override
  public void onFailure() {}

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof IOException) return true;
    return false;
  }

  public static class Factory implements Job.Factory<MapableAvatarDownloadJob> {
    @Override
    public @NonNull MapableAvatarDownloadJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new MapableAvatarDownloadJob(parameters, data.getLong(KEY_FID), data.getString(KEY_FENCE_RECORD));
    }
  }

}
