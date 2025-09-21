package org.thoughtcrime.securesms.contacts.avatars;


import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.database.SignalDatabase;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.signal.core.util.Conversions;
import java.util.Optional;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;

import javax.annotation.Nonnull;

public final class GroupRecordContactPhoto implements ContactPhoto {

  private final GroupId groupId;
  private final long    avatarId;  //AA this value is defunct under ufsrv
  private final @Nonnull String avatarUfId;

  public GroupRecordContactPhoto(@NonNull GroupId  groupId, long avatarId, @NonNull String avatarUfId) { //AA+) {
    this.groupId  = groupId;
    this.avatarId = avatarId;
    this.avatarUfId = avatarUfId;
  }

  @Override
  public InputStream openInputStream(Context context) throws IOException {
    GroupDatabase                       groupDatabase = SignalDatabase.groups();
    Optional<GroupDatabase.GroupRecord> groupRecord   = groupDatabase.getGroup(groupId);

    if (!groupRecord.isPresent() || !AvatarHelper.hasAvatar(context, groupRecord.get().getRecipientId())) {
      throw new IOException("No avatar for group: " + groupId);
    }

    return AvatarHelper.getAvatar(context, groupRecord.get().getRecipientId());
  }

  @Override
  public @Nullable Uri getUri(@NonNull Context context) {
    return null;
  }

  @Override
  public boolean isProfilePhoto() {
    return false;
  }

  @Override
  public void updateDiskCacheKey(MessageDigest messageDigest) {
    messageDigest.update(groupId.toString().getBytes());
    messageDigest.update(Conversions.stringToByteArray(avatarUfId));//AA+
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof GroupRecordContactPhoto)) return false;

    GroupRecordContactPhoto that = (GroupRecordContactPhoto)other;
    return this.groupId.equals(that.groupId) && this.avatarUfId.equals(that.avatarUfId);//AA+
  }

  @Override
  public int hashCode() {
    return this.groupId.hashCode() ^ avatarUfId.hashCode();//AA+
  }

}