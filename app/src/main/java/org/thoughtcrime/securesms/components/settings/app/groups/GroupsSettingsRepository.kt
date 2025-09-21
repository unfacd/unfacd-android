package org.thoughtcrime.securesms.components.settings.app.groups

import android.content.Context
import com.unfacd.android.jobs.ResetGroupsJob
import com.unfacd.android.jobs.UfsrvUserCommandProfileJob
import com.unfacd.android.utils.UfsrvUserUtils
import com.unfacd.android.utils.UserPrefsUtils
import org.signal.core.util.concurrent.SignalExecutors
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

class GroupsSettingsRepository {

    private val context: Context = ApplicationDependencies.getApplication()

    fun syncRoamingMode() {
        SignalExecutors.BOUNDED.execute {
            val isRoamingModeEnabled = SignalStore.settings().isRoamingEnabled
            val roamingMode = SignalStore.settings().roamingMode

            if (!isRoamingModeEnabled) {
                UfsrvUserUtils.UfsrvSetSettableUserPreference(context, null, SignalServiceProtos.UserPrefs.ROAMING_MODE, false);
            } else {
                UfsrvUserUtils.UfsrvSetSettableUserPreference(context, null, SignalServiceProtos.UserPrefs.ROAMING_MODE, true);
            }

        }
    }

    fun ufsrvUpdateGeolocTrigger() {
        SignalExecutors.BOUNDED.execute {
            val geolocRoamingTrigger = SignalStore.settings().geolocRoamingTrigger

            if (geolocRoamingTrigger != UserPrefsUtils.GeolocRoamingTrigger.UNDEFINED) {
                UfsrvUserUtils.sendUserProfileIntegerValue(geolocRoamingTrigger.value, SignalServiceProtos.UserPrefs.GEOLOC_TRIGGER, UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.GEOLOC_TRIGGER
                );
            }
        }
    }

    fun ufsrvUpdateBaselocZone() {
        SignalExecutors.BOUNDED.execute {
            val baselocZone = SignalStore.settings().baselocZone

            if (baselocZone != UserPrefsUtils.BaselocAnchorZone.UNDEFINED) {
                UfsrvUserUtils.sendUserProfileIntegerValue(baselocZone.value, SignalServiceProtos.UserPrefs.BASELOC_ANCHOR_ZONE, UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.BASELOC_ANCHOR_ZONE);
            }
        }
    }

    fun ufsrvUpdateHomebaseLoc() {
        SignalExecutors.BOUNDED.execute {
            val baselocHomePacked = SignalStore.settings().homebaseLocLatLong + ":" + SignalStore.settings().homebaseLocAddress;

            if (!baselocHomePacked.isEmpty()) {
                UfsrvUserUtils.sendUserProfileHomebaseGeoLocation(baselocHomePacked, SignalServiceProtos.UserPrefs.HOMEBASE_GEOLOC, UfsrvUserCommandProfileJob.IProfileOperationDescriptor.ProfileType.HOMEBASE_GEOLOC);
            }
        }
    }


    fun resetGroupsMembership() {
        ApplicationDependencies.getJobManager().add(ResetGroupsJob())
    }
}