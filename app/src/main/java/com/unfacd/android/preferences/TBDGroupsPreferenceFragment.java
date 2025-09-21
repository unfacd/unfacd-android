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

public class TBDGroupsPreferenceFragment /*extends ListSummaryPreferenceFragment*/
{
  /*private static final String TAG = Log.tag(GroupsPreferenceFragment.class);
  private boolean isLocationPermitted = true;

  @Override
  public void onCreate(Bundle paramBundle) {
    super.onCreate(paramBundle);

    ApplicationContext.getInstance().getUfsrvcmdEvents().register(this);

    findPreference(TextSecurePreferences.UFSRV_GEOGROUPS_ROAMING).setOnPreferenceChangeListener(new RoamingModeClickListener());
    findPreference("pref_geogroups_wanderer_mode").setOnPreferenceChangeListener(new RoamingModeWandererToggleListener());
    findPreference("pref_geogroups_conquerer_mode").setOnPreferenceChangeListener(new RoamingModeConquererToggleListener());
    findPreference("pref_geogroups_journaler_mode").setOnPreferenceChangeListener(new RoamingModeJournalerToggleListener());

    findPreference(TextSecurePreferences.UFSRV_GEOGROUPS_LOCALITY_LEVEL).setOnPreferenceChangeListener(new ListSummaryListener());
    findPreference(TextSecurePreferences.UFSRV_GEOGROUPS_BASELOC_LEVEL).setOnPreferenceChangeListener(new ListSummaryListener());

    initializeListSummary((ListPreference)findPreference(TextSecurePreferences.UFSRV_GEOGROUPS_LOCALITY_LEVEL));
    initializeListSummary((ListPreference)findPreference(TextSecurePreferences.UFSRV_GEOGROUPS_BASELOC_LEVEL));

  }

  @Override
  public void onCreatePreferences(@Nullable Bundle savedInstanceState, String rootKey) {
    addPreferencesFromResource(R.xml.preferences_groups);

  }

  @Override
  public void onResume() {
    super.onResume();

    ((ApplicationPreferencesActivity)getActivity()).getSupportActionBar().setTitle(R.string.preferences__groups);
    displayGeoGroupmRoadmingMode ();
  }

  @Override
  public void onStart() {
    super.onStart();

    showPermissionsIfNecessary();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    ApplicationContext.getInstance().getUfsrvcmdEvents().unregister(this);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  private void showPermissionsIfNecessary ()
  {
    Permissions.with(requireActivity())
            .request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .ifNecessary()
            .withRationaleDialog(getString(R.string.unfacd_needs_access_to_your_location_to_allow_discovery_of_local_groups_and_events),
                                 R.drawable.ic_explore_white_48dp)
            .onAnyDenied(() -> isLocationPermitted = false)
            .execute();
  }

  //master switch for roaming mode
  private class RoamingModeClickListener implements Preference.OnPreferenceChangeListener
  {
    boolean isOk = true;

    @Override
    public boolean onPreferenceChange (final Preference preference, Object newValue)
    {
      boolean isPrefernceSet = getPreferenceValue(newValue);

      if (isPrefernceSet) {
      new AlertDialog.Builder(requireActivity())
              .setTitle(R.string.preferences__groups_title_enable_geo_groups)
              .setMessage(R.string.preferences__groups_summary_enable_geo_groups)
              .setCancelable(true)
              .setNegativeButton(android.R.string.cancel, null)
              .setPositiveButton(R.string.preferences_groups__enable, (dialog, which) ->
                  new SetRoamingPreferencesTask((CheckBoxPreference)preference, true, UserPrefs.ROAMING_MODE).execute()
              ).show();
    } else {
      new AlertDialog.Builder(requireActivity())
              .setTitle(R.string.preferences__groups_title_disable_geo_groups)
              .setMessage(R.string.preferences__groups_summary_disable_geo_groups)
              .setCancelable(true)
              .setNegativeButton(android.R.string.cancel, null)
              .setPositiveButton(R.string.preferences_groups__disable, (dialog, which) ->
                  new SetRoamingPreferencesTask((CheckBoxPreference)preference, false, UserPrefs.ROAMING_MODE).execute()
              ).show();
    }


      return isOk;
    }
  }

  private class RoamingModeWandererToggleListener implements Preference.OnPreferenceChangeListener
  {
    boolean isOk = true;

    @Override
    public boolean onPreferenceChange (final Preference preference, Object newValue)
    {
      boolean isPrefernceSet = getPreferenceValue(newValue);

      new SetRoamingPreferencesTask((CheckBoxPreference)preference, isPrefernceSet, UserPrefs.RM_WANDERER).execute();
      return isOk;
    }
  }

  private class RoamingModeConquererToggleListener implements Preference.OnPreferenceChangeListener
  {
    boolean isOk = true;

    @Override
    public boolean onPreferenceChange (final Preference preference, Object newValue)
    {
      boolean isPrefernceSet = getPreferenceValue(newValue);

      new SetRoamingPreferencesTask((CheckBoxPreference)preference, isPrefernceSet, UserPrefs.RM_CONQUERER).execute();
      return isOk;
    }
  }

  private class RoamingModeJournalerToggleListener implements Preference.OnPreferenceChangeListener
  {
    boolean isOk = true;

    @Override
    public boolean onPreferenceChange (final Preference preference, Object newValue)
    {
      boolean isPrefernceSet = getPreferenceValue(newValue);

      new SetRoamingPreferencesTask((CheckBoxPreference)preference, isPrefernceSet, UserPrefs.RM_JOURNALER).execute();
      return isOk;
    }
  }

  private boolean displayGeoGroupmRoadmingMode ()
  {
    CheckBoxPreference prefRoaming   = (CheckBoxPreference) findPreference("pref_geogroups_roaming");//master switch
    CheckBoxPreference prefWanderer  = (CheckBoxPreference) findPreference("pref_geogroups_wanderer_mode");
    CheckBoxPreference prefConquerer = (CheckBoxPreference) findPreference("pref_geogroups_conquerer_mode");
    CheckBoxPreference prefJournaler = (CheckBoxPreference) findPreference("pref_geogroups_journaler_mode");

    int roamingMode = TextSecurePreferences.getUfsrvGeoGroupRoamingMode (getActivity());

    if (roamingMode==0) {
      prefRoaming.setChecked(false);
      return true;
    }

    prefRoaming.setChecked(true);
    switch (roamingMode) {
      case 1:
        prefWanderer.setChecked(true);
        prefConquerer.setChecked(false);
        prefJournaler.setChecked(false);
        break;

      case 2:
        prefWanderer.setChecked(false);
        prefConquerer.setChecked(true);
        prefJournaler.setChecked(false);
        break;

      case 3:
        prefWanderer.setChecked(false);
        prefConquerer.setChecked(false);
        prefJournaler.setChecked(true);
        break;
    }

    return true;
  }

  public static CharSequence getSummary(Context context) {
    return null;
  }

  private class
  SetRoamingPreferencesTask extends AsyncTask<Void,Void,Integer>
  {
    private final CheckBoxPreference  checkBoxPreference;
    private final boolean              isSet;
    private final UserPrefs userPref;

    public SetRoamingPreferencesTask(final CheckBoxPreference checkBoxPreference, boolean isSet, final UserPrefs userPref)
    {
      this.checkBoxPreference = checkBoxPreference;
      this.isSet              = isSet;
      this.userPref           = userPref;
    }

    @Override
    protected void onPreExecute () {
//      if (isSet && !isLocationPermitted) {
//        ((ApplicationPreferencesActivity)com.unfacd.android.ui.Utils.getHostActivity(getContext())).postNotification("Please note settings won't be functional until location permission is granted.", true);
//      }
    }

    @Override
    protected Integer doInBackground(Void... aVoid) {
      UfsrvUserUtils.UfsrvSetSettableUserPreference(getActivity(), null*//*recipientGroup*//*, userPref, isSet);
      return 0;
    }

    @Override
    protected void onPostExecute(Integer result) {
      //toggleGeoGroupmRoadmingMode(checkBoxPreference, newValue, JsonUtil.fromJson(jsonResponse, JsonEntityPreferenceSetResponse.class)); //orig
    }
  }

  private boolean getPreferenceValue (Object newValue)
  {
    if ("true".equals(newValue.toString())) return true;

    return false;
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEvent(AppEventUserPrefRoamingMode event) {
    String postedMessage = getContext().getString(R.string.preferences__groups_roaming_mode_setting_x, event.getResult() == 1?
                                                                                                             getContext().getString(R.string.preferences__groups_roaming_mode_setting_changed):
                                                                                                             getContext().getString(R.string.preferences__groups_roaming_mode_setting_rejected));

    if (event.getUid().longValue() == TextSecurePreferences.getUserId(ApplicationContext.getInstance()).longValue()) {
      ((ApplicationPreferencesActivity)com.unfacd.android.ui.Utils.getHostActivity(getContext())).postNotification(postedMessage, true);

      displayGeoGroupmRoadmingMode();
    } else {
      Log.d(TAG, "AppEventUserPrefRoamingMode: uid: "+event.getUid());
    }

    ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);
  }
*/
}
