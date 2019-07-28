package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityUserPrefGeoGroupRoaming extends JsonEntityBaseUserPref
{
  @JsonProperty
  int roamingMode;

  public JsonEntityUserPrefGeoGroupRoaming (Integer id, int roamingMode) {
    super(id);
    this.roamingMode = roamingMode;
  }

  public int getRoamingMode () {
    return this.roamingMode;
  }

}