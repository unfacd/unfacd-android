package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.unfacd.android.location.JsonEntityLocation;

public class JsonEntityProfile {

  @JsonProperty
  private String username; //rego name

  @JsonProperty("ufsrvuid")
  private String ufsrvUid;

  //AA+
  @JsonProperty
  private long  eid;

  @JsonProperty
  private String  avatar;

  @JsonProperty
  private String  nickname;

  @JsonProperty
  private String  e164number;

  @JsonProperty("profile_key")
  private String  profileKey;

  @JsonProperty
  JsonEntityLocation location;

  @JsonProperty
  private byte[] commitment;

  @JsonProperty
  private String version;

  public JsonEntityProfile() {}

  public JsonEntityProfile(String username, String ufsrvuid, String nickname) {
    this.username = username;
    this.ufsrvUid = ufsrvuid;
    this.nickname = nickname;
  }

  public void setUsername (String username) {
    this.username = username;
  }

  public String getUsername () {
    return username;
  }

  public void setUfsrvUid (String ufsrvUid) {
    this.ufsrvUid = ufsrvUid;
  }

  public String getUfsrvUid () {
    return ufsrvUid;
  }

  public void setNickname (String nickname)
  {
    this.nickname = nickname;
  }

  public String getNickname ()
  {
    return nickname;
  }

  public long getEid () {
    return eid;
  }

  public String getE164number () {
    return e164number;
  }

  public void setE164number (String e164number) {
    this.e164number = e164number;
  }

  public String getAvatar () {
    return avatar;
  }

  public String getProfileKey () {
    return profileKey;
  }

  public void setCommitment(byte[] commitment) {
    this.commitment = commitment;
  }

  public void setVersion(String version) {
    this.version = version;
  }
}