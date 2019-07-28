package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class JsonEntityUserPrefNetStateShare extends JsonEntityBaseUserPref
{
  @JsonProperty
  List<String> value;

  public JsonEntityUserPrefNetStateShare (Integer id, List<String> useridsList) {
    super(id);
    this.value = useridsList;
  }
  public List<String> getValue () {
    return value;
  }

}
