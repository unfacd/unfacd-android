package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class JsonEntityFencePreferenceGeogroupStickiness extends JsonEntityFencePreference
{
  final static public String FENCEPREF_GEOGROUP_STICKINESS = "sticky_geogroup";

  @JsonProperty
  Boolean value;

  public JsonEntityFencePreferenceGeogroupStickiness (Boolean value) {
    super(FENCEPREF_GEOGROUP_STICKINESS);
    this.value = value;
  }
  public Boolean getValue () {
    return value;
  }

}
