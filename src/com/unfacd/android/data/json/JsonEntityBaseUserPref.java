package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.unfacd.android.data.json.JsonEntityFence;
import com.unfacd.android.location.JsonEntityLocation;

import org.thoughtcrime.securesms.push.SignalServiceNetworkAccess;

import java.util.List;

@JsonDeserialize(using = UserPrefsDeserializer.class)
public class JsonEntityBaseUserPref
{
  @JsonProperty
  Integer id;

  @JsonProperty
  String name;

  public JsonEntityBaseUserPref (Integer id) {
    this.id = id;
  }

  public JsonEntityBaseUserPref (Integer id, String name) {
    this.id   = id;
    this.name = name;
  }

  public Integer getId () {
    return id;
  }

  public String getName () {
    return name;
  }
}