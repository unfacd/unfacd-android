
package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

//todo: phase out in favour of JsonEntityUserPrefGeoGroupRoaming
public class JsonEntityPreferencesGeoGroups
{
  @JsonProperty
  private boolean roaming;

  @JsonProperty
  private int roamingMode;

  @JsonProperty
  private String localityLevel;

  public JsonEntityPreferencesGeoGroups (boolean roaming, int roamingMode, String localityLevel)
  {
    this.roaming = roaming;
    this.roamingMode = roamingMode;
    this.localityLevel = localityLevel;
  }

  public JsonEntityPreferencesGeoGroups (boolean roaming, int roamingMode)
  {
    this.roaming = roaming;
    this.roamingMode = roamingMode;
  }

  public JsonEntityPreferencesGeoGroups (boolean roaming)
  {
    this.roaming = roaming;
  }

  public boolean isRoaming ()
  {
    return roaming;
  }

  public int getRoamingMode ()
  {
    return roamingMode;
  }

  public String getLocalityLevel ()
  {
    return localityLevel;
  }
}
