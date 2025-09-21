package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class JsonEntityUserPreferences
{
  @JsonProperty
  JsonEntityUserPrefGeoGroupRoaming geoGroupRoaming;

  @JsonProperty
  JsonEntityUserPrefAvatar avatar;

  @JsonProperty
  JsonEntityUserPrefProfileShare profileShare;

  @JsonProperty
  JsonEntityPreferencesGeoGroups geoGroups;

  @JsonProperty
  List<String> stickyGeogroups;

  @JsonProperty
  private String baseloc;

  public JsonEntityPreferencesGeoGroups getGeoGroups ()
  {
    return geoGroups;
  }

  public String getBaseloc ()
  {
    return baseloc;
  }

  List<String> getStickyGeogroups()
  {
    return this.stickyGeogroups;
  }


  public JsonEntityUserPrefGeoGroupRoaming getGeoGroupRoaming () {
    return geoGroupRoaming;
  }

  public void setStickyGeogroups (List<String> stickyGeogroups) {
    this.stickyGeogroups=stickyGeogroups;
  }

  public JsonEntityUserPrefAvatar getAvatar () {
    return avatar;
  }

  public JsonEntityUserPrefProfileShare getProfileShare () {
    return profileShare;
  }
}
