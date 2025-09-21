package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntitySharedList
{
  @JsonProperty
  Integer type;

  @JsonProperty
  String ufsrvuid;

  @JsonProperty
  Long eid;

  public JsonEntitySharedList () {}

  public JsonEntitySharedList (Integer type, String userId, Long eid) {
    this.type = type;
    this.ufsrvuid = userId;
    this.eid      = eid;
  }

  public Integer getType () {
    return type;
  }

  public String getUfsrvuid () {
    return ufsrvuid;
  }

  public Long getEid () {
    return eid;
  }
}
