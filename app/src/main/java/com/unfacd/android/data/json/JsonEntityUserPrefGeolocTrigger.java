package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityUserPrefGeolocTrigger extends JsonEntityBaseUserPref
{
  @JsonProperty
  int geolocTriggerZone;

  public JsonEntityUserPrefGeolocTrigger(Integer id, int geolocTriggerZone) {
    super(id);
    this.geolocTriggerZone = geolocTriggerZone;
  }

  public int getGeolocTrigger() {
    return this.geolocTriggerZone;
  }

}