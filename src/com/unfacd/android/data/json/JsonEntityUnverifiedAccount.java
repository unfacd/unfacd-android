package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;


public class JsonEntityUnverifiedAccount
{

  @JsonProperty
  private String username;

  @JsonProperty
  private String e164number;

  @JsonProperty
  private String password;

  @JsonProperty
  private String nonce;

  @JsonProperty
  private String androidSmsRetriever;

  @JsonProperty
  private String captcha;

  @JsonProperty
  private String challenge;

  public JsonEntityUnverifiedAccount (String username, String e164number, String password, String nonce, String androidSmsRetriever, String captcha, String challenge)
  {
    this.e164number = e164number;
    this.username = username;
    this.password = password;
    this.nonce    = nonce;
    this.androidSmsRetriever = androidSmsRetriever;
    if (captcha != null)  this.captcha = captcha;
    if (challenge != null) this.challenge = challenge;
  }

  String getUsername()
  {
    return this.username;
  }

  String getPassword()
  {
    return this.password;
  }

  String getNonce() {
    return this.nonce;
  }

  String getE164number () {
    return this.e164number;
  }
}
