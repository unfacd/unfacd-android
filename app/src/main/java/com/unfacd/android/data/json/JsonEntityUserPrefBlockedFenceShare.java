package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class JsonEntityUserPrefBlockedFenceShare extends JsonEntityBaseUserPref
{
  @JsonProperty
  List<Long> value;

  public JsonEntityUserPrefBlockedFenceShare(Integer id, List<Long> fidsList) {
    super(id);
    this.value = fidsList;
  }
  public List<Long> getValue () {
    return value;
  }

}