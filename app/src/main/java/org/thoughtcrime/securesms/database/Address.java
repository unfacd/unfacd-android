package org.thoughtcrime.securesms.database;


import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.unfacd.android.ufsrvuid.UfsrvUid;

import org.signal.core.util.logging.Log;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.phonenumbers.NumberUtil;
import org.thoughtcrime.securesms.util.DelimiterUtil;
import org.thoughtcrime.securesms.util.Util;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import androidx.annotation.NonNull;

public class Address implements Parcelable, Comparable<Address> {

  public static final Parcelable.Creator<Address> CREATOR = new Parcelable.Creator<Address>() {
    public Address createFromParcel(Parcel in) {
      return new Address(in);
    }

    public Address[] newArray(int size) {
      return new Address[size];
    }
  };

  public static final Address UNKNOWN = new Address("Unknown");
  public static final Address UFSRV = new Address("0"); //AA+

  private static final String TAG = Log.tag(Address.class);

  private final String address;

  private Address(@NonNull String address) {
    if (address == null) throw new AssertionError(address);
    this.address = address;
  }

  public Address(Parcel in) {
    this(in.readString());
  }

  public static @NonNull Address fromSerialized(@NonNull String serialized) {
    return new Address(serialized);
  }

  public boolean isGroup() {
    return GroupId.isEncodedGroup(address);
  }

  public boolean isEmail() {
    return NumberUtil.isValidEmail(address);
  }

  public boolean isPhone() {
    return !isGroup() && !isEmail();
  }

  public @NonNull String toGroupString() {
    if (!isGroup()) throw new AssertionError("Not group");
    return address;
  }

  public @NonNull String toPhoneString() {
    return address;
  }

  //AA+
  public @NonNull String toE164PhoneString() {
    if (!isPhone()) throw new AssertionError("Not e164: " + address);
    return address;
  }

  public static @NonNull
  List<Address> fromSerializedList(@NonNull String serialized, char delimiter) {
    String[]      escapedAddresses = DelimiterUtil.split(serialized, delimiter);
    List<Address> addresses        = new LinkedList<>();

    for (String escapedAddress : escapedAddresses) {
      if (!TextUtils.isEmpty(escapedAddress)) addresses.add(Address.fromSerialized(DelimiterUtil.unescape(escapedAddress, delimiter))); //AA+ conditional
    }

    return addresses;
  }

  public static @NonNull String toSerializedList(@NonNull List<Address> addresses, char delimiter) {
    Collections.sort(addresses);

    List<String> escapedAddresses = new LinkedList<>();

    for (Address address : addresses) {
      escapedAddresses.add(DelimiterUtil.escape(address.serialize(), delimiter));
    }

    return Util.join(escapedAddresses, delimiter + "");
  }

  //AA not exactly true semantics but will do for now. isPhone cannot be used, as it is multiplexed with ufsrvuid
  public boolean isE164Number() {
    if (address.startsWith("+") && address.length() <= 16)  return true;
    return false;
  }

  //AA+
  public boolean isUndefined ()
  {
    return   address.equals(UfsrvUid.UndefinedUfsrvUid);
  }

//

  public @NonNull String toEmailString() {
    if (!isEmail()) throw new AssertionError("Not email");
    return address;
  }

  @Override
  public @NonNull String toString() {
    return address;
  }

  public String serialize() {
    return address;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (other == null || !(other instanceof Address)) return false;
    return address.equals(((Address) other).address);
  }

  @Override
  public int hashCode() {
    return address.hashCode();
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(address);
  }

  @Override
  public int compareTo(@NonNull Address other) {
    return address.compareTo(other.address);
  }
}