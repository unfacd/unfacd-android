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

import android.content.DialogInterface;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceDialogFragmentCompat;

import com.annimon.stream.Stream;
import com.unfacd.android.R;

import org.signal.core.util.logging.Log;

import java.util.HashSet;
import java.util.Set;

public class MultiSelectListPreferenceDialogFragment extends PreferenceDialogFragment {
  private static final String TAG = Log.tag(MultiSelectListPreferenceDialogFragment.class);

  boolean mPreferenceChanged;
  HashSet<String> mNewValues      = new HashSet<>();
  boolean[] mSelectedItems        = new boolean[0];
  private boolean mRestoredState  = false;

  public static MultiSelectListPreferenceDialogFragment newInstance(String key) {
    MultiSelectListPreferenceDialogFragment fragment = new MultiSelectListPreferenceDialogFragment();
    Bundle b = new Bundle(1);
    b.putString(PreferenceDialogFragmentCompat.ARG_KEY, key);
    fragment.setArguments(b);
    return fragment;
  }

  public CustomMultiSelectListPreference getMultiSelectListPreference() {
    return (CustomMultiSelectListPreference) getPreference();
  }

  @Override
  protected void onPrepareDialogBuilder(final AlertDialog.Builder builder) {
    super.onPrepareDialogBuilder(builder);

    CustomMultiSelectListPreference preference = this.getMultiSelectListPreference();
    preference.initialise();

    final CharSequence[] entries = preference.getEntries();
    final CharSequence[] entryValues = preference.getEntryValues();
    if (entries == null || entryValues == null) {
//      throw new IllegalStateException("MultiSelectListPreference requires an entries array and an entryValues array.");
      builder.setMessage(getResources().getString(R.string.There_are_no_entries_to_be_shown));
      builder.setCancelable(false);
      return;
    }

    setupSelectedItems(preference);
    builder.setMultiChoiceItems(entries, mSelectedItems,
                                new DialogInterface.OnMultiChoiceClickListener() {
                                  public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                                    mSelectedItems[which] = isChecked;
                                    if (isChecked) {
                                      mPreferenceChanged |= mNewValues.add(entryValues[which].toString());
                                    } else {
                                      mPreferenceChanged |= mNewValues.remove(entryValues[which].toString());
                                    }
                                  }
                                });

    setupInitialValues(preference);
  }

  private void setupSelectedItems(final CustomMultiSelectListPreference preference) {
    if (!mRestoredState) {
      mSelectedItems = new boolean [preference.getEntries().length];
//      CharSequence [] entries     = preference.getEntries();
      CharSequence [] entryValues = preference.getEntryValues();
      Set<String> oldValues       = preference.getOldValues();//get items that that meet criterion set earlier
      for (int j = 0; j < entryValues.length; j++) {
        final int idx=j;
        Stream.of(oldValues).forEach((oldValue) -> {
          if (oldValue.equals(entryValues[idx]))  mSelectedItems[idx]=true;
        });
      }
    }
  }

  private void setupInitialValues(final CustomMultiSelectListPreference preference) {
    if (!mRestoredState) {
      mNewValues.clear();
      mNewValues.addAll(preference.getOldValues());
    }
  }

  @Override
  public void onDialogClosed(final boolean positiveResult) {
    CustomMultiSelectListPreference preference = this.getMultiSelectListPreference();
    if (positiveResult && mPreferenceChanged) {
      final Set<String> values = mNewValues;
      if (preference.callChangeListener(values)) {
        CharSequence[] processedValues = values.toArray(new CharSequence[values.size()]);
        preference.setEntryValues(processedValues);
      }
    }
    mPreferenceChanged = false;
  }

  @Override
  public void onSaveInstanceState(final Bundle outState) {
    super.onSaveInstanceState(outState);

    outState.putSerializable(TAG + ".mNewValues", mNewValues);
    outState.putBooleanArray(TAG + ".mSelectedItems", mSelectedItems);
    outState.putBoolean(TAG + ".mPreferenceChanged", mPreferenceChanged);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void onCreate(final Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (savedInstanceState != null) {
      mNewValues = (HashSet<String>) savedInstanceState.getSerializable(TAG + ".mNewValues");
      mSelectedItems = savedInstanceState.getBooleanArray(TAG + ".mSelectedItems");
      mPreferenceChanged = savedInstanceState.getBoolean(TAG + ".mPreferenceChanged");
      mRestoredState = true;
    }
  }
}