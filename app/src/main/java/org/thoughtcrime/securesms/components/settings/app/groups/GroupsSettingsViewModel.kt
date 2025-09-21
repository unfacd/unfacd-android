package org.thoughtcrime.securesms.components.settings.app.groups

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.unfacd.android.location.ufLocation
import com.unfacd.android.utils.UserPrefsUtils
import com.unfacd.android.utils.UserPrefsUtils.BaselocAnchorZone
import com.unfacd.android.utils.UserPrefsUtils.GeolocRoamingTrigger
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.util.livedata.Store

class GroupsSettingsViewModel(private val repository: GroupsSettingsRepository) : ViewModel() {
    private val store: Store<GroupsSettingsState>
    init {
        val initialState = GroupsSettingsState(
                SignalStore.settings().isRoamingEnabled,
                SignalStore.settings().roamingMode,
                SignalStore.settings().geolocRoamingTrigger,
                SignalStore.settings().baselocZone,
            ufLocation.packHomebaseLocation(SignalStore.settings().homebaseLocLatLong, SignalStore.settings().homebaseLocAddress).let { if (it.isEmpty) "" else it.get() },
                false
        )

        store = Store(initialState)
    }

    val state: LiveData<GroupsSettingsState> = store.stateLiveData

    fun setRoamingMode(roamingMode: UserPrefsUtils.RoamingMode) {
        store.update { it.copy(showProgressSpinner = true) }
        store.update { it.copy(roamingMode = roamingMode) }
        store.update { it.copy(isRoaming = roamingMode != UserPrefsUtils.RoamingMode.RM_UNSET) }
        SignalStore.settings().roamingMode = roamingMode
        repository.syncRoamingMode()
        store.update { it.copy(showProgressSpinner = false) }
    }

    fun setRoamingTrigger(geolocRoamingTrigger: GeolocRoamingTrigger) {
        store.update { it.copy(geolocRoamingTrigger = geolocRoamingTrigger) }
        SignalStore.settings().geolocRoamingTrigger = geolocRoamingTrigger
        repository.ufsrvUpdateGeolocTrigger()
    }

    fun setBaselocZone(baselocZone: BaselocAnchorZone) {
        store.update { it.copy(baselocZone = baselocZone) }
        SignalStore.settings().baselocZone = baselocZone
        repository.ufsrvUpdateBaselocZone()
    }

    fun setBaselocHomePacked(baselocHomePacked: String) {
        store.update { it.copy(homebaseLocPacked = baselocHomePacked) }
        UserPrefsUtils.setHomebaseLocFromPacked(baselocHomePacked);//updates settings store
        repository.ufsrvUpdateHomebaseLoc()
    }

    fun resetGroupsMembership() {
        repository.resetGroupsMembership()
    }

    class Factory(private val repository: GroupsSettingsRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return requireNotNull(modelClass.cast(GroupsSettingsViewModel(repository)))
        }
    }
}