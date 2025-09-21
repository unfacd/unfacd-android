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

/**
 * Raw server response to pref store request
 */

public class JsonEntityPreferenceSetResponse
{
  @JsonProperty
  String name;

  @JsonProperty
  int id;

  @JsonProperty
   boolean  value_previous;

  @JsonProperty
  boolean value;

  @JsonProperty
  boolean value_type;

  public String getName ()
  {
    return name;
  }

  public int getId ()
  {
    return id;
  }

  public boolean isValue_previous ()
  {
    return value_previous;
  }

  public boolean isValue ()
  {
    return value;
  }

  public boolean isValue_type ()
  {
    return value_type;
  }
}
