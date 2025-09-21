package com.unfacd.android.locallyaddressable;

import android.os.Parcel;
import android.os.Parcelable;

import org.thoughtcrime.securesms.recipients.RecipientId;

import androidx.annotation.NonNull;

public class LocallyAddressableUndefined extends LocallyAddressable {
  public static final Parcelable.Creator<LocallyAddressableUndefined> CREATOR = new Parcelable.Creator<LocallyAddressableUndefined>() {
    public LocallyAddressableUndefined createFromParcel(Parcel in) {
      return new LocallyAddressableUndefined(in);
    }

    public LocallyAddressableUndefined[] newArray(int size) {
      return new LocallyAddressableUndefined[size];
    }
  };

  private static LocallyAddressableUndefined locallyAddressableUndefined = new LocallyAddressableUndefined();

  LocallyAddressableUndefined () {
    super(RecipientId.from(-1), AddressableType.UNDEFINED);
  }

  public LocallyAddressableUndefined (Parcel in) {
    this();
  }

  @Override
  public int describeContents() {
    return AddressableType.UNDEFINED.getValue();
  }

  @Override
  public boolean isEmail () {
    return false;
  }

  @Override
  public boolean isGroup () {
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
    return true;
  }

  public LocallyAddressable requireUndefined () {
    return locallyAddressableUndefined;
  }

  @Override
  public @NonNull String toString() {
    return "";
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(recipientId.toLong());
  }

  @Override
  public int compareTo (LocallyAddressable locallyAddressable) {
    LocallyAddressableUndefined locallyAddressableUndefined= (LocallyAddressableUndefined)locallyAddressable;
    return this.recipientId.compareTo(locallyAddressableUndefined.recipientId);
  }

  @Override
  public @NonNull String serialize ()
  {
    return recipientId.serialize() + serializerSeperator + AddressableType.UNDEFINED;
  }

  public static LocallyAddressable require() {
    return locallyAddressableUndefined;
  }
}
