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

//Align with FenceRecord.Permission.Type and 'permissionsDbColumnHashMap' in recipient prefs database
public enum EnumFencePermissions
{
  NONE(0),
  PRESENTATION(1), //user can change: group name, banner,
  MEMBERSHIP(2), //user can change group membership: invite others, ban, kick
  MESSAGING(3),
  ATTACHING(4),
  CALLING(5),
  INVALID(6);

  private int value;

  EnumFencePermissions(int value) {
      this.value = value;
    }

  public int getValue() {
      return value;
    }
}
