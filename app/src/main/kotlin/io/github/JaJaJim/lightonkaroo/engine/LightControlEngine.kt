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

    private val _activeZone = MutableStateFlow<DayTimeZone?>(null)
    val activeZone: StateFlow<DayTimeZone?> = _activeZone

    private val _displayInfo = MutableStateFlow(DisplayInfo())
    val displayInfo: StateFlow<DisplayInfo> = _displayInfo

    fun updateDisplayInfo(info: DisplayInfo) {
        _displayInfo.value = info
    }

    @Volatile var settings: LightControllerSettings = LightControllerSettings()
    var onApplyZone: ((DayTimeZone?) -> Unit)? = null
    var onApplyHardwareOff: (() -> Unit)? = null

    fun onRideStart() {
        Timber.d("LightControlEngine: ride started")
        if (settings.autoOnWithRide) {
            applyZone(DayTimeZone.DAY)
        }
    }

    fun onRidePause() {
        Timber.d("LightControlEngine: ride paused")
        if (settings.autoOffOnPause) {
            // Switch to configured OFF Mode (e.g. blinking) when pausing
            applyZone(null)
        }
    }

    fun onRideStop() {
        Timber.d("LightControlEngine: ride stopped")
        if (settings.autoOffWithRide) {
            onApplyHardwareOff?.invoke()
        }
    }

    fun onToggleLights() {
        if (_activeZone.value != null) {
            applyZone(null)
        } else {
            applyZone(DayTimeZone.DAY)
        }
    }

    private fun applyZone(zone: DayTimeZone?) {
        _activeZone.value = zone
        onApplyZone?.invoke(zone)
    }

    fun destroy() {
        scope.cancel()
    }
}
