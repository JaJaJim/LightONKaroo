package io.github.JaJaJim.lightonkaroo.ble

import android.content.Context
import io.github.JaJaJim.lightonkaroo.DiscoveredLight
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.light.LightController
import io.github.JaJaJim.lightonkaroo.protocol.FrameAssembler
import io.github.JaJaJim.lightonkaroo.protocol.IgpsProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import no.nordicsemi.kotlin.ble.core.WriteType
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class IgpsportBleController(context: Context) : LightController {

    companion object {
        private const val TAG = "IgpsportBle"
        private const val WRITE_RETRIES = 5
        private const val WRITE_RETRY_DELAY_MS = 60L
        private const val RECONNECT_MIN_MS = 5_000L
        private const val RECONNECT_MAX_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val centralManager by lazy { CentralManager.native(appContext, scope) }
    private val writeMutex = Mutex()

    private val nordicUartService = Uuid.parse(IgpsProtocol.UART_SERVICE) // 6e400001-b5a3-f393-e0a9-e50e24dcca9e
    private val nordicUartWrite = Uuid.parse(IgpsProtocol.UART_WRITE)     // 6e400002-b5a3-f393-e0a9-e50e24dcca9e
    private val nordicUartNotify = Uuid.parse(IgpsProtocol.UART_NOTIFY)   // 6e400003-b5a3-f393-e0a9-e50e24dcca9e
    private val legacyService = Uuid.parse("0000FF12-0000-1000-8000-00805f9b34fb")
    private val legacyWrite = Uuid.parse("0000FF01-0000-1000-8000-00805f9b34fb")

    private data class BleDevice(val peripheral: Peripheral, val name: String)

    private val devices = ConcurrentHashMap<String, BleDevice>()
    private val characteristics = ConcurrentHashMap<String, RemoteCharacteristic>()
    private val deviceConfigs = ConcurrentHashMap<String, IgpsportDeviceConfig>()
    private val batteryLevelsRaw = ConcurrentHashMap<String, String>()
    private val batteryLevels = ConcurrentHashMap<String, Int>()
    private val frameAssemblers = ConcurrentHashMap<String, FrameAssembler>()

    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    private val _actualModes = MutableStateFlow<Map<String, String>>(emptyMap())
    val actualModes: StateFlow<Map<String, String>> = _actualModes

    var onDeviceConnected: (() -> Unit)? = null
    var assignedDeviceIds: Set<String> = emptySet()
    var isSettingsUiActive: Boolean = false

    fun getDeviceConfig(address: String): IgpsportDeviceConfig {
        return deviceConfigs.getOrPut(address) { IgpsportDeviceConfig() }
    }

    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private val connectionJobs = ConcurrentHashMap<String, Job>()

    private val bleScanner: android.bluetooth.le.BluetoothLeScanner?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    fun queryRawBattery(address: String) {
        scope.launch {
            Timber.d("$TAG: Querying battery for $address...")
            if (!characteristics.containsKey(address)) {
                Timber.w("$TAG: Device $address not connected, attempting connection first...")
                connect(address)
                delay(1500)
            }
            sendFrame(address, IgpsProtocol.readBattery())
        }
    }

    fun startDiscovery() {
        if (scanCallback != null) return
        scope.launch {
            for (attempt in 1..5) {
                if (scanCallback != null) break
                val scanner = bleScanner
                if (scanner != null) {
                    Timber.d("$TAG: Starting iGPSPORT BLE discovery (attempt $attempt)")
                    executeBleScan(scanner)
                    break
                }
                Timber.w("$TAG: iGPSPORT BLE scanner null, retrying in 500ms (attempt $attempt)...")
                delay(500L)
            }
        }
    }

    private fun executeBleScan(scanner: android.bluetooth.le.BluetoothLeScanner) {
        if (scanCallback != null) return
        val callback = object : android.bluetooth.le.ScanCallback() {
            override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
                try {
                    val address = result.device?.address ?: return
                    if (devices.containsKey(address)) return

                    val rawName = result.scanRecord?.deviceName ?: result.device?.name ?: return
                    val nameUpper = rawName.uppercase()

                    if (nameUpper.contains("MAGICSHINE") || nameUpper.contains("HORI") || nameUpper.contains("M1") || nameUpper.contains("M2")) return

                    val isIgpsport = nameUpper.contains("VS") || nameUpper.contains("IGP") || nameUpper.contains("IGPSPORT") ||
                                     address in assignedDeviceIds

                    if (isIgpsport) {
                        Timber.d("$TAG: Found iGPSPORT light: $rawName ($address)")
                        scope.launch { registerFoundDevice(address, rawName) }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Skipping scan result")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("$TAG: iGPSPORT BLE scan failed: $errorCode")
            }
        }
        try {
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
            scope.launch {
                delay(10_000L)
                stopDiscovery()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start iGPSPORT BLE scan")
        }
    }

    internal fun registerFoundDevice(address: String, name: String) {
        if (devices.containsKey(address)) return
        val peripheral = centralManager.getPeripheralsById(listOf(address)).firstOrNull() ?: run {
            Timber.w("$TAG: No peripheral for $address")
            return
        }
        devices[address] = BleDevice(peripheral, name)
        deviceConfigs[address] = IgpsportDeviceConfig()
        Timber.d("$TAG: iGPSPORT device registered: $name ($address)")
        updateDiscoveredLights()
        startConnectionSupervisor(address)
    }

    fun connect(address: String) {
        if (characteristics.containsKey(address)) return
        startConnectionSupervisor(address)
    }

    fun stopDiscovery() {
        scanCallback?.let { cb ->
            try {
                bleScanner?.stopScan(cb)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to stop iGPSPORT BLE scan")
            }
        }
        scanCallback = null
    }

    private val failureCounts = ConcurrentHashMap<String, Int>()

    fun resetBackoff(address: String? = null) {
        if (address != null) {
            failureCounts[address] = 0
        } else {
            failureCounts.clear()
        }
    }

    private fun startConnectionSupervisor(address: String) {
        if (connectionJobs[address]?.isActive == true) return
        connectionJobs[address] = scope.launch {
            while (isActive && (address in assignedDeviceIds || isSettingsUiActive)) {
                val wasConnected = attemptConnect(address)
                if (wasConnected) {
                    failureCounts[address] = 0
                } else {
                    val current = failureCounts[address] ?: 0
                    failureCounts[address] = current + 1
                }

                val failures = failureCounts[address] ?: 0
                val delayMs = when {
                    failures <= 3 -> 3_000L
                    failures <= 6 -> 10_000L
                    else -> 60_000L
                }

                delay(delayMs)
            }
            connectionJobs.remove(address)
        }
    }

    fun disconnect(address: String) {
        connectionJobs.remove(address)?.cancel()
        characteristics.remove(address)
        batteryLevelsRaw.remove(address)
        batteryLevels.remove(address)
        frameAssemblers.remove(address)
        failureCounts.remove(address)
        updateDiscoveredLights()
    }

    fun allConnected(deviceIds: Set<String>): Boolean {
        if (deviceIds.isEmpty()) return true
        return deviceIds.all { characteristics.containsKey(it) }
    }

    private fun resolveConfirmedName(address: String, peripheralName: String?): String {
        val existing = devices[address]?.name
        if (!existing.isNullOrEmpty() && existing != "iGPSPORT Light" && existing != "iGPSPORT") {
            return existing
        }
        if (!peripheralName.isNullOrEmpty() && peripheralName != "iGPSPORT Light" && peripheralName != "iGPSPORT") {
            return peripheralName
        }
        return "iGPSPORT Light"
    }

    private suspend fun attemptConnect(address: String): Boolean {
        val peripheral = devices[address]?.peripheral
            ?: centralManager.getPeripheralsById(listOf(address)).firstOrNull()
            ?: return false
        val confirmedName = resolveConfirmedName(address, peripheral.name)
        devices[address] = BleDevice(peripheral, confirmedName)
        deviceConfigs.putIfAbsent(address, IgpsportDeviceConfig())

        return try {
            stopDiscovery()
            Timber.d("$TAG: Connecting to iGPSPORT light at $address ($confirmedName)")
            val options = CentralManager.ConnectionOptions.Direct(
                timeout = 8.seconds,
                retry = 2,
                retryDelay = 1.seconds,
            )
            centralManager.connect(peripheral, options)

            val connected = withTimeoutOrNull(10_000) {
                if (peripheral.state.value is ConnectionState.Connected) {
                    true
                } else {
                    peripheral.state.first { it is ConnectionState.Connected }
                    true
                }
            } ?: false
            if (!connected) {
                Timber.w("$TAG: Connection timeout for iGPSPORT light $address")
                return false
            }

            val (writeChar, notifyChar) = findGattCharacteristics(peripheral) ?: return false
            characteristics[address] = writeChar
            Timber.d("$TAG: Connected to iGPSPORT light $address ($confirmedName), write & notify characteristics ready")

            scope.launch {
                try {
                    val gapServiceUuid = java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
                    val devNameUuid = java.util.UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
                    val services = peripheral.services().value
                    val gapService = services.firstOrNull { it.uuid == gapServiceUuid }
                    val nameChar = gapService?.characteristics?.firstOrNull { it.uuid == devNameUuid }
                    if (nameChar != null) {
                        val nameBytes = nameChar.read()
                        val gattName = String(nameBytes, Charsets.UTF_8).trim()
                        if (gattName.isNotEmpty() && gattName != "iGPSPORT Light" && gattName != "iGPSPORT") {
                            Timber.d("$TAG: Read hardware GATT device name for $address: '$gattName'")
                            devices[address] = BleDevice(peripheral, gattName)
                            updateDiscoveredLights()
                        }
                    }
                } catch (_: Exception) {
                    Timber.d("$TAG: Optional GATT device name read skipped for $address")
                }
            }

            val notifyJob = scope.launch {
                try {
                    val listenChar = notifyChar ?: writeChar
                    Timber.d("$TAG: Subscribing to GATT notifications on characteristic ${listenChar.uuid}...")
                    listenChar.subscribe().collect { data -> parseNotification(address, data) }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Failed to subscribe to GATT notifications on $address")
                }
            }

            val batteryPollJob = scope.launch {
                delay(2000)
                while (isActive) {
                    Timber.d("$TAG: Querying battery for iGPSPORT $address (periodic 60s poll)...")
                    sendFrame(address, IgpsProtocol.readBattery())
                    delay(60_000L)
                }
            }

            updateDiscoveredLights()
            onDeviceConnected?.invoke()

            peripheral.state.first { it is ConnectionState.Disconnected }
            batteryPollJob.cancel()
            notifyJob.cancel()
            Timber.d("$TAG: Disconnected from iGPSPORT light $address")
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Connect attempt failed for iGPSPORT light $address")
            false
        } finally {
            characteristics.remove(address)
            batteryLevelsRaw.remove(address)
            batteryLevels.remove(address)
            frameAssemblers.remove(address)
            updateDiscoveredLights()
        }
    }

    private suspend fun findGattCharacteristics(peripheral: Peripheral): Pair<RemoteCharacteristic, RemoteCharacteristic?>? {
        for (attempt in 1..10) {
            try {
                val services = peripheral.services().value

                // 1. Primary: Nordic UART Service (VS1800S)
                val nordicService = services.firstOrNull { it.uuid == nordicUartService }
                val writeChar = nordicService?.characteristics?.firstOrNull { it.uuid == nordicUartWrite }
                val notifyChar = nordicService?.characteristics?.firstOrNull { it.uuid == nordicUartNotify }
                if (writeChar != null) {
                    Timber.d("$TAG: Found Nordic UART characteristics (VS1800S) for ${peripheral.name}")
                    return Pair(writeChar, notifyChar)
                }

                // 2. Secondary: Legacy Service 0xFF12 (VS1200S / VS800 / VS500)
                val legService = services.firstOrNull { it.uuid == legacyService }
                val legChar = legService?.characteristics?.firstOrNull { it.uuid == legacyWrite }
                if (legChar != null) {
                    Timber.d("$TAG: Found Legacy 0xFF12 characteristic (VS1200S) for ${peripheral.name}")
                    return Pair(legChar, legChar)
                }

                // 3. Fallback
                for (service in services) {
                    val fallbackChar = service.characteristics.firstOrNull()
                    if (fallbackChar != null) {
                        return Pair(fallbackChar, fallbackChar)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Error discovering GATT characteristics for ${peripheral.name}")
            }
            delay(360)
        }
        return null
    }

    private fun updateDiscoveredLights() {
        val newList = devices.map { (address, device) ->
            DiscoveredLight(
                id = address,
                name = device.name,
                manufacturer = "iGPSPORT",
                protocol = LightProtocol.IGPSPORT_BLE,
                connected = characteristics.containsKey(address),
                batteryPercent = batteryLevels[address],
                rawBatteryHex = batteryLevelsRaw[address],
                currentMode = _actualModes.value[address],
            )
        }.toList()
        _discoveredLights.value = newList
    }

    private fun parseNotification(address: String, data: ByteArray) {
        if (data.isEmpty()) return
        val rawHex = IgpsportProtocol.bytesToHex(data)
        Timber.d("$TAG: 📩 GATT Notification from iGPSPORT $address (${data.size} bytes): $rawHex")

        val assembler = frameAssemblers.getOrPut(address) { FrameAssembler() }
        val frames = assembler.push(data)

        val framesToProcess = if (frames.isNotEmpty()) {
            frames
        } else if (data.size >= IgpsProtocol.HEADER_LEN && IgpsProtocol.headerValid(data)) {
            listOf(data)
        } else {
            emptyList()
        }

        if (framesToProcess.isEmpty()) {
            Timber.d("$TAG: Notification fragment buffered or invalid CRC: $rawHex")
        }

        for (frame in framesToProcess) {
            val update = IgpsProtocol.parseFrame(frame)
            Timber.d("$TAG: 🔍 Parsed frame for $address -> $update (raw: ${IgpsportProtocol.bytesToHex(frame)})")
            if (update != null) {
                val bat = update.batteryPercent ?: update.auxBatteryPercent
                if (bat != null && bat in 1..100) {
                    Timber.d("$TAG: 🔋 SUCCESS! Parsed battery level for $address: $bat%")
                    batteryLevels[address] = bat
                    batteryLevelsRaw[address] = "${IgpsportProtocol.bytesToHex(frame)} -> $bat%"
                }
                if (update.mode != null) {
                    val modeStr = when (update.mode) {
                        0 -> "OFF"
                        64 -> "CUSTOM_1"
                        65 -> "CUSTOM_2"
                        66 -> "CUSTOM_3"
                        67 -> "CUSTOM_4"
                        68 -> "CUSTOM_5"
                        69 -> "CUSTOM_6"
                        else -> "MODE_${update.mode}"
                    }
                    _actualModes.update { it + (address to modeStr) }
                }
            }
        }

        if (batteryLevelsRaw[address] == null) {
            batteryLevelsRaw[address] = rawHex
        }

        updateDiscoveredLights()
    }

    override fun setMode(deviceId: String, modeName: String) {
        val config = getDeviceConfig(deviceId)
        val frame = config.buildCommand(modeName)
        if (frame == null) {
            Timber.w("$TAG: Unknown mode: $modeName for iGPSPORT device $deviceId")
            return
        }

        val autoDimForMode = config.isAutoDimmingForMode(modeName)
        val dimFrame = IgpsportProtocol.buildAutoDimmingCommand(autoDimForMode)
        val combinedFrame = frame + dimFrame

        _actualModes.update { it + (deviceId to modeName) }
        Timber.d("$TAG: setMode($deviceId, $modeName, autoDim=$autoDimForMode)")
        scope.launch { sendFrame(deviceId, combinedFrame) }
        updateDiscoveredLights()
    }

    fun setAutoDimmingForSlot(address: String, slotNumber: Int, enabled: Boolean) {
        val config = getDeviceConfig(address)
        val updatedConfig = when (slotNumber) {
            1 -> config.copy(custom1AutoDimming = enabled)
            2 -> config.copy(custom2AutoDimming = enabled)
            else -> config.copy(custom3AutoDimming = enabled)
        }
        deviceConfigs[address] = updatedConfig
        val command = IgpsportProtocol.buildAutoDimmingCommand(enabled)
        scope.launch { sendFrame(address, command) }
    }

    fun setLowBatterySave(address: String, enabled: Boolean) {
        val config = getDeviceConfig(address)
        deviceConfigs[address] = config.copy(lowBatterySaveEnabled = enabled)
        val command = IgpsportProtocol.buildLowBatterySaveCommand(enabled)
        scope.launch { sendFrame(address, command) }
    }

    fun setCustomModeConfig(
        address: String,
        slotNumber: Int,
        patternType: Int,
        highBeamPct: Int,
        lowBeamPct: Int,
    ) {
        val command = IgpsportProtocol.buildCustomModeConfigCommandDualBeam(
            slotNumber, patternType, highBeamPct, lowBeamPct
        )
        scope.launch { sendFrame(address, command) }
    }

    fun setCustomModeConfigSingleBeam(
        address: String,
        slotNumber: Int,
        patternType: Int,
        mainPct: Int,
    ) {
        val command = IgpsportProtocol.buildCustomModeConfigCommandSingleBeam(
            slotNumber, patternType, mainPct
        )
        scope.launch { sendFrame(address, command) }
    }

    private suspend fun sendFrame(address: String, frame: ByteArray) {
        val chunks = IgpsProtocol.chunks(frame)
        Timber.d("$TAG: Sending frame to $address in ${chunks.size} chunks (${frame.size} bytes): ${IgpsportProtocol.bytesToHex(frame)}")
        for (chunk in chunks) {
            writeBytes(address, chunk)
            delay(75)
        }
    }

    private suspend fun writeBytes(address: String, bytes: ByteArray) {
        val characteristic = characteristics[address]
        if (characteristic == null) {
            Timber.w("$TAG: Not connected to iGPSPORT light $address, cannot send command")
            return
        }

        writeMutex.withLock {
            for (attempt in 1..WRITE_RETRIES) {
                try {
                    characteristic.write(bytes, WriteType.WITHOUT_RESPONSE)
                    return
                } catch (e: Exception) {
                    if (attempt == WRITE_RETRIES) {
                        Timber.e(e, "$TAG: Write failed after $WRITE_RETRIES attempts to $address")
                    } else {
                        delay(WRITE_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    fun destroy() {
        scope.launch {
            characteristics.keys.toList().forEach { disconnect(it) }
            scope.cancel()
        }
    }
}
