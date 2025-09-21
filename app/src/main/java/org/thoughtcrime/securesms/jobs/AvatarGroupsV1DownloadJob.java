package org.thoughtcrime.securesms.jobs;

import androidx.annotation.NonNull;
import android.text.TextUtils;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase.GroupRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.jobmanager.Data;
import org.thoughtcrime.securesms.jobmanager.Job;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.net.NotPushRegisteredException;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.signal.core.util.Hex;
import org.signal.libsignal.protocol.InvalidMessageException;
import java.util.Optional;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentPointer;
import org.whispersystems.signalservice.api.messages.SignalServiceAttachmentRemoteId;
import org.whispersystems.signalservice.api.push.exceptions.MissingConfigurationException;
import org.whispersystems.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public final class AvatarGroupsV1DownloadJob extends BaseJob {

  public static final String KEY = "AvatarDownloadJob";

  private static final String TAG = Log.tag(AvatarGroupsV1DownloadJob.class);

  private static final String KEY_GROUP_ID = "group_id";

  @NonNull private final GroupId groupId;

  public AvatarGroupsV1DownloadJob (@NonNull GroupId groupId) {
    this(new Job.Parameters.Builder()
                 .addConstraint(NetworkConstraint.KEY)
                 .setMaxAttempts(10)
                 .build(),
         groupId);
  }

  private AvatarGroupsV1DownloadJob (@NonNull Job.Parameters parameters, @NonNull GroupId groupId) {
    super(parameters);
    this.groupId = groupId;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_GROUP_ID, groupId.toString()).build();
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

    GroupDatabase         database   = SignalDatabase.groups();
    Optional<GroupRecord> record     = database.getGroup(groupId);
    File                  attachment = null;

    try {
      if (record.isPresent()) {
        long             avatarId    = record.get().getAvatarId();
        String           contentType = record.get().getAvatarContentType();
        byte[]           key         = record.get().getAvatarKey();
        String           relay       = record.get().getRelay();
        Optional<byte[]> digest      = Optional.ofNullable(record.get().getAvatarDigest());
        Optional<String> fileName    = Optional.empty();

        //AA+ todo: should go in initialisation and serialisation and constructor
        String avatarUfId = record.get().getAvatarUfId();

        if (TextUtils.isEmpty(avatarUfId) /*|| avatarId == -1*/ || key == null) {
          Log.e(TAG, "Avatar identifier unknown: " + avatarUfId);
          return;
        }

        if (digest.isPresent()) {
          Log.w(TAG, "Downloading group avatar with digest: " + Hex.toString(digest.get()));
        }

        attachment = File.createTempFile("avatar", "tmp", context.getCacheDir());
        attachment.deleteOnExit();

        SignalServiceMessageReceiver   receiver    = ApplicationDependencies.getSignalServiceMessageReceiver();
        SignalServiceAttachmentPointer pointer     = new SignalServiceAttachmentPointer(avatarUfId, 0, new SignalServiceAttachmentRemoteId(avatarId), contentType, key, Optional.of(0), Optional.empty(), 0, 0, digest, fileName, false, false, false, Optional.empty(), Optional.empty(), System.currentTimeMillis());//AA+  ufId
        InputStream                    inputStream = receiver.retrieveAttachment(pointer, attachment, AvatarHelper.AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE);
        try {
          AvatarHelper.setAvatar(context, record.get().getRecipientId(), inputStream);//AA++
        } catch (AssertionError e) {
          throw new IOException("Failed to copy stream. Likely a Conscrypt issue.", e);
        }
        SignalDatabase.groups().onAvatarUpdated(groupId.requireV2(), true);

//        database.updateAvatar(groupId, BitmapUtil.toByteArray(avatar), avatarUfId);//AA+
        SignalDatabase.groups().onAvatarUpdated(groupId.requireV2(), true);
        inputStream.close();
      }
    } catch (NonSuccessfulResponseCodeException | InvalidMessageException | MissingConfigurationException e) {
      Log.w(TAG, e);
    } finally {
      if (attachment != null)
        attachment.delete();
    }
  }

  @Override
  public void onFailure() {}

  @Override
  public boolean onShouldRetry(Exception exception) {
    if (exception instanceof IOException) return true;
    return false;
  }

  public static final class Factory implements Job.Factory<AvatarGroupsV1DownloadJob> {
    @Override
    public @NonNull
    AvatarGroupsV1DownloadJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new AvatarGroupsV1DownloadJob(parameters, GroupId.parseOrThrow(data.getString(KEY_GROUP_ID)).requireV2());
    }
  }
}
