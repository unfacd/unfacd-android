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

package com.unfacd.android.fence;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.unfacd.android.data.json.JsonEntityBaseUserPref;
import com.unfacd.android.data.json.JsonEntityFencePreference;

import org.thoughtcrime.securesms.recipients.Recipient;
import java.util.Optional;

import java.util.List;

public class FenceDescriptor
{
  @JsonProperty
  private long fid;

  @JsonProperty
  private long eid;

  @JsonProperty
  private String fname; /*title|dname*/

  @JsonProperty
  private String fcname;

  @JsonProperty("fkey")
  private String fenceKey;

  @JsonProperty
  private int type;

  @JsonProperty
  private int privacy_mode;

  @JsonProperty
  private int delivery_mode;

  @JsonProperty
  private int join_mode;

  @JsonProperty
  private List<Recipient> fmembers;

  @JsonProperty
  private List<Recipient> fmembersInvited;

  @JsonProperty
  private  List<String> members;

  @JsonProperty
  private List<JsonEntityFencePreference> fence_preferences; //custom deserialiser

  @JsonProperty
  private String       avatar;//base64 encoded
  @JsonProperty
  private long         avatarId;
  @JsonProperty
  private String       avatarKey;//base64 cnvert to byte[]
  @JsonProperty
  private String       avatarContentType;

  @JsonProperty
  private double        longitude;
  @JsonProperty
  private  double        latitude;
  @JsonProperty
  private  int           maxmembers;
  @JsonProperty
  private long          ttl;

  @JsonProperty
  private int          userCount;

  @JsonProperty
  private boolean isInvited;

  public int getUserCount ()
  {
    return userCount;
  }

  public FenceDescriptor ()
    {
    }


  public FenceDescriptor (String fname, Optional<List<Recipient>> members)
  {
    this.fname=fname;
    if (members.isPresent())
    {
      this.fmembers=members.get();
    }
  }

  public List<Recipient> getFmembersInvited ()
  {
    return fmembersInvited;
  }

  public boolean isInvited ()
  {
    return isInvited;
  }

  public List<String> getMembers() {
    return members;
  }

  public String getFname ()
  {
    return fname;
  }

  public String getFenceKey ()
  {
    return fenceKey;
  }

  public String getFcname ()
  {
    return fcname;
  }

  public int getType ()
  {
    return type;
  }

  public int getPrivacy_mode ()
  {
    return privacy_mode;
  }

  public int getDelivery_mode ()
  {
    return delivery_mode;
  }

  public int getJoin_mode ()
  {
    return join_mode;
  }

  public long getFid ()
  {
    return fid;
  }

  public List<Recipient> getFmembers ()
  {
    return fmembers;
  }

  public double getLatitude ()
  {
    return latitude;
  }

  public double getLongitude ()
  {
    return longitude;
  }

  public int getMaxmembers ()
  {
    return maxmembers;
  }

  public long getTtl ()
  {
    return ttl;
  }

  public long getEid ()
  {
    return eid;
  }

  public String getAvatar ()
  {
    return avatar;
  }

}
