package com.unfacd.android.locallyaddressable;

import android.os.Parcel;
import android.os.Parcelable;

import org.thoughtcrime.securesms.recipients.RecipientId;

import androidx.annotation.NonNull;

public class LocallyAddressablePhoneNumber extends LocallyAddressable {
  public static final Parcelable.Creator<LocallyAddressablePhoneNumber> CREATOR = new Parcelable.Creator<LocallyAddressablePhoneNumber>() {
    public LocallyAddressablePhoneNumber createFromParcel(Parcel in) {
      return new LocallyAddressablePhoneNumber(in);
    }

    public LocallyAddressablePhoneNumber[] newArray(int size) {
      return new LocallyAddressablePhoneNumber[size];
    }
  };

  private static LocallyAddressablePhoneNumber locallyAddressableUndefined = new LocallyAddressablePhoneNumber(RecipientId.from(-1), "+0");

  String e164PhoneNumber;

  public  LocallyAddressablePhoneNumber (RecipientId recipientId, String e164PhoneNumber) {
    super(recipientId, AddressableType.PHONE);
    if (e164PhoneNumber.startsWith("+") || e164PhoneNumber.length() <= 16) {
      this.e164PhoneNumber = e164PhoneNumber;
    } else throw new AssertionError("Malformatted e164 number: " + e164PhoneNumber);
  }

  public  LocallyAddressablePhoneNumber(Parcel in) {
    this(RecipientId.from(in.readLong()), in.readString());
  }

  @Override
  public int describeContents() {
    return AddressableType.PHONE.getValue();
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
    return true;
  }

  @Override
  public boolean isUfsrvUid () {
    return true;
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
    return e164PhoneNumber;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(recipientId.toLong());
    dest.writeString(e164PhoneNumber);
  }

  @Override
  public int compareTo (LocallyAddressable locallyAddressable) {
    LocallyAddressablePhoneNumber locallyAddressablePhone = (LocallyAddressablePhoneNumber)locallyAddressable;
    return this.e164PhoneNumber.compareTo(locallyAddressablePhone.e164PhoneNumber);
  }

  @Override
  public @NonNull String serialize ()
  {
    return recipientId.serialize() + serializerSeperator + e164PhoneNumber;
  }

  public static LocallyAddressablePhoneNumber from (RecipientId recipientId, String e164PhoneNumber) {
    return new LocallyAddressablePhoneNumber(recipientId, e164PhoneNumber);
  }
}
