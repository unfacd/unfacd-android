package org.thoughtcrime.securesms.profiles.manage;

import android.content.Context;

import com.unfacd.android.jobs.UfsrvUserCommandProfileJob;
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob.ProfileCommandDescriptor;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.signal.core.util.concurrent.SignalExecutors;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.MultiDeviceProfileContentUpdateJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.profiles.edit.EditSelfProfileRepository;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.ProfileUtil;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.CommandArgs;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.UserCommand;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

final class ManageProfileRepository {

  private static final String TAG = Log.tag(ManageProfileRepository.class);

  public void setName(@NonNull Context context, @NonNull ProfileName profileName, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        ProfileUtil.uploadProfileWithName(context, profileName);
        SignalDatabase.recipients().setProfileName(Recipient.self().getId(), profileName);
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (IOException e) {
        Log.w(TAG, "Failed to upload profile during name change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  public void setAbout(@NonNull Context context, @NonNull String about, @NonNull String emoji, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        ProfileUtil.uploadProfileWithAbout(context, about, emoji);
        SignalDatabase.recipients().setAbout(Recipient.self().getId(), about, emoji);
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (IOException e) {
        Log.w(TAG, "Failed to upload profile during about change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  public void setAvatar(@NonNull Context context, @NonNull byte[] data, @NonNull String contentType, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
//        ProfileUtil.uploadProfileWithAvatar(new StreamDetails(new ByteArrayInputStream(data), contentType, data.length));//AA-
        sendAvatar(context, data,
                    new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.UPDATED_VALUE));//AA+
        AvatarHelper.setAvatar(context, Recipient.self().getId(), new ByteArrayInputStream(data));
        SignalStore.misc().markHasEverHadAnAvatar();
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (IOException e) {
        Log.w(TAG, "Failed to upload profile during avatar change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  public void clearAvatar(@NonNull Context context, @NonNull Consumer<Result> callback) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
//        ProfileUtil.uploadProfileWithAvatar(null);//AA-
        sendAvatar(context, null,
                              new UfsrvCommandUtils.CommandArgDescriptor(UserCommand.CommandTypes.PREFERENCE_VALUE, CommandArgs.DELETED_VALUE));
        AvatarHelper.delete(context, Recipient.self().getId());
        ApplicationDependencies.getJobManager().add(new MultiDeviceProfileContentUpdateJob());

        callback.accept(Result.SUCCESS);
      } catch (Exception e) {
        Log.w(TAG, "Failed to upload profile during name change.", e);
        callback.accept(Result.FAILURE_NETWORK);
      }
    });
  }

  //AA+
  private static EditSelfProfileRepository.UserProfileActionResult sendAvatar(@NonNull  Context      context,
                                                                              @Nullable byte[]       avatar,
                                                                              @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    Log.d(TAG, String.format("sendGroupUpdateAvatar: Updating profile avatar" ));

    ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.AVATAR);
    if (avatar != null) profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.SET);
    else profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.UNSET);
    UfsrvUserCommandProfileJob.ProfileCommandDescriptor profileCommandDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor(profileOperationDescriptor);

    ApplicationDependencies.getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), avatar!=null ? Base64.encodeBytes(avatar) : null));

    return new EditSelfProfileRepository.UserProfileActionResult(null, 0);
  }


  enum Result {
    SUCCESS, FAILURE_NETWORK
  }
}