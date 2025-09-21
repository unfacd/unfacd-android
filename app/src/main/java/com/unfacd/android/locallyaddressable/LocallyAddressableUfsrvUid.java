package com.unfacd.android.locallyaddressable;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.DelimiterUtil;

import java.util.LinkedList;
import java.util.List;

import androidx.annotation.NonNull;

public class LocallyAddressableUfsrvUid extends LocallyAddressable {
  public static final Parcelable.Creator<LocallyAddressableUfsrvUid> CREATOR = new Parcelable.Creator<LocallyAddressableUfsrvUid>() {
    public LocallyAddressableUfsrvUid createFromParcel(Parcel in) {
      return new LocallyAddressableUfsrvUid(in);
    }

    public LocallyAddressableUfsrvUid[] newArray(int size) {
      return new LocallyAddressableUfsrvUid[size];
    }
  };

  public static LocallyAddressableUfsrvUid locallyAddressableUndefined = new LocallyAddressableUfsrvUid(RecipientId.from(-1), UfsrvUid.undefinedUfsrvUid);

  UfsrvUid ufsrvUid;

  public  LocallyAddressableUfsrvUid (RecipientId recipientId, UfsrvUid ufsrvUid) {
    super(recipientId, AddressableType.UFSRVUID);
    this.ufsrvUid = ufsrvUid;
  }

  public  LocallyAddressableUfsrvUid(Parcel in) {
    this(RecipientId.from(in.readLong()), UfsrvUid.fromEncoded(in.readString()));
  }

  @Override
  public int describeContents() {
    return AddressableType.UFSRVUID.getValue();
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
    return true;
  }

  @Override
  public boolean isUndefined () {
    return recipientId.toLong() == -1;
  }

  static public LocallyAddressableUfsrvUid requireUndefined () {
    return locallyAddressableUndefined;
  }

  @Override
  public @NonNull String toString() {
    return ufsrvUid.toString();
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(recipientId.toLong());
    dest.writeString(ufsrvUid.toString());
  }

  @Override
  public int compareTo (LocallyAddressable locallyAddressable) {
    LocallyAddressableUfsrvUid locallyAddressableUfsrvUid = (LocallyAddressableUfsrvUid)locallyAddressable;
    return this.ufsrvUid.toString().compareTo(locallyAddressableUfsrvUid.ufsrvUid.toString());
  }

  @Override
  public @NonNull String serialize ()
  {
    return recipientId.serialize() + serializerSeperator + ufsrvUid;
  }

  public static LocallyAddressableUfsrvUid from (RecipientId recipientId, UfsrvUid ufsrvUid) {
    return new LocallyAddressableUfsrvUid(recipientId, ufsrvUid);
  }

  public static LocallyAddressableUfsrvUid from (RecipientId recipientId, String ufsrvUidEncoded) {
    return new LocallyAddressableUfsrvUid(recipientId, UfsrvUid.fromEncoded(ufsrvUidEncoded));
  }

  public static LocallyAddressableUfsrvUid from (String ufsrvUidEncoded) {
    return new LocallyAddressableUfsrvUid(RecipientId.UNKNOWN, UfsrvUid.fromEncoded(ufsrvUidEncoded));
  }

  public static @NonNull
  List<LocallyAddressableUfsrvUid> fromSerializedList(@NonNull String serialized, char delimiter) {
    String[]      escapedAddresses = DelimiterUtil.split(serialized, delimiter);
    List<LocallyAddressableUfsrvUid> addresses        = new LinkedList<>();

    for (String escapedAddress : escapedAddresses) {
      if (!TextUtils.isEmpty(escapedAddress)) addresses.add(LocallyAddressableUfsrvUid.from(RecipientId.UNKNOWN, escapedAddress)); //AA+ conditional
    }

    return addresses;
  }
}
