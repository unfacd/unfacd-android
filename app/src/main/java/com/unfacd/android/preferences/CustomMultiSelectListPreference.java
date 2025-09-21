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

import com.unfacd.android.R;

import android.content.Context;
import android.content.res.TypedArray;
//import android.preference.MultiSelectListPreference;
import android.util.AttributeSet;
import org.signal.core.util.logging.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


public class CustomMultiSelectListPreference extends androidx.preference.DialogPreference
{
  private static final String TAG = CustomMultiSelectListPreference.class.getName();

  String dynamicEntriesProviderName;
  String dynamicSelectedEntriesProviderName;
  String validationCallback;
  boolean selectAllValuesByDefault;
  boolean isInitialised = false;

  private IDynamicProvider mDynamicEntriesProvider;
  private IDynamicProvider mDynamicSelectedEntriesProvider;
//  https://github.com/Knickedi/android-toolbox/blob/master/android_toolbox/src/de/viktorreiser/toolbox/preference/ValidatedDialogPreference.java
  private Method validationCallbackReflected;
  private CharSequence[] mEntries;
  private CharSequence[] mEntryValues;

          boolean dialogActionable = true; //by default, positive button is shown
  private String  customId;
  private int     selectionCriterion;
  Set<String>     oldSelectedValues;
  Set<Long>       ignoredEntries = null;


  /**
   * The list of entries to be shown in the list in subsequent dialogs.
   *
   * @return The list as an array.
   */
  public CharSequence[] getEntries() {
    return mEntries;
  }

  /**
   * Returns the array of values to be saved for the preference.
   *
   * @return The array of values.
   */
  public CharSequence[] getEntryValues() {
    return mEntryValues;
  }

  /**
   * Returns items eligible for selection as per current criterion
   *
   * @return The array of values.
   */
  public Set<String> getOldValues ()
  {
    return oldSelectedValues;
  }

  /**
   * The array to find the value to save for a preference when an entry from
   * entries is selected. If a user clicks on the second item in entries, the
   * second item in this array will be saved to the preference.
   *
   * @param entryValues The array to be used as values to save for the preference.
   */
  public void setEntryValues(CharSequence[] entryValues) {
    mEntryValues = entryValues;
  }

  public void setCustomId (String customId)
  {
    this.customId=customId;
  }

  public void setDialogActionable (boolean actionable)
  {
    this.dialogActionable=actionable;
  }

  public void setSelectionCriterion (int selectionCriterion) {
    this.selectionCriterion = selectionCriterion;
  }

  public void setIgnoredEntries (Set<Long> ignoredEntries)
  {
    this.ignoredEntries = ignoredEntries;
  }

  public String getCustomId ()
  {
    return this.customId;
  }


  public CustomMultiSelectListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    super(context, attrs, defStyleAttr, defStyleRes);

    initialiseResources(context, attrs, defStyleAttr, defStyleRes);
  }

  public CustomMultiSelectListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
    this(context, attrs, defStyleAttr, R.attr.dialogPreferenceStyle);
  }

  public CustomMultiSelectListPreference (Context context, AttributeSet attrs) {
    this(context, attrs, R.attr.dialogPreferenceStyle);
  }

  private void initialiseResources (Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
    // attributes (see attrs.xml)
    TypedArray typedArray              = context.obtainStyledAttributes(attrs, R.styleable.CustomListPreference);
    dynamicEntriesProviderName         = typedArray.getString(R.styleable.CustomListPreference_dynamicEntriesProvider);
    dynamicSelectedEntriesProviderName = typedArray.getString(R.styleable.CustomListPreference_dynamicEntryValuesProvider);
    selectAllValuesByDefault           = typedArray.getBoolean(R.styleable.CustomListPreference_selectAllValuesByDefault, false);
    validationCallback                 = typedArray.getString(R.styleable.CustomListPreference_onValidate);

    typedArray.recycle();
  }

  void initialise () {
    if (!isInitialised) {
      if (dynamicEntriesProviderName != null && dynamicSelectedEntriesProviderName != null)
      {
        initDynamicProviders(getContext());
        processDynamicEntries(getContext());
        isInitialised = true;
      }
    }
  }

  private void initDynamicProviders (Context context) {
    try {
      Class<IDynamicProvider> dynamicEntriesProviderClass     = (Class<IDynamicProvider>) Class.forName(dynamicEntriesProviderName);
      Class<IDynamicProvider> dynamicEntryValuesProviderClass = (Class<IDynamicProvider>) Class.forName(dynamicSelectedEntriesProviderName);

      try {
        mDynamicEntriesProvider     = dynamicEntriesProviderClass.getDeclaredConstructor().newInstance();
        mDynamicSelectedEntriesProvider = dynamicEntryValuesProviderClass.getDeclaredConstructor().newInstance();

        processDynamicEntries(context);
      } catch (InvocationTargetException e) {
        e.printStackTrace();
      } catch (NoSuchMethodException e) {
        e.printStackTrace();
      }  catch (java.lang.InstantiationException e) {
        e.printStackTrace();
      }
    } catch (ClassNotFoundException e) {
      Log.e(TAG, String.format("Could not find class '%s'. Will not load dynamic providers!", dynamicEntriesProviderName));
    } catch (IllegalAccessException e) {
      Log.e(TAG, e.getMessage());
    }
  }

  private void processDynamicEntries (Context context) {
    if (mDynamicEntriesProvider != null && mDynamicSelectedEntriesProvider != null) {
      mDynamicSelectedEntriesProvider.setCustomId(customId);
      mDynamicSelectedEntriesProvider.setIgnoredMembers(ignoredEntries);
      mDynamicSelectedEntriesProvider.setSelectedCriterion(selectionCriterion);
      mDynamicSelectedEntriesProvider.populate(context);

      mDynamicEntriesProvider.setCustomId(customId);
      mDynamicEntriesProvider.setIgnoredMembers(ignoredEntries);
      mDynamicEntriesProvider.populate(context);

      //all entries in a set regardless of criterion's applicability
      List<String> entries     = mDynamicEntriesProvider.getItemsForDisplay();
      List<String> entryValues = mDynamicEntriesProvider.getItemsForId();

      if (entries != null && entryValues != null && !entries.isEmpty() && !entryValues.isEmpty()) {
        CharSequence[] dynamicEntries     = entries.toArray(new CharSequence[entries.size()]);
        CharSequence[] dynamicEntryValues = entryValues.toArray(new CharSequence[entryValues.size()]);

        mEntries          = dynamicEntries;
        mEntryValues      = dynamicEntryValues;
        oldSelectedValues = getSelectedItems(); //actual subset of above being marked for selection
      }
    }

    if (validationCallback != null) {
      setOnValidation(validationCallback);
    }
  }


  @Override
  protected void onSetInitialValue(boolean restoreValue, Object defaultValue) {
    Set<String> values = getSelectedItems();

    //setValues(getSelectedItems());
//    if (!restoreValue && selectAllValuesByDefault && mEntryValues != null) {
//      final int valueCount = mEntryValues.length;
//      final Set<String> result = new HashSet<String>();
//
//      for (int i = 0; i < valueCount; i++) {
//        result.add(mEntryValues[i].toString());
//      }
//
//      setValues(result);
//
//      return;
//    }
//
//    super.onSetInitialValue(restoreValue, defaultValue);
  }

  private Set<String> getSelectedItems ()
  {
    if (mDynamicSelectedEntriesProvider != null)  return (Set)new HashSet<>(mDynamicSelectedEntriesProvider.getSelectedItems(getContext()));

    return (Set)new HashSet<>();
  }


//   Set callback on activity.<br>
  public void setOnValidation(String methodName) {
    setOnValidation(getContext(), methodName);
  }

  /**
   * Set callback on any object.<br>
   * <br>
   * Method should have this signature {@code boolean methodName(}
   * {@link CustomMultiSelectListPreference}{@code )}.
   *
   * @param object
   *            object on which callback method will be called
   * @param methodName
   *            method name of callback
   */
  public void setOnValidation(Object object, String methodName) {
    try {
      validationCallbackReflected = object.getClass().getDeclaredMethod(
              methodName, CustomMultiSelectListPreference.class);

      if (!validationCallbackReflected.getReturnType().equals(boolean.class)) {
        throw new IllegalArgumentException("Method " + methodName + "("
                                                   + CustomMultiSelectListPreference.class.getSimpleName()
                                                   + ") should return a boolean");
      }

      validationCallbackReflected.setAccessible(true);
    } catch (SecurityException e) {
      throw new RuntimeException(e);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException("Method " + methodName + "("
                                                 + CustomMultiSelectListPreference.class.getSimpleName()
                                                 + ") doesn't exist in activity", e);
    }
  }

  /**
          * Override this method to validate input.<br>
	 * <br>
	 * Default implementation returns {@code true}.
          *
          * @return {@code true} if input is correct and preference dialog should be closed
	 *
	 */
  protected boolean validateInput(CustomMultiSelectListPreference pref) {
    return true;
  }
}