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

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.unfacd.android.R;

import org.thoughtcrime.securesms.preferences.CorrectedPreferenceFragment;

import java.util.Arrays;

public abstract class ListSummaryPreferenceFragment extends CorrectedPreferenceFragment
{

  protected class ListSummaryListener implements Preference.OnPreferenceChangeListener {
    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
      ListPreference listPref   = (ListPreference) preference;
      int            entryIndex = Arrays.asList(listPref.getEntryValues()).indexOf(value);

      listPref.setSummary(entryIndex >= 0 && entryIndex < listPref.getEntries().length
              ? listPref.getEntries()[entryIndex]
              : getString(R.string.preferences__led_color_unknown));
      return true;
    }
  }

  protected void initializeListSummary(ListPreference pref) {
    pref.setSummary(pref.getEntry());
  }
}
