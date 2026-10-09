package io.github.JaJaJim.lightonkaroo

import android.content.Context
import io.github.JaJaJim.lightonkaroo.ble.BleRemoteController
import io.github.JaJaJim.lightonkaroo.ble.BleRemoteProtocol
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
    internal lateinit var bleRemoteController: BleRemoteController
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

    private var lastThreatLevel: Int = 0
    private var lastRadarEventTimestamp = 0L

    private var threatHoldJob: Job? = null
    private var bleStartJob: Job? = null
    private var discoveryPollingJob: Job? = null
    private var displayRotationJob: Job? = null
    private var unifiedScanCallback: android.bluetooth.le.ScanCallback? = null

    @Volatile private var settingsUiActive = false
    @Volatile private var rideActive = false
    val isRideActive: Boolean get() = rideActive

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
        bleRemoteController = BleRemoteController(applicationContext)

        lightControllers[LightProtocol.ANT_PLUS] = lightControl
        lightControllers[LightProtocol.BLE] = magicshineController

        magicshineController.onDeviceConnected = {
            stopBleIfNotNeeded()
            engine.onApplyState?.invoke(engine.activeState.value)
        }

        bleRemoteController.onButtonNotification = { hexPattern ->
            val settings = engine.settings
            if (settings.remoteSniffingActive) {
                val newSettings = settings.copy(
                    remoteBoundBytesHex = hexPattern,
                    remoteSniffingActive = false,
                )
                extensionScope.launch {
                    repository.updateSettings(newSettings)
                    engine.settings = newSettings
                }
                Timber.d("$TAG: Sniffed remote button pattern: $hexPattern")
            } else if (settings.remoteBoundBytesHex.isNotEmpty() && hexPattern.startsWith(settings.remoteBoundBytesHex)) {
                Timber.d("$TAG: Bound remote button pressed ($hexPattern)! Triggering light action...")
                engine.onToggleLights()
            }
        }

        engine = LightControlEngine()

        engine.onApplyState = { state ->
            for (assignment in engine.settings.lightAssignments) {
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
            for (assignment in engine.settings.lightAssignments) {
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
                val assignments = engine.settings.lightAssignments.filter { it.enabled }
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
                bleRemoteController.startSupervisor()
                if (!allAssignedBleConnected()) {
                    Timber.d("$TAG: Ride recording active/resumed but BLE lights not connected. Forcing unified BLE discovery scan.")
                    extensionScope.launch {
                        karooSystem.dispatch(RequestBluetooth(extension))
                        startUnifiedBleScan()
                    }
                }
                updateRadarMonitoring()
            }
            is RideState.Paused -> {
                engine.onRidePause()
                stopUnifiedBleScan()
            }
            is RideState.Idle -> {
                rideActive = false
                stopDiscoveryPolling()
                stopRadarMonitoring()
                stopRadarSimulation()
                engine.onRideStop()
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
            bleRemoteController.boundAddress = settings.remoteDeviceAddress
            syncBleAssignments()
            startBleIfNeeded()
            updateRadarMonitoring()
        }
    }

    private fun syncBleAssignments() {
        magicshineController.assignedDeviceIds = engine.settings.lightAssignments
            .filter { it.enabled && it.protocol == LightProtocol.BLE }
            .map { it.deviceId }
            .toSet()
    }

    fun testMode(deviceId: String, modeName: String) {
        val assignment = engine.settings.lightAssignments.find { it.deviceId == deviceId } ?: return
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
            for (assignment in engine.settings.lightAssignments) {
                lightControllers[assignment.protocol]?.setMode(assignment.deviceId, "OFF")
            }
        }
    }

    fun setSettingsUiActive(active: Boolean) {
        Timber.d("$TAG: setSettingsUiActive=$active")
        settingsUiActive = active
        magicshineController.isSettingsUiActive = active
        bleRemoteController.isSettingsUiActive = active

        if (active) {
            lightControl.bind()
            startDiscoveryPolling()
            startBleIfNeeded()
            startDisplayRotation()
            bleRemoteController.startSupervisor()
            extensionScope.launch {
                delay(500)
                for (assignment in engine.settings.lightAssignments) {
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

    private fun hasBleAssignments(): Boolean =
        engine.settings.lightAssignments.any { it.enabled && it.protocol == LightProtocol.BLE } || engine.settings.remoteDeviceAddress.isNotEmpty()

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
                    Timber.d("$TAG: Starting single unified BLE discovery scan")
                    startUnifiedBleScan()
                }
            }
        }
    }

    private fun startUnifiedBleScan() {
        if (unifiedScanCallback != null) return
        extensionScope.launch {
            val bluetoothManager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            var scanner: android.bluetooth.le.BluetoothLeScanner? = null
            for (attempt in 1..5) {
                if (unifiedScanCallback != null) break
                scanner = bluetoothManager?.adapter?.bluetoothLeScanner
                if (scanner != null) break
                delay(500L)
            }
            if (scanner == null || unifiedScanCallback != null) return@launch

            val callback = object : android.bluetooth.le.ScanCallback() {
                override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                    try {
                        val address = result.device?.address ?: return
                        val rawName = result.scanRecord?.deviceName ?: try { result.device?.name } catch (_: SecurityException) { null } ?: return
                        val nameUpper = rawName.uppercase()

                        val isMagicshine = nameUpper.contains("MAGICSHINE") ||
                                           nameUpper.contains("M1") ||
                                           nameUpper.contains("M2") ||
                                           nameUpper.contains("M3") ||
                                           nameUpper.contains("HORI") ||
                                           nameUpper.contains("EVO") ||
                                           nameUpper.contains("CBL") ||
                                           nameUpper.contains("RAY") ||
                                           nameUpper.contains("SEEMEE") ||
                                           nameUpper.contains("MONTEER")

                        val isRemote = BleRemoteProtocol.SUPPORTED_PREFIXES.any { nameUpper.contains(it) } ||
                                       address == bleRemoteController.boundAddress

                        if (isMagicshine) {
                            Timber.d("$TAG: Unified BLE scan found Magicshine light: $rawName ($address)")
                            magicshineController.registerFoundDevice(address, rawName)
                        }
                        if (isRemote) {
                            Timber.d("$TAG: Unified BLE scan found Remote: $rawName ($address)")
                            bleRemoteController.registerFoundDevice(address, rawName)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "$TAG: Skipping unified scan result")
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Timber.e("$TAG: Unified BLE scan failed: $errorCode")
                }
            }

            try {
                val settings = android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(null, settings, callback)
                unifiedScanCallback = callback
                Timber.d("$TAG: Single unified BLE scan started successfully")
            } catch (e: SecurityException) {
                Timber.e(e, "$TAG: Permission missing for BLE scan")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to start unified BLE scan")
            }
        }
    }

    private fun stopUnifiedBleScan() {
        unifiedScanCallback?.let { cb ->
            try {
                val bluetoothManager = applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (e: SecurityException) {
                Timber.w(e, "$TAG: Permission missing to stop unified BLE scan")
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to stop unified BLE scan")
            }
        }
        unifiedScanCallback = null
        magicshineController.stopDiscovery()
        bleRemoteController.stopDiscovery()
    }

    private fun stopBleIfNotNeeded() {
        if (settingsUiActive) return
        if (!hasBleAssignments()) {
            stopUnifiedBleScan()
            karooSystem.dispatch(ReleaseBluetooth(extension))
        } else if (allAssignedBleConnected()) {
            stopUnifiedBleScan()
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
        _antLights.value = antDeviceCache.map { device ->
            val assignment = engine.settings.lightAssignments.find { it.deviceId == device.id }
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
        return engine.settings.lightAssignments.filter { it.enabled }.joinToString("\n") {
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
        val needsRadar = engine.settings.softwareThreatModeEnabled && engine.settings.lightAssignments.any { it.enabled && it.isThreatModeEnabled && it.protocol == LightProtocol.ANT_PLUS }
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

            val currentProfileAssignments = engine.settings.lightAssignments
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

                val currentProfileAssignments = engine.settings.lightAssignments
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
        bleRemoteController.destroy()
        lightControl.unbind()
        karooSystem.dispatch(ReleaseBluetooth(extension))
        karooSystem.disconnect()
        extensionScope.cancel()
        super.onDestroy()
    }
}
