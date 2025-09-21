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

package com.unfacd.android.ufsrvcmd.events;


import com.unfacd.android.ufsrvcmd.UfsrvCommandEvent;

public class AppEventPrefUserAvatar extends UfsrvCommandEvent
{
  private final String avatarUfrsvId;
  private final Long uid;

  public AppEventPrefUserAvatar (Long uid, String avatarUfrsvId)
  {
    this.uid = uid;
    this.avatarUfrsvId = avatarUfrsvId;
  }

  public Long getUid ()
  {
    return this.uid;
  }

  public String getAvatarUfrsvId ()
  {
    return avatarUfrsvId;
  }
}
