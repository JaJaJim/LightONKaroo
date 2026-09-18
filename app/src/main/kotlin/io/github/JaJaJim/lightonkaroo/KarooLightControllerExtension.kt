package io.github.JaJaJim.lightonkaroo

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.github.JaJaJim.lightonkaroo.ble.MagicshineBleController
import io.github.JaJaJim.lightonkaroo.karoo.KarooLightControl
import io.github.JaJaJim.lightonkaroo.data.DayTimeZone
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.data.LightRole
import io.github.JaJaJim.lightonkaroo.data.modeProviderFor
import io.github.JaJaJim.lightonkaroo.data.PreferencesRepository
import io.github.JaJaJim.lightonkaroo.light.LightController
import io.github.JaJaJim.lightonkaroo.datatypes.LightStatusDataType
import io.github.JaJaJim.lightonkaroo.engine.LightControlEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

data class DiscoveredLight(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val protocol: LightProtocol = LightProtocol.ANT_PLUS,
    val connected: Boolean = true,
    val batteryPercent: Int? = null,
    val temperature: Int? = null,
    val currentMode: String? = null,
    val batteryFromRadar: Boolean = false,
)

class KarooLightControllerExtension : KarooExtension("light-on-karoo", BuildConfig.VERSION_NAME) {

    companion object {
        const val TAG = "LightController"
        private const val BIKE_LIGHT_DATA_TYPE = "TYPE_BIKE_LIGHT_ID"
        private const val DEVICE_TYPE_BIKE_LIGHT = 35

        @Volatile
        private var instance: KarooLightControllerExtension? = null
        fun getInstance(): KarooLightControllerExtension? = instance
    }

    init {
        instance = this
    }

    internal lateinit var karooSystem: KarooSystemService
    internal lateinit var lightControl: KarooLightControl
    internal lateinit var magicshineController: MagicshineBleController
    private val lightControllers = mutableMapOf<LightProtocol, LightController>()
    internal lateinit var engine: LightControlEngine
    internal lateinit var repository: PreferencesRepository

    private val _antLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    private var savedDevicesConsumerId: String? = null
    private var radarConsumerId: String? = null
    @Volatile private var radarThreatActive = false
    private var bleStartJob: kotlinx.coroutines.Job? = null
    private var discoveryPollingJob: kotlinx.coroutines.Job? = null
    private var displayRotationJob: kotlinx.coroutines.Job? = null
    @Volatile private var settingsUiActive = false
    @Volatile private var rideActive = false

    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types by lazy {
        listOf(LightStatusDataType(engine))
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("$TAG: Extension onCreate")

        karooSystem = KarooSystemService(applicationContext)
        repository = PreferencesRepository(applicationContext)
        lightControl = KarooLightControl(applicationContext)
        magicshineController = MagicshineBleController(applicationContext)
        lightControllers[LightProtocol.ANT_PLUS] = lightControl
        lightControllers[LightProtocol.BLE] = magicshineController
        magicshineController.onDeviceConnected = {
            stopBleIfNotNeeded()
            engine.activeZone.value?.let { zone -> engine.onApplyZone?.invoke(zone) }
        }
        engine = LightControlEngine()

        engine.onApplyZone = { zone ->
            for (assignment in engine.settings.lightAssignments) {
                val modeName = assignment.modeForZone(zone)
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, modeName)
            }
            updateRadarMonitoring()
        }

        engine.onApplyHardwareOff = {
            for (assignment in engine.settings.lightAssignments) {
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, "OFF")
            }
        }

        // Merge ANT+ and BLE discovered lights
        extensionScope.launch {
            combine(
                _antLights,
                magicshineController.discoveredLights,
                lightControl.connectionStates,
                lightControl.actualModes
            ) { ant, ble, connStates, actualModes ->
                ant.map {
                    it.copy(
                        connected = connStates[it.id] == "CONNECTED",
                        currentMode = actualModes[it.id]
                    )
                } + ble
            }.collect { merged ->
                _discoveredLights.value = merged
            }
        }

        // When the SensorService light session becomes ready (we bind lazily on ride/UI),
        // re-apply the current zone so ANT+ lights catch up despite the async bind.
        lightControl.onServiceReady = {
            engine.activeZone.value?.let { zone -> engine.onApplyZone?.invoke(zone) }
        }

        karooSystem.connect { connected ->
            Timber.d("$TAG: Karoo system connected=$connected")
            if (connected) {
                setupConsumers()
                loadSettings()
                startDisplayRotation()
            }
        }
    }

    private fun startDisplayRotation() {
        displayRotationJob?.cancel()
        displayRotationJob = extensionScope.launch {
            var currentIndex = 0
            while (true) {
                val assignments = engine.settings.lightAssignments
                if (assignments.isNotEmpty()) {
                    currentIndex %= assignments.size
                    val assignment = assignments[currentIndex]
                    val light = discoveredLights.value.find { it.id == assignment.deviceId }

                    val statusText = when {
                        light == null -> "OFFLINE"
                        !light.connected -> "SEARCHING"
                        light.currentMode == "OFF" -> "OFF"
                        else -> "ON"
                    }

                    val battery = light?.batteryPercent
                    val batteryColor = when {
                        battery == null -> 0xFFAAAAAA.toInt()
                        battery >= 40 -> 0xFF00FF00.toInt() // Green
                        battery >= 25 -> 0xFFFFFF00.toInt() // Yellow
                        battery >= 10 -> 0xFFFFA500.toInt() // Orange
                        else -> 0xFFFF0000.toInt() // Red
                    }

                    engine.updateDisplayInfo(
                        io.github.JaJaJim.lightonkaroo.engine.DisplayInfo(
                            deviceName = assignment.deviceName,
                            statusText = statusText,
                            batteryPercent = battery,
                            batteryColor = batteryColor,
                            batteryFromRadar = light?.batteryFromRadar ?: false
                        )
                    )
                    currentIndex++
                } else {
                    engine.updateDisplayInfo(io.github.JaJaJim.lightonkaroo.engine.DisplayInfo(statusText = "No Lights"))
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    }

    private fun setupConsumers() {
        karooSystem.addConsumer<RideState> { state ->
            handleRideState(state)
        }
    }

    private fun handleRideState(state: RideState) {
        when (state) {
            is RideState.Recording -> {
                rideActive = true
                lightControl.bind()
                engine.onRideStart()
                startDiscoveryPolling()
                // (Re)arm BLE for the ride so an assigned light that isn't connected yet
                // gets discovered and connected mid-ride, not just at extension startup.
                startBleIfNeeded()
                updateRadarMonitoring()
            }
            is RideState.Paused -> engine.onRidePause()
            is RideState.Idle -> {
                rideActive = false
                stopDiscoveryPolling()
                stopRadarMonitoring()
                engine.onRideStop()
                if (!settingsUiActive) lightControl.unbind()
            }
        }
    }

    private fun loadSettings() {
        extensionScope.launch {
            var settings = repository.settingsFlow.first()
            val migrated = settings.migrateProfilesToAssignments()
            if (migrated != settings) {
                repository.updateSettings(migrated)
                settings = migrated
            }
            engine.settings = settings
            syncBleAssignments()
            startBleIfNeeded()
        }
    }

    private fun syncBleAssignments() {
        magicshineController.assignedDeviceIds = engine.settings.lightAssignments
            .filter { it.protocol == LightProtocol.BLE }
            .map { it.deviceId }
            .toSet()
    }

    fun testMode(deviceId: String, modeName: String) {
        val assignment = engine.settings.lightAssignments.find { it.deviceId == deviceId } ?: return
        extensionScope.launch {
            lightControllers[assignment.protocol]?.setMode(deviceId, modeName)
            kotlinx.coroutines.delay(3000)
            // In settings UI, always revert to real OFF, not the configured OFF-mode
            lightControllers[assignment.protocol]?.setMode(deviceId, "OFF")
        }
    }

    fun onAssignmentChanged() {
        syncBleAssignments()
        // Connect newly assigned BLE lights
        for (id in magicshineController.assignedDeviceIds) {
            magicshineController.connect(id)
        }
        startBleIfNeeded()
        updateRadarMonitoring()
        // Ensure new assignments are also turned OFF while in settings
        if (settingsUiActive) {
            for (assignment in engine.settings.lightAssignments) {
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, "OFF")
            }
        }
    }

    fun setSettingsUiActive(active: Boolean) {
        Timber.d("$TAG: setSettingsUiActive=$active")
        settingsUiActive = active
        if (active) {
            lightControl.bind()
            startDiscoveryPolling()
            startBleIfNeeded()
            // Turn everything truly OFF for configuration session
            extensionScope.launch {
                // Small delay to ensure binders are ready
                kotlinx.coroutines.delay(500)
                for (assignment in engine.settings.lightAssignments) {
                    lightControllers[assignment.protocol]?.setMode(assignment.deviceId, "OFF")
                }
            }
        } else {
            if (!rideActive) {
                stopDiscoveryPolling()
                lightControl.unbind()
            } else {
                // Restore ride state when leaving settings
                engine.onApplyZone?.invoke(engine.activeZone.value)
            }
            stopBleIfNotNeeded()
        }
    }

    private fun allAssignedBleConnected(): Boolean {
        val bleAssigned = magicshineController.assignedDeviceIds
        return bleAssigned.isEmpty() || magicshineController.allConnected(bleAssigned)
    }

    private fun startDiscoveryPolling() {
        if (discoveryPollingJob != null) return
        discoveryPollingJob = extensionScope.launch {
            while (true) {
                discoverKarooLights()
                if (allAssignedBleConnected()) {
                    Timber.d("$TAG: All assigned lights connected, stopping discovery polling")
                    break
                }
                kotlinx.coroutines.delay(10_000)
            }
            discoveryPollingJob = null
        }
    }

    private fun stopDiscoveryPolling() {
        discoveryPollingJob?.cancel()
        discoveryPollingJob = null
    }

    private fun hasBleAssignments(): Boolean =
        engine.settings.lightAssignments.any { it.protocol == LightProtocol.BLE }

    private fun startBleIfNeeded() {
        Timber.d("$TAG: startBleIfNeeded: settingsUiActive=$settingsUiActive, hasBleAssignments=${hasBleAssignments()}")
        if (settingsUiActive || hasBleAssignments()) {
            bleStartJob?.cancel()
            bleStartJob = extensionScope.launch {
                kotlinx.coroutines.delay(2000)
                // Always hold Bluetooth while a BLE light is assigned so the supervisor can
                // reconnect a known light without a scan. Only run the scanner when a light
                // still needs discovering (or the settings UI is open), otherwise it would
                // scan for the whole ride even though everything is already connected.
                karooSystem.dispatch(RequestBluetooth(extension))
                // Assigned lights connect directly by their bonded address. This works even
                // when the light is on but idle and no longer advertising (Magicshine stops
                // advertising when idle), which scan-based discovery cannot handle — and it
                // needs no scanner, so there is no scan battery cost during a ride.
                for (id in magicshineController.assignedDeviceIds) {
                    magicshineController.connect(id)
                }
                // The scanner is only needed to discover NEW, not-yet-assigned lights, so run
                // it only while the settings UI is open — never for a whole ride.
                if (settingsUiActive) {
                    Timber.d("$TAG: Starting BLE discovery")
                    magicshineController.startDiscovery()
                }
            }
        }
    }

    private fun stopBleIfNotNeeded() {
        if (settingsUiActive) return
        if (!hasBleAssignments()) {
            magicshineController.stopDiscovery()
            karooSystem.dispatch(ReleaseBluetooth(extension))
        } else if (magicshineController.allConnected(magicshineController.assignedDeviceIds)) {
            magicshineController.stopDiscovery()
        }
    }

    internal fun discoverKarooLights() {
        savedDevicesConsumerId?.let { karooSystem.removeConsumer(it) }
        extensionScope.launch {
            Timber.d("$TAG: Querying Karoo for saved bike light devices")
            savedDevicesConsumerId = karooSystem.addConsumer<SavedDevices> { savedDevices ->
                fun translateBattery(name: String?): Int? = when (name) {
                    "GOOD" -> 80
                    "OK" -> 50
                    "LOW" -> 20
                    "CRITICAL" -> 5
                    else -> null
                }

                // 1. Collect all batteries from enabled devices that are NOT lights (type 35)
                // This captures Radar (Type 16) or other sensors sharing the same serial suffix
                val otherBatteriesBySuffix = savedDevices.devices.filter { device ->
                    val parts = device.id.split("-")
                    device.enabled && (parts.size < 2 || parts[1].toIntOrNull() != DEVICE_TYPE_BIKE_LIGHT)
                }.mapNotNull { device ->
                    val batteryPercent = translateBattery(device.details?.lastBattery?.name)
                    if (batteryPercent != null) device.id.split("-").last() to batteryPercent else null
                }.toMap()

                val lights = savedDevices.devices.filter { device ->
                    device.supportedDataTypes.contains(BIKE_LIGHT_DATA_TYPE) && device.enabled
                }.filter { device ->
                    val parts = device.id.split("-")
                    parts.size >= 3 && parts[1].toIntOrNull() == DEVICE_TYPE_BIKE_LIGHT
                }

                antDeviceCache = lights.map { device ->
                    val directBattery = translateBattery(device.details?.lastBattery?.name)
                    var batteryPercent = directBattery
                    var fromRadar = false
                    
                    if (batteryPercent == null) {
                        val suffix = device.id.split("-").last()
                        val radarBattery = otherBatteriesBySuffix[suffix]
                        if (radarBattery != null) {
                            batteryPercent = radarBattery
                            fromRadar = true
                        }
                    }
                    AntDeviceInfo(device.id, device.name, device.details?.manufacturer, batteryPercent, fromRadar)
                }
                updateAntLights()

                for (device in antDeviceCache) {
                    lightControl.registerConnectionState(device.id)
                    lightControl.registerForLightParameters(device.id)
                }
            }
        }
    }

    private data class AntDeviceInfo(
        val id: String,
        val name: String,
        val manufacturer: String?,
        val batteryPercent: Int?,
        val batteryFromRadar: Boolean = false
    )
    private var antDeviceCache = listOf<AntDeviceInfo>()

    private fun updateAntLights() {
        _antLights.value = antDeviceCache.map { device ->
            val assignment = engine.settings.lightAssignments.find { it.deviceId == device.id }
            // Only allow radar battery fallback for REAR lights
            val useRadarBattery = assignment?.role == LightRole.REAR
            
            DiscoveredLight(
                id = device.id,
                name = device.name,
                manufacturer = device.manufacturer,
                connected = lightControl.connectionStates.value[device.id] == "CONNECTED",
                batteryPercent = if (device.batteryFromRadar && !useRadarBattery) null else device.batteryPercent,
                batteryFromRadar = device.batteryFromRadar && useRadarBattery
            )
        }
    }

    private fun buildModeDetail(zone: DayTimeZone?): String {
        return engine.settings.lightAssignments.joinToString("\n") {
            val roleLabel = when (it.role) {
                LightRole.FRONT -> "F"
                LightRole.REAR -> "R"
            }
            val modeId = it.modeForZone(zone)
            val displayName = modeProviderFor(it.protocol, it.deviceId)
                .availableModes()
                .find { m -> m.id == modeId }?.displayName ?: modeId
            "$roleLabel: $displayName"
        }
    }

    override fun onBonusAction(actionId: String) {
        Timber.d("$TAG: BonusAction $actionId")
        when (actionId) {
            "toggle-lights" -> {
                engine.onToggleLights()
                val status = if (engine.activeZone.value != null) "ON" else "OFF"
                karooSystem.dispatch(
                    InRideAlert(
                        id = "light-toggle",
                        icon = R.drawable.ic_light,
                        title = "Lights $status",
                        detail = buildModeDetail(engine.activeZone.value),
                        autoDismissMs = 3000,
                        backgroundColor = android.R.color.black,
                        textColor = android.R.color.white,
                    ),
                )
            }
        }
    }

    private fun updateRadarMonitoring() {
        val needsRadar = engine.settings.lightAssignments.any { it.radarWarnFlash }
        if (needsRadar) startRadarMonitoring() else stopRadarMonitoring()
    }

    private fun startRadarMonitoring() {
        if (radarConsumerId != null) return
        Timber.d("$TAG: Starting radar monitoring")
        radarConsumerId = karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.RADAR),
        ) { event: OnStreamState ->
            if (event.state is StreamState.Streaming) {
                val values = (event.state as StreamState.Streaming).dataPoint.values
                val threat = values[DataType.Field.RADAR_THREAT_LEVEL]?.toInt() ?: 0
                onRadarThreat(threat > 0)
            }
        }
    }

    private fun stopRadarMonitoring() {
        radarConsumerId?.let {
            Timber.d("$TAG: Stopping radar monitoring")
            karooSystem.removeConsumer(it)
        }
        radarConsumerId = null
        radarThreatActive = false
    }

    private fun onRadarThreat(threatDetected: Boolean) {
        if (threatDetected == radarThreatActive) return
        radarThreatActive = threatDetected
        Timber.d("$TAG: Radar threat=${if (threatDetected) "DETECTED" else "CLEAR"}")

        for (assignment in engine.settings.lightAssignments) {
            if (!assignment.radarWarnFlash) continue
            val zone = engine.activeZone.value
            if (zone != null) continue

            val modeName = if (threatDetected) "FAST_FLASH" else assignment.modeOff
            lightControllers[assignment.protocol]?.setMode(assignment.deviceId, modeName)
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: Extension onDestroy")
        instance = null
        stopRadarMonitoring()
        engine.destroy()
        magicshineController.destroy()
        lightControl.unbind()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        extensionScope.cancel()
        super.onDestroy()
    }
}
