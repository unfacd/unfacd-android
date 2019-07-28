package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityUserPrefAvatar extends JsonEntityBaseUserPref
{
  @JsonProperty
  String value;

  public JsonEntityUserPrefAvatar (Integer id, String avatar) {
    super(id);
    this.value = avatar;
  }

  public String getValue () {
    return value;
  }

}
