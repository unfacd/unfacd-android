/**
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityPresenceInformation
{

  @JsonProperty
  private String  ufsrvuid;

  @JsonProperty
  private int  status;

  @JsonProperty
  private Long  serviced;

  @JsonProperty
  private Long  suspended;

  public JsonEntityPresenceInformation () {}

  public JsonEntityPresenceInformation (String ufsrvuid) {
    this.ufsrvuid   = ufsrvuid;
  }

  public void setUfsrvuid (String ufsrvuid)
  {
    this.ufsrvuid = ufsrvuid;
  }

  public String getUfsrvuid ()
  {
    return ufsrvuid;
  }

  public int getStatus () {
    return status;
  }

  public Long getServiced () {
    return serviced;
  }

  public Long getSuspended () {
    return suspended;
  }
}
