package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

@JsonDeserialize(using = FencePreferencesDeserializer.class)
public class JsonEntityFencePreference
{
  @JsonProperty
  String name;

  public JsonEntityFencePreference (String name) {
    this.name = name;
  }

  public String getName () {
    return name;
  }
}