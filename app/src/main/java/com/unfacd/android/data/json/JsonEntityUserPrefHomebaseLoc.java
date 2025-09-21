package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityUserPrefHomebaseLoc extends JsonEntityBaseUserPref
{
  @JsonProperty
  String homebaseLoc; //"lat,long:country:region:area:0:"

  public JsonEntityUserPrefHomebaseLoc (Integer id, String homebaseLoc) {
    super(id);
    this.homebaseLoc = homebaseLoc;
  }

  public String getHomebaseLoc() {
    return homebaseLoc;
  }

}