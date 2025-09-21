package com.unfacd.android.locallyaddressable;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Patterns;

import org.thoughtcrime.securesms.phonenumbers.NumberUtil;
import org.thoughtcrime.securesms.recipients.RecipientId;

import java.util.regex.Pattern;

import androidx.annotation.NonNull;

public class LocallyAddressableEmail extends LocallyAddressable {
  private static String UNDEFINED_EMAIL = "0@0";

  public static final Parcelable.Creator<LocallyAddressableEmail> CREATOR = new Parcelable.Creator<LocallyAddressableEmail>() {
    public LocallyAddressableEmail createFromParcel(Parcel in) {
      return new LocallyAddressableEmail(in);
    }

    public LocallyAddressableEmail[] newArray(int size) {
      return new LocallyAddressableEmail[size];
    }
  };

  private static LocallyAddressableEmail locallyAddressableUndefined = new LocallyAddressableEmail(RecipientId.requireUnknown(), UNDEFINED_EMAIL);

  String email;

  public  LocallyAddressableEmail (RecipientId recipientId, String email) {
    super(recipientId, AddressableType.EMAIL);
    if (NumberUtil.isValidEmail(email)) {
      this.email = email;
    } else if (!recipientId.isUnknown()) throw new AssertionError("Malformatted email: " + email);
  }

  public  LocallyAddressableEmail(Parcel in) {
    this(RecipientId.from(in.readLong()), in.readString());
  }

  @Override
  public int describeContents() {
    return AddressableType.EMAIL.getValue();
  }

  @Override
  public boolean isEmail () {
    return true;
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
    return recipientId.toLong() == -1;
  }

  public LocallyAddressable requireUndefined () {
    return locallyAddressableUndefined;
  }

  @Override
  public @NonNull String toString() {
    return email;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(recipientId.toLong());
    dest.writeString(email);
  }

  @Override
  public int compareTo (LocallyAddressable locallyAddressable) {
    LocallyAddressableEmail locallyAddressableEmail = (LocallyAddressableEmail)locallyAddressable;
    return this.email.compareTo(locallyAddressableEmail.email);
  }

  @Override
  public @NonNull String serialize ()
  {
    return recipientId + serializerSeperator + email;
  }

  public static LocallyAddressableEmail from (RecipientId recipientId, String email) {
    return new LocallyAddressableEmail(recipientId, email);
  }

  public static boolean isValidEmail (String email) {
    Pattern pattern = Patterns.EMAIL_ADDRESS;
    return pattern.matcher(email).matches();
  }
}
