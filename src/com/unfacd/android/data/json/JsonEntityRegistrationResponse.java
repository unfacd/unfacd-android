package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityRegistrationResponse
{

  @JsonProperty
  private long userid;

  @JsonProperty
  private String status;

  @JsonProperty
  private String cookie;

  public String getStatus() {
    return status;
  }

  public String getCookie() {
    return cookie;
  }

  public long getUserid() {return userid;}
}
