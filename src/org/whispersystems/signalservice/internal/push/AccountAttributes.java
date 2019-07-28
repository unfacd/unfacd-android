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
package org.whispersystems.signalservice.internal.push;

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
  private byte[] unidentifiedAccessKey;

  @JsonProperty
  private boolean unrestrictedUnidentifiedAccess;

  //
  @JsonProperty
  private String verificationCode;

  //
  @JsonProperty
  private String e164number;

  //
  @JsonProperty
  private String username;

  //
  @JsonProperty
  private String cookie;

  //
  @JsonProperty ("profile_key")
  private byte[] profileKey;

  public AccountAttributes(String signalingKey, int registrationId, boolean fetchesMessages, String verificationCode, String pin, byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess,
                           String cookie, String username, String e164number, byte[] profileKey) {
    this.signalingKey    = signalingKey;
    this.registrationId  = registrationId;
    this.voice           = true;
    this.video           = true;
    this.fetchesMessages = fetchesMessages;
    this.pin             = pin;
    this.unidentifiedAccessKey          = unidentifiedAccessKey;
    this.unrestrictedUnidentifiedAccess = unrestrictedUnidentifiedAccess;

    //
    this.verificationCode = pin;//verificationCode;todo: separate pin from verification code
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

  public byte[] getUnidentifiedAccessKey() {
    return unidentifiedAccessKey;
  }

  public boolean isUnrestrictedUnidentifiedAccess() {
    return unrestrictedUnidentifiedAccess;
  }

  //
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
}
