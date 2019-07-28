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
package org.whispersystems.signalservice.api.push;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A class that represents a contact's registration state.
 */

public class ContactTokenDetails {

  @JsonProperty
  private String  token;

  @JsonProperty
  private String username; //rego name, previously number

  @JsonProperty("ufsrvuid")
  private String ufsrvUid;

  //
  @JsonProperty
  private long  eid;

  @JsonProperty
  private String  nickname;

  @JsonProperty
  private String  e164number;
  //

  public ContactTokenDetails() {}

  public ContactTokenDetails(String username, String ufsrvuid, String nickname) {
    this.username = username;
    this.ufsrvUid = ufsrvuid;
    this.nickname = nickname;
  }

  /**
   * @return The "anonymized" token (truncated hash) that's transmitted to the server.
   */
  public String getToken() {
    return token;
  }

  public void setUsername (String username) {
    this.username = username;
  }

  /**
   * @return This contact's username (e164 formatted number).
   */
  public String getUsername () {
    return username;
  }

  public void setUfsrvUid (String ufsrvUid) {
    this.ufsrvUid = ufsrvUid;
  }

  public String getUfsrvUid () {
    return ufsrvUid;
  }

  public void setNickname (String nickname)
  {
    this.nickname = nickname;
  }

  public String getNickname ()
  {
    return nickname;
  }

  public long getEid () {
    return eid;
  }

  public String getE164number () {
    return e164number;
  }

  public void setE164number (String e164number) {
    this.e164number = e164number;
  }
}
