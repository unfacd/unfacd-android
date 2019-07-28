package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityUserPrefE164Number extends JsonEntityBaseUserPref
{
  @JsonProperty
  String value;

  public JsonEntityUserPrefE164Number (Integer id, String e164Number) {
    super(id);
    this.value = e164Number;
  }

  public String getValue () {
    return value;
  }

}