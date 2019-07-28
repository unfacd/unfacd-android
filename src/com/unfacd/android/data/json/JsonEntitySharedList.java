package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntitySharedList
{
  @JsonProperty
  Integer type;

  @JsonProperty
  String ufsrvuid;

  public JsonEntitySharedList () {}

  public JsonEntitySharedList (Integer type, String userId) {
    this.type = type;
    this.ufsrvuid = userId;
  }

  public Integer getType () {
    return type;
  }

  public String getUfsrvuid () {
    return ufsrvuid;
  }
}
