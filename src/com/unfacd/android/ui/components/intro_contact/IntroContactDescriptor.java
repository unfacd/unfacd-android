package com.unfacd.android.ui.components.intro_contact;

import org.thoughtcrime.securesms.database.Address;

public class IntroContactDescriptor {
  final Address address;
  final String message;
  final String avatarId;
        ResponseStatus responseStatus;
  final IntroDirection  introDirection;
  final long timestampSent;
        long timestampResponse;
  byte[] avatarBlob = null;

  public IntroContactDescriptor (Address address, String message, String avatarId, IntroDirection introDirection, long timestampSent) {
    this.address          = address;
    this.message          = message;
    this.avatarId         = avatarId;
    this.introDirection   = introDirection;
    this.timestampSent    = timestampSent;
  }

  public IntroContactDescriptor (Address address, String message, String avatarId, IntroDirection introDirection, long timestampSent, ResponseStatus responseStatus, long timestampResponse) {
    this(address, message, avatarId, introDirection, timestampSent);
    this.responseStatus     = responseStatus;
    this.timestampResponse  = timestampResponse;
  }

  public Address getAddress ()
  {
    return address;
  }

  public String getMessage ()
  {
    return message;
  }

  public String getAvatarId ()
  {
    return avatarId;
  }

  public ResponseStatus getResponseStatus ()
  {
    return responseStatus;
  }

  public IntroDirection getIntroDirection ()
  {
    return introDirection;
  }

  public long getTimestampSent ()
  {
    return timestampSent;
  }

  public long getTimestampResponse ()
  {
    return timestampResponse;
  }

  public byte[] getAvatarBlob ()
  {
    return avatarBlob;
  }

  public void setResponse (ResponseStatus responseStatus, long timestampResponse)
  {
    this.responseStatus = responseStatus;
    this.timestampResponse  = timestampResponse;
  }

  public void setAvatarBlob (byte[] avatar)
  {
    this.avatarBlob = avatar;
  }

  public enum ResponseStatus {
    UNSENT(0),//default
    ACCEPTED(1),
    REJECTED(2),
    IGNORED(3),
    UNSEEN(4),
    SENT(5);

    private int value;

    ResponseStatus(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }

  public enum IntroDirection {
    INCOMING(0),
    OUTGOING(1);

    private int value;

    IntroDirection(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }
  }
}