package io.github.JaJaJim.lightonkaroo.engine

import io.github.JaJaJim.lightonkaroo.data.DayTimeZone
import io.github.JaJaJim.lightonkaroo.data.LightControllerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

@kotlinx.serialization.Serializable
data class DisplayInfo(
    val deviceName: String = "",
    val statusText: String = "",
    val batteryLabel: String = "",
    val batteryColor: Int = 0xFFFFFFFF.toInt(),
    val batteryFromRadar: Boolean = false,
)

class LightControlEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 0 = OFF, 1 = PRIMARY, 2 = SECONDARY
    private val _activeState = MutableStateFlow(0)
    val activeState: StateFlow<Int> = _activeState

    private val _displayInfo = MutableStateFlow(DisplayInfo())
    val displayInfo: StateFlow<DisplayInfo> = _displayInfo

    fun updateDisplayInfo(info: DisplayInfo) {
        _displayInfo.value = info
    }

    private var lastOnState = 1
    private var stateBeforePause: Int? = null

    @Volatile var settings: LightControllerSettings = LightControllerSettings()
    var onApplyState: ((Int) -> Unit)? = null
    var onApplyHardwareOff: (() -> Unit)? = null

    fun onRideStart() {
        Timber.d("LightControlEngine: ride started/resumed")
        val restoredState = stateBeforePause
        if (restoredState != null) {
            // Restore the state we had before the pause
            applyState(restoredState)
            stateBeforePause = null
        } else if (settings.autoOnWithRide) {
            // Initial start of the ride
            applyState(1)
        }
    }

    fun onRidePause() {
        Timber.d("LightControlEngine: ride paused")
        if (settings.pauseBehavior == "NONE") return
        
        // Remember current state to restore it later
        stateBeforePause = _activeState.value
        
        when (settings.pauseBehavior) {
            "OFF" -> applyState(0)
            "PRIMARY" -> applyState(1)
            "SECONDARY" -> applyState(2)
            "HARD_OFF" -> {
                onApplyHardwareOff?.invoke()
                _activeState.value = 0 // Update UI to show OFF
            }
        }
    }

    fun onRideStop() {
        Timber.d("LightControlEngine: ride stopped")
        stateBeforePause = null // Clear any pause state
        if (settings.autoOffWithRide) {
            onApplyHardwareOff?.invoke()
        }
        _activeState.value = 0 // ALWAYS ensure internal state is OFF for next session
    }

    fun onToggleLights() {
        if (settings.threeModeEnabled) {
            // In 3-mode, tap toggles ON states
            when (_activeState.value) {
                0 -> applyState(lastOnState) // Turn back ON
                1 -> applyState(2)           // Switch Primary -> Secondary
                2 -> applyState(1)           // Switch Secondary -> Primary
            }
        } else {
            // Classical ON/OFF toggle
            if (_activeState.value == 0) {
                applyState(1)
            } else {
                applyState(0)
            }
        }
    }

    fun onRightClick() {
        if (settings.threeModeEnabled) {
            if (_activeState.value == 0) {
                applyState(lastOnState) // Turn ON if it was OFF
            } else {
                applyState(0) // Turn OFF if it was ON
            }
        } else {
            onToggleLights() // Standard toggle on both sides if disabled
        }
    }

    fun setOff() {
        applyState(0)
    }

    private fun applyState(state: Int) {
        _activeState.value = state
        if (state != 0) lastOnState = state
        onApplyState?.invoke(state)
    }

    fun destroy() {
        scope.cancel()
    }
}
