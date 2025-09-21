package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

public class VerifyAccountResponse {
  @JsonProperty
  private String uuid;

  @JsonProperty
  private String pni;

  @JsonProperty
  private boolean storageCapable;

  //AA+ ufsrv provided registration data following a successful registration verification (JsonEntityVerifiedAccount)
  @JsonProperty
  private String cookie;

  @JsonProperty
  private long userid;

  @JsonProperty
  private String ufsrvuid;

  @JsonProperty("access_token")
  private String accessToken;

  @JsonProperty("profile_key")
  private String profileKey;

  @JsonProperty("verification_code")
  private String verificationCode;

  @JsonProperty("e164number")
  private String e164Number;

  @JsonProperty("username")
  private String username;

  public String getCookie () {
    return this.cookie;
  }

  public long getUid () {
    return this.userid;
  }

  public String getUfsrvuid () {
    return ufsrvuid;
  }

  public String getAccessToken () {
    return accessToken;
  }

  public String getProfileKey () {
    return profileKey;
  }

  public String getVerificationCode () {
    return verificationCode;
  }

  public String getE164Number () {
    return e164Number;
  }

  public String getUsername () {
    return username;
  }
  //

  public String getUuid() {
    return uuid;
  }

  public boolean isStorageCapable() {
    return storageCapable;
  }

  public String getPni() {
    return pni;
  }
}