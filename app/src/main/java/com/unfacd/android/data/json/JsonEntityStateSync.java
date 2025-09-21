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
import com.unfacd.android.fence.FenceDescriptor;
import com.unfacd.android.location.JsonEntityLocation;


import java.util.List;

public class JsonEntityStateSync
{
    public enum AccountStates {
      ACCOUNTSTATE_UNAUTHENTICATED (0),
      ACCOUNTSTATE_REGISTERED (1),
      ACCOUNTSTATE_VERIFICATION_SENT (3),
      ACCOUNTSTATE_VERIFIED (4);

      private final int stateCode;

      private AccountStates(int stateCode) {
        this.stateCode = stateCode;
      }

      //AccountStates state = AccountState.ACCOUNTSTATE_UNAUTHENTICATED;
      //System.out.println(state.getStateCode());
      public int getStateCode() {
        return this.stateCode;
      }
    }

  @JsonProperty("account_state")
  private boolean accountState;

  @JsonProperty("session_state")
  private int sessionState;

  @JsonProperty("ufsrvuid")
  private String ufsrvUid;

  @JsonProperty
  private String username;

  @JsonProperty
  private String nickname;

  @JsonProperty
  private int queue_size;

  @JsonProperty
  private String sid;

  @JsonProperty
  private String cid;

  @JsonProperty
  JsonEntityLocation location;

  @JsonProperty
  private List<FenceDescriptor> fences;

  @JsonProperty
  private List<FenceDescriptor> fences_invited;

  @JsonProperty
  private List<JsonEntityBaseUserPref> user_prefs; //custom deserialiser

  @JsonProperty
  private List<JsonEntitySharedList> shared_lists;

  public List<FenceDescriptor> getFences()
  {
    return fences;
  }

  public List<FenceDescriptor> getFencesInvited()
  {
    return fences_invited;
  }

  //check UserPrefsDeserializer
  public List<JsonEntityBaseUserPref> getUserPrefs() {
    return user_prefs;
  }

  public int getSessionState() {
    return  sessionState;
  }

  public String getNickname()
  {
    return nickname;
  }

  public String getSid()
  {
    return sid;
  }

  public int getQueue_size()
  {
    return queue_size;
  }

  public String getCid()
  {
    return cid;
  }

  public String getUfsrvUid()
  {
    return ufsrvUid;
  }

  public JsonEntityLocation getLocation()
  {
    return location;
  }

  public String getUsername()
  {
    return username;
  }

  public boolean getAccountState()
  {
    return accountState;
  }

  public List<JsonEntitySharedList> getShared_lists() {
    return shared_lists;
  }

  public JsonEntityStateSync() {
  }

}
