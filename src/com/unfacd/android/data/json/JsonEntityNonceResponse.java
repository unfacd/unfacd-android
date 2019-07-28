package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;
//{"status":"ok","controller":"user","nonce":"3198a2082a"}
public class JsonEntityNonceResponse
{

  @JsonProperty
  private String status;

  @JsonProperty
  private String nonce;

  public String getStatus() {
    return status;
  }

  public String getNonce() {
    return nonce;
  }
}
