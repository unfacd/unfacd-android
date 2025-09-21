package com.unfacd.android.locallyaddressable;

import android.os.Parcel;
import android.os.Parcelable;

import org.thoughtcrime.securesms.groups.BadGroupIdException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.recipients.RecipientId;

import androidx.annotation.NonNull;

public class LocallyAddressableGroup extends LocallyAddressable {
  private static final String TAG = Log.tag(LocallyAddressableGroup.class);

  public static final Parcelable.Creator<LocallyAddressableGroup> CREATOR = new Parcelable.Creator<LocallyAddressableGroup>() {
    public LocallyAddressableGroup createFromParcel(Parcel in) {
      return new LocallyAddressableGroup(in);
    }

    public LocallyAddressableGroup[] newArray(int size) {
      return new LocallyAddressableGroup[size];
    }
  };

  private static LocallyAddressableGroup locallyAddressableUndefined = new LocallyAddressableGroup(RecipientId.from(-1), new byte[16]);

  String groupIdEncoded;
  byte[] groupIdRaw;
  GroupId groupId;

  public LocallyAddressableGroup(RecipientId recipientId, byte[] groupIdRaw) {
    super(recipientId, AddressableType.GROUP);
    this.groupIdRaw = groupIdRaw;
    try {
      this.groupIdEncoded = GroupId.v2orThrow(groupIdRaw).toString();
    } catch (AssertionError x) {
      Log.e(TAG, x.getMessage());
      this.groupIdEncoded = GroupId.ENCODED_UNDEFINED_GROUP_PREFIX;
    }

  }

  public LocallyAddressableGroup(Parcel in) {
//    this.groupIdRaw = new byte[in.readInt()];
//    in.readByteArray(this.groupIdRaw);
    this(RecipientId.from(in.readLong()), new byte[in.readInt()]);//todo: load group (see commented)
  }

  public static LocallyAddressable from(RecipientId recipientId, String groupEncoded) {
    try {
      LocallyAddressableGroup locallyAddressableGroup = new LocallyAddressableGroup(recipientId, GroupId.parse(groupEncoded).getDecodedId());
      return locallyAddressableGroup;
    } catch (BadGroupIdException x) {
      Log.e(TAG, x.getMessage());
    }

    return LocallyAddressableGroup.locallyAddressableUndefined;
  }

  @Override
  public int describeContents() {
    return AddressableType.GROUP.getValue();
  }

  @Override
  public boolean isEmail () {
    return false;
  }

  @Override
  public boolean isGroup () {
    return true;
  }

  public boolean isMmsGroup() {
    try {
      return GroupId.parse(groupIdEncoded).isMms();
    } catch (BadGroupIdException x) {
      Log.e(TAG, x.getMessage());
    }

    return false;
  }

  @Override
  public boolean isFence () {
    return false;
  }

  @Override
  public boolean isPhone () {
    return false;
  }

  @Override
  public boolean isUfsrvUid () {
    return false;
  }

  @Override
  public boolean isUndefined () {
    return recipientId.toLong() == -1;
  }

  public LocallyAddressable requireUndefined () {
    return locallyAddressableUndefined;
  }

  @Override
  public @NonNull String toString() {
    return groupIdEncoded;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(recipientId.toLong());
    dest.writeByteArray(groupIdRaw);
  }

  @Override
  public int compareTo (LocallyAddressable locallyAddressable) {
    LocallyAddressableGroup locallyAddressableGroup = (LocallyAddressableGroup)locallyAddressable;
    return this.groupIdEncoded.compareTo(locallyAddressableGroup.groupIdEncoded);
  }

  @Override
  public @NonNull String serialize ()
  {
    return recipientId.serialize() + serializerSeperator + groupId;
  }
}
