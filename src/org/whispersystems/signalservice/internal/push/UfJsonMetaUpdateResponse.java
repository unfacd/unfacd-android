package org.whispersystems.signalservice.internal.push;

import com.fasterxml.jackson.annotation.JsonProperty;

//  {"status":"ok","updated":2869}
//  {"status":"ok","updated":false}
public class UfJsonMetaUpdateResponse
{

  @JsonProperty
  private String status;

  @JsonProperty
  private boolean updated;

  public String getStatus() {
    return status;
  }

  public boolean getUpdated() {
    return updated;
  }
}
