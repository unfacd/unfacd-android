package org.thoughtcrime.securesms.profiles.edit;

import android.content.Context;
import android.os.AsyncTask;
import android.text.TextUtils;

import com.unfacd.android.jobs.UfsrvUserCommandProfileJob;
import com.unfacd.android.utils.UfsrvCommandUtils;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.profiles.ProfileMediaConstraints;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.profiles.SystemProfileUtil;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.registration.RegistrationUtil;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;
import org.whispersystems.signalservice.api.util.InvalidNumberException;
import org.whispersystems.signalservice.api.util.StreamDetails;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.lifecycle.MutableLiveData;

public class EditSelfProfileRepository implements EditProfileRepository {

  private static final String TAG = Log.tag(EditSelfProfileRepository.class);

  private final Context context;
  private final boolean excludeSystem;

  EditSelfProfileRepository(@NonNull Context context, boolean excludeSystem) {
    this.context        = context.getApplicationContext();
    this.excludeSystem  = excludeSystem;
  }

  @Override
  public void getCurrentAvatarColor(@NonNull Consumer<AvatarColor> avatarColorConsumer) {
    SimpleTask.run(() -> Recipient.self().getAvatarColor(), avatarColorConsumer::accept);
  }

  @Override
  public void getCurrentProfileName(@NonNull Consumer<ProfileName> profileNameConsumer) {
    ProfileName storedProfileName = Recipient.self().getProfileName();
    if (!storedProfileName.isEmpty()) {
      profileNameConsumer.accept(storedProfileName);
    } else if (!excludeSystem) {
      SystemProfileUtil.getSystemProfileName(context).addListener(new ListenableFuture.Listener<String>() {
        @Override
        public void onSuccess(String result) {
          if (!TextUtils.isEmpty(result)) {
            profileNameConsumer.accept(ProfileName.fromSerialized(result));
          } else {
            profileNameConsumer.accept(storedProfileName);
          }
        }

        @Override
        public void onFailure(ExecutionException e) {
          Log.w(TAG, e);
          profileNameConsumer.accept(storedProfileName);
        }
      });
    } else {
      profileNameConsumer.accept(storedProfileName);
    }
  }

  @Override
  public void getCurrentAvatar(@NonNull Consumer<byte[]> avatarConsumer) {
    RecipientId selfId = Recipient.self().getId();

    if (AvatarHelper.hasAvatar(context, selfId)) {
      SimpleTask.run(() -> {
        try {
          return StreamUtil.readFully(AvatarHelper.getAvatar(context, selfId));
        } catch (IOException e) {
          Log.w(TAG, e);
          return null;
        }
      }, avatarConsumer::accept);
    } else if (!excludeSystem) {
      SystemProfileUtil.getSystemProfileAvatar(context, new ProfileMediaConstraints()).addListener(new ListenableFuture.Listener<byte[]>() {
        @Override
        public void onSuccess(byte[] result) {
          avatarConsumer.accept(result);
        }

        @Override
        public void onFailure(ExecutionException e) {
          Log.w(TAG, e);
          avatarConsumer.accept(null);
        }
      });
    }
  }

  @Override
  public void getCurrentDisplayName(@NonNull Consumer<String> displayNameConsumer) {
    displayNameConsumer.accept("");
  }

  //AA+
  @Override
  public void getCurrentNickname(@NonNull Consumer<Optional<String>> callback) {
    callback.accept(Optional.ofNullable(TextSecurePreferences.getUfsrvNickname(context)));
  }

  @Override
  public void isNameEditable(@NonNull Consumer<Boolean> callback) {
    callback.accept(true);
  }
  //

  @Override
  public void getCurrentName(@NonNull Consumer<String> nameConsumer) {
    nameConsumer.accept("");
  }

  @Override public void getCurrentDescription(@NonNull Consumer<String> descriptionConsumer) {
    descriptionConsumer.accept("");
  }

  @Override
  public void uploadProfile(@NonNull ProfileName profileName,
                            @NonNull String displayName,
                            boolean displayNameChanged,
                            @NonNull String description,
                            boolean descriptionChanged,
                            @Nullable byte[] avatar,
                            boolean avatarChanged,
                            @NonNull Consumer<UploadResult> uploadResultConsumer)
  {
//    SimpleTask.run(() -> {
//      SignalDatabase.recipients().setProfileName(Recipient.self().getId(), profileName);
//
//      try {
//        AvatarHelper.setAvatar(context, Recipient.self().getId(), avatar != null ? new ByteArrayInputStream(avatar) : null);
//      } catch (IOException e) {
//        return UploadResult.ERROR_IO;
//      }
//
//      ApplicationDependencies.getJobManager()
//              .startChain(new ProfileUploadJob())
//              .then(Arrays.asList(new MultiDeviceProfileKeyUpdateJob(), new MultiDeviceProfileContentUpdateJob()))
//              .enqueue();

//      RegistrationUtil.maybeMarkRegistrationComplete(context);
//
//      return UploadResult.SUCCESS;
//    }, uploadResultConsumer::accept);

    //AA+
    SimpleTask.run(() -> {
      new UpdateBasicUserProfileTask(profileName, avatarChanged?avatar:null, displayName).execute();

      RegistrationUtil.maybeMarkRegistrationComplete(context);

      if (avatar != null) {
        SignalStore.misc().markHasEverHadAnAvatar();
      }

      return UploadResult.SUCCESS;
    }, uploadResultConsumer::accept);
  }

  @Override
  public void getCurrentUsername(@NonNull Consumer<Optional<String>> callback) {
    callback.accept(Recipient.self().getUsername());
  }

  private class
  UpdateBasicUserProfileTask extends AsyncTask<Void,Void,Optional<ArrayList<UserProfileActionResult>>>
  {
    @NonNull ProfileName profileName;
    @Nullable byte[] avatar;
    @Nullable String nickname;

    public UpdateBasicUserProfileTask (@NonNull ProfileName profileName, @Nullable byte[] avatar, @Nullable String nickname) {
      this.profileName = profileName;
      this.avatar      =  avatar;
      this.nickname    =  nickname;
    }

    @Override
    protected Optional<ArrayList<UserProfileActionResult>> doInBackground(Void... aVoid) {
      try {
        return Optional.of(updateUserProfile(profileName, avatar, nickname));
      } catch (InvalidNumberException e) {
        return Optional.empty();
      }
    }

    @Override
    protected void onPostExecute(Optional<ArrayList<UserProfileActionResult>> result) {
      if (result.isPresent() && result.get().size() >= 1) {
        //todo: proper error checking on each list item result.get().get(0).threadId >-1
//        if (captureFile != null) captureFile.delete();
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) handleFinishedLollipop();
//        else                                                       handleFinishedLegacy();
      } else {
        super.onPostExecute(result);
      }
    }
  }

  public ArrayList<UserProfileActionResult>
  updateUserProfile(@NonNull ProfileName profileName, @Nullable byte[] avatarBytes, @Nullable String nickname) throws InvalidNumberException
  {
    ArrayList<UserProfileActionResult>  groupActionResults  = new ArrayList<>();
    UserProfileActionResult             profileActionResultName;
    final StreamDetails                 avatar;
    final MutableLiveData<byte[]> avatarBytesOrig      = new MutableLiveData<>();

    if (!nickname.equals(TextSecurePreferences.getUfsrvNickname(context))) {
      profileActionResultName = sendUserProfileUpdateName(context, nickname,
                                                          new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE));

      groupActionResults.add(profileActionResultName);
    } else {
      groupActionResults.add(new UserProfileActionResult(null, -1));
    }

    if (avatarBytes != null) {
      getCurrentAvatar(ab -> {if (ab != null) avatarBytesOrig.setValue(ab);});
      if (avatarBytesOrig.getValue() == null || avatarBytesOrig.getValue() != null && !Arrays.equals(avatarBytesOrig.getValue(), avatarBytes)) { //AA+  array comparison
        profileActionResultName = sendGroupUpdateAvatar(context, avatarBytes,
                                                        new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.UPDATED_VALUE));

        groupActionResults.add(profileActionResultName);
      }
    } else if (avatarBytesOrig.getValue() != null || (avatarBytesOrig.getValue() != null && avatarBytesOrig.getValue().length > 0)) {
      //also caters for avatar was previously set but now deleted
      profileActionResultName = sendGroupUpdateAvatar(context, null,
                                                      new UfsrvCommandUtils.CommandArgDescriptor(SignalServiceProtos.UserCommand.CommandTypes.PREFERENCE_VALUE, SignalServiceProtos.CommandArgs.DELETED_VALUE));

      groupActionResults.add(profileActionResultName);
    }

    return groupActionResults;//.get(0);//return the first for now
  }

  private static UserProfileActionResult
  sendUserProfileUpdateName(@NonNull  Context      context,
                            @Nullable String       nickname,
                            @NonNull  UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    Log.d(TAG, String.format("sendUserProfileUpdateName: Updating profile '%s'", nickname));

    UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.NAME);
    profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.SET);
    UfsrvUserCommandProfileJob.ProfileCommandDescriptor profileCommandDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor(profileOperationDescriptor);
    ApplicationDependencies.getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), nickname));

    return new UserProfileActionResult(null, 0);
  }

  private static UserProfileActionResult sendGroupUpdateAvatar(@NonNull  Context      context,
                                                               @Nullable byte[]       avatar,
                                                               @NonNull UfsrvCommandUtils.CommandArgDescriptor commandArgDescriptor)
  {
    Log.d(TAG, String.format("sendGroupUpdateAvatar: Updating profile avatar" ));

    UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor profileOperationDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor.ProfileOperationDescriptor();
    profileOperationDescriptor.setProfileType(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.AVATAR);
    if (avatar!=null) profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.SET);
    else profileOperationDescriptor.setOperationMode(UfsrvUserCommandProfileJob.IProfileOperationDescriptor.OperationMode.UNSET);
    UfsrvUserCommandProfileJob.ProfileCommandDescriptor profileCommandDescriptor = new UfsrvUserCommandProfileJob.ProfileCommandDescriptor(profileOperationDescriptor);

    ApplicationDependencies.getJobManager()
            .add(new UfsrvUserCommandProfileJob(UfsrvUserCommandProfileJob.ProfileCommandHelper.serialise(profileCommandDescriptor), avatar!=null ? Base64.encodeBytes(avatar) : null));

    return new UserProfileActionResult(null, 0);
  }

  public static class UserProfileActionResult {
    private Recipient   groupRecipient;
    private long        threadId;

    public UserProfileActionResult(Recipient groupRecipient, long threadId) {
      this.groupRecipient = groupRecipient;
      this.threadId       = threadId;
    }

    public Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }
  }
}