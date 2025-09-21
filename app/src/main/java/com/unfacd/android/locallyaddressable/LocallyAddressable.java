package com.unfacd.android.locallyaddressable;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.DelimiterUtil;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import androidx.annotation.NonNull;

/**
 * Locally Addressable is an identifier that is saved in the local Recipient db with its own RecipientId
 */
public abstract class LocallyAddressable implements Serializable, Parcelable, Comparable<LocallyAddressable>
{
  static String serializerSeperator = ":";

  RecipientId recipientId;
  AddressableType addressableType;

  public LocallyAddressable (RecipientId recipientId, AddressableType addressableType) {
    this.recipientId      = recipientId;
    this.addressableType  = addressableType;
  }

  public AddressableType getAddressableType() {
    return addressableType;
  }

  public abstract boolean isEmail();
  public abstract boolean isGroup();
  public abstract boolean isFence();
  public abstract  boolean isPhone();
  public abstract boolean isUfsrvUid();
  public abstract boolean isUndefined();

  public enum AddressableType {
    UNDEFINED(0),
    UFSRVUID(1),
    GROUP(2),
    FENCE(3),
    PHONE(4),
    EMAIL(5);

    private int value;

    AddressableType(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public boolean hasSameIdAs (@NonNull LocallyAddressable locallyAddressable) {
    return recipientId.compareTo(locallyAddressable.recipientId) == 0;
  }

  public RecipientId getRecipientId () {
    return recipientId;
  }

  public @NonNull String serialize ()
  {
    return String.valueOf(recipientId);
  }

}
