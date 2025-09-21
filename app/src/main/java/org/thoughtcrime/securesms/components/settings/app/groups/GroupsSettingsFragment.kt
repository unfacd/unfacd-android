package org.thoughtcrime.securesms.components.settings.app.groups

import android.app.ProgressDialog
import android.os.Bundle
import android.text.Html
import android.text.TextUtils
import android.view.View
import androidx.fragment.app.setFragmentResultListener
import androidx.lifecycle.ViewModelProviders
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.unfacd.android.ApplicationContext
import com.unfacd.android.R
import com.unfacd.android.location.ufLocation
import com.unfacd.android.ufsrvcmd.events.AppEventUserPrefRoamingMode
import com.unfacd.android.utils.UserPrefsUtils
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.thoughtcrime.securesms.components.settings.DSLConfiguration
import org.thoughtcrime.securesms.components.settings.DSLSettingsAdapter
import org.thoughtcrime.securesms.components.settings.DSLSettingsFragment
import org.thoughtcrime.securesms.components.settings.DSLSettingsText
import org.thoughtcrime.securesms.components.settings.configure
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.maps.PlacePickerFragment
import org.thoughtcrime.securesms.maps.PlacePickerFragment.LOCATION_PICKED_RESULT
import org.thoughtcrime.securesms.maps.PlacePickerFragment.LOCATION_PICKED_RESULT_KEY
import org.thoughtcrime.securesms.util.navigation.safeNavigate

class GroupsSettingsFragment : DSLSettingsFragment(R.string.preferences__groups) {
    private val roamingTriggerValues by lazy { resources.getStringArray(R.array.pref_geogroups_locality_levels_values) }
    private val roamingTriggerLabels by lazy { resources.getStringArray(R.array.pref_geogroups_locality_levels_entries) }

    private val baselocZoneValues by lazy { resources.getStringArray(R.array.pref_groups_baseloc_levels_values) }
    private val baselocZoneLabels by lazy { resources.getStringArray(R.array.pref_groups_baseloc_levels_entries) }

    private lateinit var viewModel: GroupsSettingsViewModel
    var progressDialog: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApplicationContext.getInstance().ufsrvcmdEvents.register(this);
    }

  //AA+ retrieve location from place picker fragment after it terminated.
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    setFragmentResultListener(LOCATION_PICKED_RESULT_KEY) { requestKey, bundle ->
      val result = bundle.getParcelable(LOCATION_PICKED_RESULT) as android.location.Address?
      viewModel.setBaselocHomePacked(ufLocation.makeHomebaseLocationPrefix(result, 4).orElse(""));
    }
  }
  //

    override fun onStart() {
        super.onStart()
//        showPermissionsIfNecessary();
    }

    override fun onDestroy() {
        super.onDestroy()
        ApplicationContext.getInstance().ufsrvcmdEvents.unregister(this);
    }

    override fun bindAdapter(adapter: DSLSettingsAdapter) {
        val repository = GroupsSettingsRepository()
        val factory = GroupsSettingsViewModel.Factory(repository)
        viewModel = ViewModelProviders.of(this, factory)[GroupsSettingsViewModel::class.java]

        viewModel.state.observe(viewLifecycleOwner) {
            if (it.showProgressSpinner) {
                if (progressDialog?.isShowing == false) {
                    progressDialog = ProgressDialog.show(requireContext(), null, null, true)
                }
            } else {
                progressDialog?.hide()
            }

            adapter.submitList(getConfiguration(it).toMappingModelList())
        }
    }

    private fun getConfiguration(state: GroupsSettingsState): DSLConfiguration {
        return configure {
            sectionHeaderPref(R.string.preferences_groups__category_title_Geo_groups)

            switchPref(
                title = DSLSettingsText.from(if (state.isRoaming) R.string.preferences__groups_title_enable_geo_groups else R.string.preferences__groups_title_disable_geo_groups),
                summary = DSLSettingsText.from(if (state.isRoaming) R.string.preferences__groups_summary_enable_geo_groups else R.string.preferences__groups_summary_disable_geo_groups),
                isChecked = state.isRoaming,
                isEnabled = state.isRoaming,
                onClick = {
                    viewModel.setRoamingMode(UserPrefsUtils.RoamingMode.RM_UNSET)
                }
            )

            checkboxPref(
                    title = DSLSettingsText.from(R.string.preferences__groups_title_geo_groups_wanderer_mode),
                    summary = DSLSettingsText.from(R.string.preferences__groups_summary_geo_groups_wanderer_mode),
                    isChecked = state.isRoaming  && state.roamingMode == UserPrefsUtils.RoamingMode.RM_WANDERER,
                    isEnabled = true,
                    onClick = {
                        viewModel.setRoamingMode(UserPrefsUtils.RoamingMode.RM_WANDERER)
                    }
            )

            checkboxPref(
                    title = DSLSettingsText.from(R.string.preferences__groups_title_geo_groups_conqueror_mode),
                    summary = DSLSettingsText.from(R.string.preferences__groups_summary_geo_groups_conqueror_mode),
                    isChecked = state.isRoaming  && state.roamingMode == UserPrefsUtils.RoamingMode.RM_CONQUEROR,
                    isEnabled = true,
                    onClick = {
                        viewModel.setRoamingMode(UserPrefsUtils.RoamingMode.RM_CONQUEROR)
                    }
            )

            checkboxPref(
                    title = DSLSettingsText.from(R.string.preferences__groups_title_geo_groups_journaler_mode),
                    summary = DSLSettingsText.from(R.string.preferences__groups_summary_geo_groups_journaler_mode),
                    isChecked = state.isRoaming  && state.roamingMode == UserPrefsUtils.RoamingMode.RM_JOURNALER,
                    isEnabled = true,
                    onClick = {
                        viewModel.setRoamingMode(UserPrefsUtils.RoamingMode.RM_JOURNALER)
                    }
            )

          //todo convert to example used in "Usolicited request action" setting MaterialDialog
            radioListPref(
                    title = DSLSettingsText.from(R.string.preferences__groups_title_geoloc_roaming_trigger),
                    listItems = roamingTriggerLabels,
                    selected = roamingTriggerValues.indexOf(UserPrefsUtils.GeolocRoamingTrigger.entries[state.geolocRoamingTrigger.value].ordinal.toString()),
                    isEnabled = true,
                    onSelected = {
                        viewModel.setRoamingTrigger(UserPrefsUtils.GeolocRoamingTrigger.entries[it + 1]) //Array value starts at 1 ('it' represents position index, so we need to add 1 to get to the value in teh enum)
                    }
            )

            dividerPref()

            sectionHeaderPref(R.string.preferences_groups__category_title_User_groups)

          //todo convert to example used in "Usolicited request action" setting MaterialDialog
            radioListPref(
                title = DSLSettingsText.from(R.string.preferences__groups_title_baseloc_zones),
                listItems = baselocZoneLabels,
                selected = baselocZoneValues.indexOf(UserPrefsUtils.BaselocAnchorZone.entries[state.baselocZone.value].ordinal.toString()),
                isEnabled = true,
                onSelected = {
                    viewModel.setBaselocZone(UserPrefsUtils.BaselocAnchorZone.entries[it + 1])//Array value starts at 0 ('it' represents position index, so we need to add 1 to get to the value in teh enum)
                }
            )

            clickPref(
              title = DSLSettingsText.from(R.string.preferences_groups__homebase_geo_location_set_to),
              summary = DSLSettingsText.from(
                Html.fromHtml(provideHomebaseGeoLoc(state.homebaseLocPacked))
                        ),
              onClick = {
                findNavController().safeNavigate(R.id.action_groupsSettingsFragment_to_homebaseGeolocationFragment, getPlacePickerArguments())
              }
            )

            dividerPref()

            switchPref(
                    title = DSLSettingsText.from(R.string.preferences__reset_group_membership),
                    summary = DSLSettingsText.from(R.string.preferences__reset_group_membership_extra),
                    isChecked = false,
                    isEnabled = true,
            ) {
                    MaterialAlertDialogBuilder(requireContext()).apply {
                        setIcon(R.drawable.ic_info_outline)
                        setTitle(R.string.preferences__reset_group_membership)
                        setMessage(R.string.preferences__reset_group_membership_dialog)
                        setNegativeButton(android.R.string.cancel, null)
                        setPositiveButton(android.R.string.ok) {
                            _, _ -> viewModel.resetGroupsMembership()
                        }
                        show()
                    }
            }

        }
    }

    /*showPermissionsIfNecessary ()
    {
        Permissions.with(requireActivity())
                .request(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .ifNecessary()
                .withRationaleDialog(getString(R.string.unfacd_needs_access_to_your_location_to_allow_discovery_of_local_groups_and_events),
                        R.drawable.ic_explore_white_48dp)
                .onAnyDenied(() -> isLocationPermitted = false)
        .execute();
    }*/

  //AA+
  fun provideHomebaseGeoLoc(homebaseLocPacked: String): String {
    if (TextUtils.isEmpty(homebaseLocPacked)) return getString(R.string.preferences_groups__home_base_location_undefined)

    return getString(R.string.preferences_groups__home_base_location_explained, ufLocation.describeLocationFromHomebaseLoc(ufLocation.unpackAddressFromHomeBaseLoc(homebaseLocPacked)))
  }

  fun getPlacePickerArguments(): Bundle {
    return Bundle().apply {
      var location : android.location.Location = if (!SignalStore.settings().homebaseLocLatLong.isEmpty()) ufLocation.provideLatLongLocation(SignalStore.settings().homebaseLocLatLong) else ufLocation.getInstance().myLocation
      putParcelable(PlacePickerFragment.SEED_LOCATION_INTENT,  location)
      putBoolean(PlacePickerFragment.SELECTION_MODE_INTENT, true)//ie from settings screen context
    }
  }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    fun onEvent(event: AppEventUserPrefRoamingMode) {
      /*  val postedMessage = getContext().getString(R.string.preferences__groups_roaming_mode_setting_x, event.getResult() == 1?
        getContext().getString(R.string.preferences__groups_roaming_mode_setting_changed):
        getContext().getString(R.string.preferences__groups_roaming_mode_setting_rejected));

        if (event.getUid().longValue() == TextSecurePreferences.getUserId(ApplicationContext.getInstance()).longValue()) {
            ((ApplicationPreferencesActivity)com.unfacd.android.ui.Utils.getHostActivity(getContext())).postNotification(postedMessage, true);

            displayGeoGroupmRoadmingMode();
        } else {
            Log.d(TAG, "AppEventUserPrefRoamingMode: uid: "+event.getUid());
        }

        ApplicationContext.getInstance().getUfsrvcmdEvents().removeStickyEvent(event);*/
    }
}
