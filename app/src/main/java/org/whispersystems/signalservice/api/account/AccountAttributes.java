/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.signalservice.api.account;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class AccountAttributes {

  @JsonProperty
  private String  signalingKey;

  @JsonProperty
  private int     registrationId;

  @JsonProperty
  private boolean voice;

  @JsonProperty
  private boolean video;

  @JsonProperty
  private boolean fetchesMessages;

  @JsonProperty
  private String pin;

  @JsonProperty
  private String registrationLock;

  @JsonProperty
  private byte[] unidentifiedAccessKey;

  @JsonProperty
  private boolean unrestrictedUnidentifiedAccess;

  @JsonProperty
  private boolean discoverableByPhoneNumber;

  //AA+
  @JsonProperty
  private String verificationCode;

  //AA+
  @JsonProperty
  private String e164number;

  //AA+
  @JsonProperty
  private String username;

  //AA+
  @JsonProperty
  private String cookie;

  //AA+
  @JsonProperty ("profile_key")
  private byte[] profileKey;

  @JsonProperty
  private Capabilities capabilities;

  @JsonProperty
  private String name;

  public AccountAttributes(String signalingKey,
                           int registrationId,
                           boolean fetchesMessages,
                           String verificationCode,
                           String pin,
                           String registrationLock,
                           byte[] unidentifiedAccessKey,
                           boolean unrestrictedUnidentifiedAccess,
                           Capabilities capabilities,
                           boolean discoverableByPhoneNumber,
                           String name,
                           String cookie,
                           String username,
                           String e164number,
                           byte[] profileKey) {
    this.signalingKey    = signalingKey;
    this.registrationId  = registrationId;
    this.voice           = true;
    this.video           = true;
    this.fetchesMessages = fetchesMessages;
    this.pin             = pin;
    this.registrationLock               = registrationLock;
    this.unidentifiedAccessKey          = unidentifiedAccessKey;
    this.unrestrictedUnidentifiedAccess = unrestrictedUnidentifiedAccess;
    this.capabilities                   = capabilities;
    this.discoverableByPhoneNumber      = discoverableByPhoneNumber;
    this.name                           = name;

    //AA+
    this.verificationCode = verificationCode;
    this.cookie           = cookie;
    this.e164number       = e164number;
    this.username         = username;
    this.profileKey       = profileKey;
  }

  public AccountAttributes() {}

  public String getSignalingKey() {
    return signalingKey;
  }

  public int getRegistrationId() {
    return registrationId;
  }

  public boolean isFetchesMessages() {
    return fetchesMessages;
  }

  public String getPin() {
    return pin;
  }

  public String getRegistrationLock() {
    return registrationLock;
  }

  public byte[] getUnidentifiedAccessKey() {
    return unidentifiedAccessKey;
  }

  public boolean isUnrestrictedUnidentifiedAccess() {
    return unrestrictedUnidentifiedAccess;
  }

  public boolean isDiscoverableByPhoneNumber() {
    return discoverableByPhoneNumber;
  }

  public Capabilities getCapabilities() {
    return capabilities;
  }

  public String getName() {
    return name;
  }

  //AA+
  public String getVerificationCode() {
    return verificationCode;
  }

  public String getCookie() {
    return cookie;
  }

  public byte[] getProfileKey() {
    return profileKey;
  }

  public String getE164number () {
    return e164number;
  }

  public String getUsername () {
    return username;
  }

  //

  public static class Capabilities {
    @JsonProperty
    private boolean uuid;

    @JsonProperty("gv2-3")
    private boolean gv2;

    @JsonProperty
    private boolean storage;

    @JsonProperty
    private boolean senderKey;

    @JsonProperty
    private boolean announcementGroup;

    @JsonProperty
    private boolean changeNumber;

    @JsonProperty
    private boolean stories;

    @JsonCreator
    public Capabilities() {}

    public Capabilities(boolean uuid, boolean gv2, boolean storage,/* boolean gv1Migration,*/ boolean senderKey, boolean announcementGroup, boolean changeNumber, boolean stories)
    {
      this.uuid = uuid;
      this.gv2 = gv2;
      this.storage = storage;
//      this.gv1Migration = gv1Migration;
      this.senderKey = senderKey;
      this.announcementGroup = announcementGroup;
      this.changeNumber = changeNumber;
      this.stories = stories;
    }

    public boolean isUuid() {
      return uuid;
    }

    public boolean isGv2() {
      return gv2;
    }

    public boolean isStorage() {
      return storage;
    }

    public boolean isSenderKey() {
      return senderKey;
    }

    public boolean isAnnouncementGroup() {
      return announcementGroup;
    }

    public boolean isChangeNumber() {
      return changeNumber;
    }

    public boolean isStories() {
      return stories;
    }

  }
}
