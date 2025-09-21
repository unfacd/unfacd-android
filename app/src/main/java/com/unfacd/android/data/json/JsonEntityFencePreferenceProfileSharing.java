package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class JsonEntityFencePreferenceProfileSharing extends JsonEntityFencePreference
{
  final static public String FENCEPREF_PROFILE_SHARING     = "profile_sharing";

  @JsonProperty
  Boolean value;

  public JsonEntityFencePreferenceProfileSharing (Boolean value) {
    super(FENCEPREF_PROFILE_SHARING);
    this.value = value;
  }
  public Boolean getValue () {
    return value;
  }

}
