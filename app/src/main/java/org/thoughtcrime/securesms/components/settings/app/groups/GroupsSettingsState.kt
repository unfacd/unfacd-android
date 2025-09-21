package org.thoughtcrime.securesms.components.settings.app.groups

import com.unfacd.android.utils.UserPrefsUtils

//AA+
data class GroupsSettingsState(
        val isRoaming: Boolean,
        val roamingMode: UserPrefsUtils.RoamingMode,
        val geolocRoamingTrigger: UserPrefsUtils.GeolocRoamingTrigger,
        val baselocZone: UserPrefsUtils.BaselocAnchorZone,
        val homebaseLocPacked: String,
        val showProgressSpinner: Boolean
)