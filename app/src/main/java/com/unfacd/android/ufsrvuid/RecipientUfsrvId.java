package com.unfacd.android.ufsrvuid;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.DelimiterUtil;
import org.thoughtcrime.securesms.util.Util;

import java.util.ArrayList;
import java.util.List;

public final class RecipientUfsrvId implements Parcelable, Comparable<RecipientUfsrvId> {

  private static final long UNKNOWN_ID = -1;
  private static final char DELIMITER = ',';

  public static final RecipientUfsrvId UNKNOWN = RecipientUfsrvId.from(UNKNOWN_ID);

  private final long id;

  public static RecipientUfsrvId from(long id) {
    return new RecipientUfsrvId(id);
  }

  public static RecipientUfsrvId from(@NonNull String id) {
    return RecipientUfsrvId.from(Long.parseLong(id));
  }

  private RecipientUfsrvId(long id) {
    this.id = id;
  }

  private RecipientUfsrvId(Parcel in) {
    id = in.readLong();
  }

  public static @NonNull String toSerializedList(@NonNull List<RecipientId> ids) {
    return Util.join(Stream.of(ids).map(RecipientId::serialize).toList(), String.valueOf(DELIMITER));
  }

  public static List<RecipientId> fromSerializedList(@NonNull String serialized) {
    String[]          stringIds = DelimiterUtil.split(serialized, DELIMITER);
    List<RecipientId> out       = new ArrayList<>(stringIds.length);

    for (String stringId : stringIds) {
      RecipientId id = RecipientId.from(Long.parseLong(stringId));
      out.add(id);
    }

    return out;
  }

  public boolean isUnknown() {
    return id == UNKNOWN_ID;
  }

  public @NonNull String serialize() {
    return String.valueOf(id);
  }

  public @NonNull String toQueueKey() {
    return "RecipientUfsrvId.java::" + id;
  }

  //AA+
  public long toId() {
    return id;
  }

  @Override
  public @NonNull String toString() {
    return "RecipientUfsrvId.java::" + id;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    RecipientUfsrvId that = (RecipientUfsrvId) o;

    return id == that.id;
  }

  @Override
  public int hashCode() {
    return (int) (id ^ (id >>> 32));
  }

  @Override
  public int compareTo(RecipientUfsrvId o) {
    return Long.compare(this.id, o.id);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(id);
  }

  public static final Creator<RecipientUfsrvId> CREATOR = new Creator<RecipientUfsrvId>() {
    @Override
    public RecipientUfsrvId createFromParcel(Parcel in) {
      return new RecipientUfsrvId(in);
    }

    @Override
    public RecipientUfsrvId[] newArray(int size) {
      return new RecipientUfsrvId[size];
    }
  };
}