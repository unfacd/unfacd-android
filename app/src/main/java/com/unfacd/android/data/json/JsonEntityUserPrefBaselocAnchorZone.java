package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityUserPrefBaselocAnchorZone extends JsonEntityBaseUserPref
{
  @JsonProperty
  int baselocAnchorZone;

  public JsonEntityUserPrefBaselocAnchorZone(Integer id, int geolocTriggerZone) {
    super(id);
    this.baselocAnchorZone = geolocTriggerZone;
  }

  public int getBaselocAnchorZone() {
    return this.baselocAnchorZone;
  }

}