
package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class JsonEntityPresenceInformationList
{

  @JsonProperty
  private List<JsonEntityPresenceInformation> presence;

  public JsonEntityPresenceInformationList () {}

  public List<JsonEntityPresenceInformation> getSharingList() {
    return presence;
  }
}
