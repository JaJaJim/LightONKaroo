package io.github.JaJaJim.lightonkaroo

import io.github.JaJaJim.lightonkaroo.ble.MagicshineBleController
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.data.LightRole
import io.github.JaJaJim.lightonkaroo.data.PreferencesRepository
import io.github.JaJaJim.lightonkaroo.data.modeProviderFor
import io.github.JaJaJim.lightonkaroo.datatypes.LightStatusDataType
import io.github.JaJaJim.lightonkaroo.engine.LightControlEngine
import io.github.JaJaJim.lightonkaroo.karoo.KarooLightControl
import io.github.JaJaJim.lightonkaroo.light.LightController
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.ReleaseBluetooth
import io.hammerhead.karooext.models.RequestBluetooth
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.SavedDevices
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
    private var radarSimulationJob: Job? = null

    @Volatile
    var isRadarThreatActive: Boolean = false
        private set

    @Volatile
    var activeProfileIndex: Int = 1

    private val activeDataFieldProfiles = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    fun registerActiveDataField(profileIndex: Int) {
        activeDataFieldProfiles.add(profileIndex)
        activeProfileIndex = getMasterDataFieldProfile()
        syncBleAssignments()
        startBleIfNeeded()
        for (id in magicshineController.assignedDeviceIds) {
            magicshineController.connect(id)
        }
        Timber.d("$TAG: Registered data field Bike $profileIndex. Active profiles: $activeDataFieldProfiles, Master: $activeProfileIndex")
    }

    fun unregisterActiveDataField(profileIndex: Int) {
        activeDataFieldProfiles.remove(profileIndex)
        activeProfileIndex = getMasterDataFieldProfile()
        syncBleAssignments()
        startBleIfNeeded()
        for (id in magicshineController.assignedDeviceIds) {
            magicshineController.connect(id)
        }
        Timber.d("$TAG: Unregistered data field Bike $profileIndex. Active profiles: $activeDataFieldProfiles, Master: $activeProfileIndex")
    }

    fun getMasterDataFieldProfile(): Int {
        return activeDataFieldProfiles.minOrNull() ?: activeProfileIndex
    }

    private var lastThreatLevel: Int = 0
    private var lastRadarEventTimestamp = 0L

    private var threatHoldJob: Job? = null
    private var bleStartJob: Job? = null
    private var discoveryPollingJob: Job? = null
    private var displayRotationJob: Job? = null
    @Volatile private var settingsUiActive = false
    @Volatile private var rideActive = false
    val isRideActive: Boolean get() = rideActive

    private val extensionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val types by lazy {
        listOf(
            LightStatusDataType(engine, bikeProfileIndex = 1),
            LightStatusDataType(engine, bikeProfileIndex = 2),
            LightStatusDataType(engine, bikeProfileIndex = 3),
            LightStatusDataType(engine, bikeProfileIndex = 4),
        )
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
            engine.onApplyState?.invoke(engine.activeState.value)
        }
        engine = LightControlEngine()

        engine.onApplyState = { state ->
            val masterIndex = getMasterDataFieldProfile()
            val assignments = engine.settings.assignmentsForProfile(masterIndex)
            for (assignment in assignments) {
                if (!assignment.enabled) continue

                // If threat is active, defer mode changes for threat-participating lights until threat clears
                if (isRadarThreatActive && assignment.useForThreatMode && assignment.protocol == LightProtocol.ANT_PLUS) {
                    Timber.d("$TAG: Threat active! Deferring physical light change for ${assignment.displayName}")
                    continue
                }

                val modeName = assignment.modeForState(state)
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, modeName)
            }
            updateRadarMonitoring()
        }

        engine.onApplyHardwareOff = {
            val masterIndex = getMasterDataFieldProfile()
            val assignments = engine.settings.assignmentsForProfile(masterIndex)
            for (assignment in assignments) {
                if (!assignment.enabled) continue
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, "OFF")
            }
        }

        // Merge ANT+ and BLE discovered lights
        extensionScope.launch {
            combine(
                _antLights,
                magicshineController.discoveredLights,
                lightControl.connectionStates,
                lightControl.actualModes,
            ) { ant, ble, connStates, actualModes ->
                ant.map {
                    it.copy(
                        connected = connStates[it.id] == "CONNECTED",
                        currentMode = actualModes[it.id],
                    )
                } + ble
            }.collect { merged ->
                _discoveredLights.value = merged
            }
        }

        lightControl.onServiceReady = {
            engine.onApplyState?.invoke(engine.activeState.value)
        }

        karooSystem.connect { connected ->
            Timber.d("$TAG: Karoo system connected=$connected")
            if (connected) {
                setupConsumers()
                loadSettings()
            }
        }
    }

    internal fun startDisplayRotation() {
        if (displayRotationJob != null) return
        displayRotationJob = extensionScope.launch {
            var currentIndex = 0
            while (true) {
                val masterIndex = getMasterDataFieldProfile()
                val assignments = engine.settings.assignmentsForProfile(masterIndex)
                val discovered = discoveredLights.value

                val allConnected = assignments.isNotEmpty() && assignments.all { assignment ->
                    discovered.find { it.id == assignment.deviceId }?.connected == true
                }

                if (assignments.isNotEmpty()) {
                    val currentInfo = engine.displayInfo.value
                    if (engine.settings.showDetailedStatus && !currentInfo.isSimulatedRadar && !isRadarThreatActive) {
                        currentIndex %= assignments.size
                        val assignment = assignments[currentIndex]
                        val light = discovered.find { it.id == assignment.deviceId }

                        val statusText = when {
                            light == null -> "OFFLINE"
                            !light.connected -> "SEARCHING"
                            else -> light.currentMode?.replace("_", " ") ?: "CONNECTED"
                        }

                        val battery = light?.batteryPercent
                        val (batteryLabel, batteryColor) = when {
                            battery == null -> "Unknown" to 0xFFAAAAAA.toInt()
                            battery > 50 -> "Good" to 0xFF32e09a.toInt()
                            battery >= 25 -> "Medium" to 0xFFffe714.toInt()
                            else -> "Low" to 0xFFd34343.toInt()
                        }

                        val nextInfo = currentInfo.copy(
                            deviceName = assignment.displayName,
                            statusText = statusText,
                            batteryLabel = batteryLabel,
                            batteryColor = batteryColor,
                            batteryFromRadar = light?.batteryFromRadar ?: false,
                            allConnected = allConnected,
                            temperature = light?.temperature,
                        )
                        if (currentInfo != nextInfo) {
                            engine.updateDisplayInfo(nextInfo)
                        }
                        currentIndex++
                    } else {
                        val nextInfo = currentInfo.copy(
                            allConnected = allConnected,
                        )
                        if (currentInfo != nextInfo) {
                            engine.updateDisplayInfo(nextInfo)
                        }
                    }
                } else {
                    val currentInfo = engine.displayInfo.value
                    val nextInfo = currentInfo.copy(
                        deviceName = "No Lights",
                        statusText = "Add in settings",
                        allConnected = false,
                    )
                    if (currentInfo != nextInfo) {
                        engine.updateDisplayInfo(nextInfo)
                    }
                }

                val speedSec = if (engine.settings.showDetailedStatus) {
                    engine.settings.rotationSpeedSeconds.coerceAtLeast(1)
                } else {
                    5
                }
                delay(speedSec * 1000L)
            }
        }
    }

    internal fun stopDisplayRotation() {
        displayRotationJob?.cancel()
        displayRotationJob = null
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
                startBleIfNeeded()
                // If any assigned BLE light is not connected upon ride start or resume, force start BLE discovery immediately!
                if (!allAssignedBleConnected()) {
                    Timber.d("$TAG: Ride recording active/resumed but BLE lights not connected. Forcing BLE discovery scan.")
                    extensionScope.launch {
                        karooSystem.dispatch(RequestBluetooth(extension))
                        magicshineController.startDiscovery()
                    }
                }
                updateRadarMonitoring()
            }
            is RideState.Paused -> {
                engine.onRidePause()
                // Stop BLE discovery during pause to conserve battery
                magicshineController.stopDiscovery()
            }
            is RideState.Idle -> {
                rideActive = false
                stopDiscoveryPolling()
                stopRadarMonitoring()
                stopRadarSimulation()
                engine.onRideStop()
                // Wait 1 second for queued OFF commands to transmit over ANT+/BLE before unbinding IPC binder
                extensionScope.launch {
                    delay(1000L)
                    if (!settingsUiActive && !rideActive) {
                        lightControl.unbind()
                    }
                }
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
            updateRadarMonitoring()
        }
    }

    private fun syncBleAssignments() {
        val masterIndex = getMasterDataFieldProfile()
        magicshineController.assignedDeviceIds = engine.settings.assignmentsForProfile(masterIndex)
            .filter { it.enabled && it.protocol == LightProtocol.BLE }
            .map { it.deviceId }
            .toSet()
    }

    fun testMode(deviceId: String, modeName: String) {
        val masterIndex = getMasterDataFieldProfile()
        val assignment = engine.settings.assignmentsForProfile(masterIndex).find { it.deviceId == deviceId } ?: return
        extensionScope.launch {
            lightControllers[assignment.protocol]?.setMode(deviceId, modeName)
            delay(3000)
            lightControllers[assignment.protocol]?.setMode(deviceId, "OFF")
        }
    }

    fun onAssignmentChanged() {
        syncBleAssignments()
        for (id in magicshineController.assignedDeviceIds) {
            magicshineController.connect(id)
        }
        startBleIfNeeded()
        updateRadarMonitoring()
        if (settingsUiActive) {
            val masterIndex = getMasterDataFieldProfile()
            for (assignment in engine.settings.assignmentsForProfile(masterIndex)) {
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, "OFF")
            }
        }
    }

    fun setSettingsUiActive(active: Boolean) {
        Timber.d("$TAG: setSettingsUiActive=$active")
        settingsUiActive = active
        magicshineController.isSettingsUiActive = active
        if (active) {
            lightControl.bind()
            startDiscoveryPolling()
            startBleIfNeeded()
            startDisplayRotation()
            extensionScope.launch {
                delay(500)
                val masterIndex = getMasterDataFieldProfile()
                for (assignment in engine.settings.assignmentsForProfile(masterIndex)) {
                    lightControllers[assignment.protocol]?.setMode(assignment.deviceId, "OFF")
                }
            }
        } else {
            stopRadarMonitoring()
            stopRadarSimulation()
            if (!rideActive) {
                stopDiscoveryPolling()
                stopDisplayRotation()
                lightControl.unbind()
            } else {
                engine.onApplyState?.invoke(engine.activeState.value)
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
                delay(10_000)
            }
            discoveryPollingJob = null
        }
    }

    private fun stopDiscoveryPolling() {
        discoveryPollingJob?.cancel()
        discoveryPollingJob = null
    }

    private fun hasBleAssignments(): Boolean {
        val masterIndex = getMasterDataFieldProfile()
        return engine.settings.assignmentsForProfile(masterIndex).any { it.enabled && it.protocol == LightProtocol.BLE }
    }

    private fun startBleIfNeeded() {
        Timber.d("$TAG: startBleIfNeeded: settingsUiActive=$settingsUiActive, hasBleAssignments=${hasBleAssignments()}")
        if (settingsUiActive || hasBleAssignments()) {
            bleStartJob?.cancel()
            bleStartJob = extensionScope.launch {
                delay(2000)
                karooSystem.dispatch(RequestBluetooth(extension))
                for (id in magicshineController.assignedDeviceIds) {
                    magicshineController.connect(id)
                }
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
                    "GOOD", "NEW" -> 80
                    "OK" -> 50
                    "LOW" -> 20
                    "CRITICAL" -> 5
                    else -> if (name != null) 100 else null
                }

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
                    lightControl.forceRefreshLightParameters(device.id)
                }
            }
        }
    }

    private data class AntDeviceInfo(
        val id: String,
        val name: String,
        val manufacturer: String?,
        val batteryPercent: Int?,
        val batteryFromRadar: Boolean = false,
    )
    private var antDeviceCache = listOf<AntDeviceInfo>()

    private fun updateAntLights() {
        val masterIndex = getMasterDataFieldProfile()
        _antLights.value = antDeviceCache.map { device ->
            val assignment = engine.settings.assignmentsForProfile(masterIndex).find { it.deviceId == device.id }
            val useRadarBattery = assignment?.role == LightRole.REAR

            DiscoveredLight(
                id = device.id,
                name = device.name,
                manufacturer = device.manufacturer,
                connected = lightControl.connectionStates.value[device.id] == "CONNECTED",
                batteryPercent = if (device.batteryFromRadar && !useRadarBattery) null else device.batteryPercent,
                batteryFromRadar = device.batteryFromRadar && useRadarBattery,
            )
        }
    }

    private fun buildModeDetail(state: Int): String {
        if (state == 0) return "Lights Off"
        val masterIndex = getMasterDataFieldProfile()
        return engine.settings.assignmentsForProfile(masterIndex).filter { it.enabled }.joinToString("\n") {
            val modeId = it.modeForState(state)
            val displayName = modeProviderFor(it.protocol, it.deviceId)
                .availableModes()
                .find { m -> m.id == modeId }?.displayName ?: modeId
            "${it.displayName}: $displayName"
        }
    }

    override fun onBonusAction(actionId: String) {
        Timber.d("$TAG: BonusAction $actionId")
        when (actionId) {
            "toggle-lights" -> {
                engine.onToggleLights()
                val state = engine.activeState.value
                val status = when (state) {
                    1 -> "PRIMARY ON"
                    2 -> "SECONDARY ON"
                    else -> "OFF"
                }
                karooSystem.dispatch(
                    InRideAlert(
                        id = "light-toggle",
                        icon = R.drawable.ic_light,
                        title = "Lights $status",
                        detail = buildModeDetail(state),
                        autoDismissMs = 3000,
                        backgroundColor = android.R.color.black,
                        textColor = android.R.color.white,
                    ),
                )
            }
        }
    }

    private fun updateRadarMonitoring() {
        val masterIndex = getMasterDataFieldProfile()
        val needsRadar = engine.settings.softwareThreatModeEnabled && engine.settings.assignmentsForProfile(masterIndex).any { it.enabled && it.isThreatModeEnabled && it.protocol == LightProtocol.ANT_PLUS }
        val simulate = engine.settings.softwareThreatModeEnabled && engine.settings.simulateRadar

        if (simulate) {
            stopRadarMonitoring()
            if (rideActive) {
                startRadarSimulation()
            } else {
                stopRadarSimulation()
            }
        } else {
            stopRadarSimulation()
            if (needsRadar) startRadarMonitoring() else stopRadarMonitoring()
        }
    }

    private fun startRadarSimulation() {
        if (radarSimulationJob != null) return
        Timber.d("$TAG: Starting action-packed FIT-based stress test radar simulation loop")
        radarSimulationJob = extensionScope.launch {
            while (isActive && rideActive) {
                // 1. Initial Start Check (8s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(8000L)
                if (!isActive) break

                // STRESS PHASE A
                // 2. Real FIT Event 1: 1 Car @ 32 km/h (Level 1 Yellow) (12s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 1,
                        simThreatLevel = 1,
                    ),
                )
                onRadarThreatLevel(1)
                delay(12000L)
                if (!isActive) break

                // 3. Fast Platoon: 3 Cars @ 50 km/h (Level 2 Red) (5s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 3,
                        simThreatLevel = 2,
                    ),
                )
                onRadarThreatLevel(2)
                delay(5000L)
                if (!isActive) break

                // 4. 1-Second Blitz Gap! (1s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(1000L)
                if (!isActive) break

                // 5. Tailgater: 2 Cars (Level 2 Red) (8s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 2,
                        simThreatLevel = 2,
                    ),
                )
                onRadarThreatLevel(2)
                delay(8000L)
                if (!isActive) break

                // 6. Mini Pause (8s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(8000L)
                if (!isActive) break

                // STRESS PHASE B
                // 7. Slow Car (1 Car @ Level 1 Yellow) (6s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 1,
                        simThreatLevel = 1,
                    ),
                )
                onRadarThreatLevel(1)
                delay(6000L)
                if (!isActive) break

                // 8. Overtaking Sports Car (3 Cars @ Level 2 Red) (7s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 3,
                        simThreatLevel = 2,
                    ),
                )
                onRadarThreatLevel(2)
                delay(7000L)
                if (!isActive) break

                // 9. 1-Second Blitz Gap! (1s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(1000L)
                if (!isActive) break

                // 10. Second Tailgater (2 Cars @ Level 2 Red) (8s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 2,
                        simThreatLevel = 2,
                    ),
                )
                onRadarThreatLevel(2)
                delay(8000L)
                if (!isActive) break

                // 11. Mini Pause (8s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(8000L)
                if (!isActive) break

                // STRESS PHASE C (Rush Hour Cascade)
                // 12. City Traffic Car 1 (1 Car @ Level 1 Yellow) (8s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 1,
                        simThreatLevel = 1,
                    ),
                )
                onRadarThreatLevel(1)
                delay(8000L)
                if (!isActive) break

                // 13. 1-Second Blitz Gap! (1s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(1000L)
                if (!isActive) break

                // 14. Overtake Double (3 Cars @ Level 2 Red) (9s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 3,
                        simThreatLevel = 2,
                    ),
                )
                onRadarThreatLevel(2)
                delay(9000L)
                if (!isActive) break

                // 15. 1-Second Blitz Gap! (1s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(1000L)
                if (!isActive) break

                // 16. Dense Platoon (2 Cars @ Level 1 Yellow) (9s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 2,
                        simThreatLevel = 1,
                    ),
                )
                onRadarThreatLevel(1)
                delay(9000L)
                if (!isActive) break

                // 17. Final Clear Check (8s)
                engine.updateDisplayInfo(
                    engine.displayInfo.value.copy(
                        isSimulatedRadar = true,
                        simVehicleCount = 0,
                        simThreatLevel = 0,
                    ),
                )
                onRadarThreatLevel(0)
                delay(8000L)
            }
        }
    }

    private fun stopRadarSimulation() {
        radarSimulationJob?.cancel()
        radarSimulationJob = null
        if (engine.displayInfo.value.isSimulatedRadar) {
            engine.updateDisplayInfo(
                engine.displayInfo.value.copy(
                    isSimulatedRadar = false,
                    simVehicleCount = 0,
                    simThreatLevel = 0,
                ),
            )
        }
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
                onRadarThreatLevel(threat)
            }
        }
    }

    private fun stopRadarMonitoring() {
        threatHoldJob?.cancel()
        threatHoldJob = null
        radarConsumerId?.let {
            Timber.d("$TAG: Stopping radar monitoring")
            karooSystem.removeConsumer(it)
        }
        radarConsumerId = null
        isRadarThreatActive = false
        lastThreatLevel = 0
        engine.updateDisplayInfo(engine.displayInfo.value)
    }

    private fun onRadarThreatLevel(threatLevel: Int) {
        val now = System.currentTimeMillis()
        val threatDetected = threatLevel > 0

        // Rate-limiting / Throttling guard: ignore redundant radar callbacks firing faster than 100ms apart
        if (now - lastRadarEventTimestamp < 100L && lastThreatLevel == threatLevel && isRadarThreatActive == threatDetected) {
            return
        }
        lastRadarEventTimestamp = now

        threatHoldJob?.cancel()

        if (threatDetected) {
            val previousThreatLevel = lastThreatLevel
            isRadarThreatActive = true
            lastThreatLevel = threatLevel
            Timber.d("$TAG: Software threat ACTIVE level=$threatLevel (previous=$previousThreatLevel)")
            engine.updateDisplayInfo(engine.displayInfo.value)

            // If the exact same threat level is already active, skip re-transmitting duplicate command
            if (previousThreatLevel == threatLevel && isRadarThreatActive) {
                Timber.d("$TAG: Threat level $threatLevel already active; skipping duplicate ANT+ transmission.")
                return
            }

            val masterIndex = getMasterDataFieldProfile()
            val currentProfileAssignments = engine.settings.assignmentsForProfile(masterIndex)
            for (assignment in currentProfileAssignments) {
                if (!assignment.enabled || !assignment.useForThreatMode || assignment.protocol != LightProtocol.ANT_PLUS) continue

                val activeState = engine.activeState.value
                val overrideActive = engine.settings.overrideActiveModes

                if (previousThreatLevel == 0 && activeState != 0 && !overrideActive) continue

                val targetMode = assignment.softwareThreatMode
                if (targetMode != "DISABLED") {
                    lightControllers[assignment.protocol]?.setMode(assignment.deviceId, targetMode)
                }
            }
        } else {
            // Guard check: If already clear, do not launch duplicate clear jobs every 250ms from radar stream spam
            if (!isRadarThreatActive && threatHoldJob == null) {
                return
            }
            if (!isRadarThreatActive) {
                return
            }

            isRadarThreatActive = false
            threatHoldJob?.cancel()
            val holdSeconds = engine.settings.threatHoldTimeSeconds.coerceAtMost(3)
            threatHoldJob = extensionScope.launch {
                if (holdSeconds > 0) {
                    delay(holdSeconds * 1000L)
                }
                // Guard check: If a new threat arrived while delay was completing, do NOT clear or restore!
                if (isRadarThreatActive) {
                    Timber.d("$TAG: New threat arrived during hold time delay; aborting restore command.")
                    return@launch
                }
                lastThreatLevel = 0
                threatHoldJob = null
                Timber.d("$TAG: Software threat CLEAR (after hold time)")
                engine.updateDisplayInfo(engine.displayInfo.value)

                val masterIndex = getMasterDataFieldProfile()
                val currentProfileAssignments = engine.settings.assignmentsForProfile(masterIndex)
                for (assignment in currentProfileAssignments) {
                    if (!assignment.enabled || !assignment.useForThreatMode || assignment.protocol != LightProtocol.ANT_PLUS) continue
                    val activeState = engine.activeState.value
                    val restoreMode = assignment.modeForState(activeState)
                    lightControllers[assignment.protocol]?.setMode(assignment.deviceId, restoreMode)
                }
            }
        }
    }

    override fun onDestroy() {
        Timber.d("$TAG: Extension onDestroy")
        instance = null
        stopRadarSimulation()
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
