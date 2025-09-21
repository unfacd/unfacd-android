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

package com.unfacd.android.preferences;

import android.content.Context;

import java.util.List;
import java.util.Set;

public interface IDynamicProvider {

  public int getCount ();

  /**
   *
   * @param customId User defined Id
   */
  public void setCustomId (String customId);

  /**
   *
   * @param selectedCriterion User define selection criteria used to determine which items can
   *                          be marked as "selected"
   *
   */
  public void setSelectedCriterion (int selectedCriterion);

  public <T> List<T> getItems ();

  /**
   *
   * @return The items returned in a way suitable for Ui listing
   */
  public <T> List<T> getItemsForDisplay ();

  /**
   *
   * @return The items indexed on Id
   */
  public <T> List<T> getItemsForId ();

  public <T> List<T> getSelectedItems (Context context);

  public void populate (Context context);

  public void setIgnoredMembers (Set<Long> ignoredMembers);

}