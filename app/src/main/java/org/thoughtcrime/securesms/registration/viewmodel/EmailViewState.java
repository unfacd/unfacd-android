package org.thoughtcrime.securesms.registration.viewmodel;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

import androidx.annotation.Nullable;

//AA+
public final class EmailViewState implements Parcelable {

  public static final EmailViewState INITIAL = new Builder().build();

  private final String email;

  private EmailViewState(Builder builder) {
    this.email = builder.email;
  }

  public Builder toBuilder() {
    return new Builder().email(email);
  }

  public String getEmail() {
    return email;
  }

  public boolean isValid() {
    return !TextUtils.isEmpty(getEmail()) && android.util.Patterns.EMAIL_ADDRESS.matcher(getEmail()).matches();
  }

  @Override
  public int hashCode() {
    int hash = email != null ? email.hashCode() : 0;
    hash *= 31;
    return hash;
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj == null) return false;
    if (obj.getClass() != getClass()) return false;

    EmailViewState other = (EmailViewState) obj;

    return Objects.equals(other.email, email);
  }

  public static class Builder {
    private String email;

    public Builder email(String email) {
      this.email = email;
      return this;
    }

    public EmailViewState build() {
      return new EmailViewState(this);
    }
  }

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel parcel, int i) {
    parcel.writeString(email);
  }

  public static final Creator<EmailViewState> CREATOR = new Creator<EmailViewState>() {
    @Override
    public EmailViewState createFromParcel(Parcel in) {
      return new Builder().email(in.readString())
                          .build();
    }

    @Override
    public EmailViewState[] newArray(int size) {
      return new EmailViewState[size];
    }
  };
}