package org.thoughtcrime.securesms.profiles.edit;

import android.content.Context;

import com.unfacd.android.R;
import com.unfacd.android.ui.components.PairedGroupName;

import org.signal.core.util.StreamUtil;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.conversation.colors.AvatarColor;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.groups.GroupAccessControl;
import org.thoughtcrime.securesms.groups.GroupChangeException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;

import java.io.IOException;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

class EditGroupProfileRepository implements EditProfileRepository {

  private static final String TAG = Log.tag(EditGroupProfileRepository.class);

  private final Context context;
  private final GroupId groupId;

  EditGroupProfileRepository(@NonNull Context context, @NonNull GroupId groupId) {
    this.context = context.getApplicationContext();
    this.groupId = groupId;
  }

  @Override
  public void getCurrentAvatarColor(@NonNull Consumer<AvatarColor> avatarColorConsumer) {
    SimpleTask.run(() -> Recipient.resolved(getRecipientId()).getAvatarColor(), avatarColorConsumer::accept);
  }

  @Override
  public void getCurrentProfileName(@NonNull Consumer<ProfileName> profileNameConsumer) {
    profileNameConsumer.accept(ProfileName.EMPTY);
  }

  @Override
  public void getCurrentAvatar(@NonNull Consumer<byte[]> avatarConsumer) {
    SimpleTask.run(() -> {
      final RecipientId recipientId = getRecipientId();

      if (AvatarHelper.hasAvatar(context, recipientId)) {
        try {
          return StreamUtil.readFully(AvatarHelper.getAvatar(context, recipientId));
        } catch (IOException e) {
          Log.w(TAG, e);
          return null;
        }
      } else {
        return null;
      }
    }, avatarConsumer::accept);
  }

  @Override
  public void getCurrentDisplayName(@NonNull Consumer<String> displayNameConsumer) {
    SimpleTask.run(() -> Recipient.resolved(getRecipientId()).getDisplayName(), displayNameConsumer::accept);
  }

  //AA+
  @Override
  public void getCurrentNickname(@NonNull Consumer<Optional<String>> callback) {
    //nothing
    callback.accept(Optional.empty());
  }

  @Override
  public void isNameEditable(@NonNull Consumer<Boolean> callback) {
    SimpleTask.run(() -> {
      final GroupDatabase groupDatabase =  SignalDatabase.groups();

      boolean isPairedGroup = groupDatabase.isPairedGroup(groupId);
      GroupDatabase.GroupType groupType = groupDatabase.getGroupType(groupId);
      GroupDatabase.GroupRecord groupRecord = groupDatabase.getGroup(groupId).orElse(null);
      if (groupRecord != null) {
        GroupAccessControl groupAccessControl = groupRecord.getAttributesAccessControlFor(Recipient.self());
        return !isPairedGroup && !groupType.equals(GroupDatabase.GroupType.GEO) && groupAccessControl.equals(GroupAccessControl.ALLOWED);
      }

      return false;

    }, callback::accept);
  }
  //

  //AA+
  @Override
  public void getCurrentName(@NonNull Consumer<String> nameConsumer) {
    SimpleTask.run(() -> {
      RecipientId recipientId = getRecipientId();
      Recipient   recipient   = Recipient.resolved(recipientId);

      return SignalDatabase.groups()
                           .getGroup(recipientId)
                           .map(groupRecord -> {
                             if (groupRecord.isPairedGroup()) {
                               PairedGroupName privateGroupForTwoName = new PairedGroupName(context, Recipient.live(recipientId));
                               return privateGroupForTwoName.getStylisedName().get().toString();
                             } else {
                               String title = groupRecord.getTitle();
                               return title == null ? context.getString(R.string.Recipient_unknown) : title;
                             }
                           })
                           .orElseGet(() -> recipient.getGroupName(context));
    }, nameConsumer::accept);
  }

  //AA-
 /* @Override
  public void getCurrentName(@NonNull Consumer<String> nameConsumer) {
    SimpleTask.run(() -> {
      RecipientId recipientId = getRecipientId();
      Recipient   recipient   = Recipient.resolved(recipientId);

      return SignalDatabase.groups()
              .getGroup(recipientId)
              .map(groupRecord -> {
                String title = groupRecord.getTitle();
                return title == null ? "" : title;
              })
              .or(() -> recipient.getGroupName(context));
    }, nameConsumer::accept);
  }*/

  @Override
  public void getCurrentDescription(@NonNull Consumer<String> descriptionConsumer) {
    SimpleTask.run(() -> {
      RecipientId recipientId = getRecipientId();

      return SignalDatabase.groups()
                           .getGroup(recipientId)
                           .map(groupRecord -> {
                String description = groupRecord.getDescription();
                return description == null ? "" : description;
              })
              .orElse("");
    }, descriptionConsumer::accept);
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
    SimpleTask.run(() -> {
      try {
        GroupManager.updateGroupDetails(context, groupId, avatar, avatarChanged, displayName, displayNameChanged, description, descriptionChanged);

        return UploadResult.SUCCESS;
      } catch (GroupChangeException | IOException e) {
        return UploadResult.ERROR_IO;
      }

    }, uploadResultConsumer::accept);
  }

  @Override
  public void getCurrentUsername(@NonNull Consumer<Optional<String>> callback) {
    callback.accept(Optional.empty());
  }

  @WorkerThread
  private RecipientId getRecipientId() {
    return SignalDatabase.recipients().getByGroupId(groupId)
                         .orElseThrow(() -> new AssertionError("Recipient ID for Group ID does not exist."));
  }
}