package com.unfacd.android.ui.components.intro_contact;

import android.text.TextUtils;

import com.unfacd.android.locallyaddressable.LocallyAddressable;

public class TBDIntroContactDescriptor
{
  final LocallyAddressable addressable;
  final String nickname;
  final String message;
  final String avatarId;
        ResponseStatus responseStatus;
  final IntroDirection  introDirection;
  final long timestampSent;
        long timestampResponse;
  byte[] avatarBlob = null;

  public TBDIntroContactDescriptor (LocallyAddressable addressable, String nickname, String message, String avatarId, IntroDirection introDirection, long timestampSent) {
    this.addressable      = addressable;
    this.nickname         = nickname;
    this.message          = message;
    this.avatarId         = avatarId;
    this.introDirection   = introDirection;
    this.timestampSent    = timestampSent;
  }

  public TBDIntroContactDescriptor (LocallyAddressable addressable, String nickname, String message, String avatarId, IntroDirection introDirection, long timestampSent, ResponseStatus responseStatus, long timestampResponse) {
    this(addressable, nickname, message, avatarId, introDirection, timestampSent);
    this.responseStatus     = responseStatus;
    this.timestampResponse  = timestampResponse;
  }

  public LocallyAddressable getAddressable ()
  {
    return addressable;
  }

  public String getMessage ()
  {
    return message;
  }

  public String getNickname () {
    return nickname;
  }

  public boolean isNicknameProvided () {
    return !TextUtils.isEmpty(nickname);
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

  public boolean isIgnored () {
    return responseStatus == ResponseStatus.IGNORED;
  }

  public boolean isAccepted () {
    return responseStatus == ResponseStatus.ACCEPTED;
  }

  public boolean isRejected () {
    return responseStatus == ResponseStatus.REJECTED;
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