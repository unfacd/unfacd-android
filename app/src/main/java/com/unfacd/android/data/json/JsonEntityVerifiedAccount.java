/**
 * Copyright (C) 2015-2019 unfacd works
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.unfacd.android.data.json;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JsonEntityVerifiedAccount
{
  @JsonProperty
  private String cookie;

  @JsonProperty
  private long userid;

  @JsonProperty
  private String ufsrvuid;

  @JsonProperty("access_token")
  private String accessToken;

  @JsonProperty("profile_key")
  private String profileKey;

  @JsonProperty("verification_code")
  private String verificationCode;

  @JsonProperty("e164number")
  private String e164Number;

  @JsonProperty("username")
  private String username;

  @JsonProperty("uuid")
  private String uuid;

  public String getCookie () {
    return this.cookie;
  }

  public long getUid () {
    return this.userid;
  }

  public String getUfsrvuid () {
    return ufsrvuid;
  }

  public String getAccessToken () {
    return accessToken;
  }

  public String getProfileKey () {
    return profileKey;
  }

  public String getVerificationCode () {
    return verificationCode;
  }

  public String getE164Number () {
    return e164Number;
  }

  public String getUsername () {
    return username;
  }

  public String getUuid () {
    return uuid;
  }
}
