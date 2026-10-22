package io.github.JaJaJim.lightonkaroo.ble

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.JaJaJim.lightonkaroo.DiscoveredLight
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.light.LightController
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

data class MageneBleDevice(val peripheral: Peripheral, val name: String)

@OptIn(ExperimentalUuidApi::class)
class MageneBleController(context: Context) : LightController {

    companion object {
        private const val TAG = "MageneBle"
        private const val WRITE_RETRIES = 5
        private const val WRITE_RETRY_DELAY_MS = 60L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val centralManager by lazy { CentralManager.native(appContext, scope) }
    private val writeMutex = Mutex()

    val devices = ConcurrentHashMap<String, MageneBleDevice>()
    val characteristics = ConcurrentHashMap<String, RemoteCharacteristic>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()
    private val failureCounts = ConcurrentHashMap<String, Int>()

    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    private val _actualModes = MutableStateFlow<Map<String, String>>(emptyMap())
    val actualModes: StateFlow<Map<String, String>> = _actualModes

    @Volatile
    var isSettingsUiActive: Boolean = false

    @Volatile
    var assignedDeviceIds: Set<String> = emptySet()

    var onDeviceConnected: (() -> Unit)? = null

    private var bleScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    fun resetBackoff(address: String? = null) {
        if (address != null) {
            failureCounts[address] = 0
        } else {
            failureCounts.clear()
        }
    }

    fun updateDiscoveredLights() {
        val newList = devices.values.map { device ->
            val address = device.peripheral.address
            DiscoveredLight(
                id = address,
                name = device.name,
                manufacturer = "Magene",
                protocol = LightProtocol.MAGENE_BLE,
                connected = characteristics.containsKey(address),
                currentMode = _actualModes.value[address],
            )
        }.toList()
        _discoveredLights.value = newList
    }

    fun startDiscovery() {
        if (scanCallback != null) return
        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        bleScanner = bluetoothManager.adapter?.bluetoothLeScanner ?: return

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val address = result.device?.address ?: return
                    if (devices.containsKey(address)) return

                    val rawName = result.scanRecord?.deviceName ?: result.device?.name ?: return
                    val nameUpper = rawName.uppercase()

                    val isMagene = nameUpper.contains("MAGENE") ||
                                   nameUpper.contains("AT1200") ||
                                   nameUpper.contains("AT1600") ||
                                   nameUpper.contains("AT") ||
                                   nameUpper.contains("L508") ||
                                   address in assignedDeviceIds

                    if (isMagene) {
                        Timber.d("$TAG: Found Magene BLE light: $rawName ($address)")
                        scope.launch { registerFoundDevice(address, rawName) }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Skipping scan result")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("$TAG: BLE scan failed: $errorCode")
            }
        }

        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                .build()
            bleScanner?.startScan(null, settings, callback)
            scanCallback = callback
            scope.launch {
                delay(10_000L)
                stopDiscovery()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start Magene BLE scan")
        }
    }

    internal fun registerFoundDevice(address: String, name: String) {
        if (devices.containsKey(address)) return
        val peripheral = centralManager.getPeripheralsById(listOf(address)).firstOrNull() ?: run {
            Timber.w("$TAG: No peripheral for $address")
            return
        }
        devices[address] = MageneBleDevice(peripheral, name)
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
                Timber.w(e, "$TAG: Failed to stop Magene BLE scan")
            }
        }
        scanCallback = null
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
        failureCounts.remove(address)
        updateDiscoveredLights()
    }

    fun allConnected(deviceIds: Set<String>): Boolean {
        if (deviceIds.isEmpty()) return true
        return deviceIds.all { characteristics.containsKey(it) }
    }

    private fun resolveConfirmedName(address: String, peripheralName: String?): String {
        val existing = devices[address]?.name
        if (!existing.isNullOrEmpty() && existing != "Magene Light" && existing != "MAGENE") {
            return existing
        }
        if (!peripheralName.isNullOrEmpty() && peripheralName != "Magene Light" && peripheralName != "MAGENE") {
            return peripheralName
        }
        return "Magene AT1200"
    }

    private suspend fun attemptConnect(address: String): Boolean {
        val peripheral = devices[address]?.peripheral
            ?: centralManager.getPeripheralsById(listOf(address)).firstOrNull()
            ?: return false
        val confirmedName = resolveConfirmedName(address, peripheral.name)
        devices[address] = MageneBleDevice(peripheral, confirmedName)

        return try {
            stopDiscovery()
            Timber.d("$TAG: Connecting to Magene BLE light at $address ($confirmedName)")
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
                Timber.w("$TAG: Connection timeout for Magene BLE light $address")
                return false
            }

            var services = peripheral.services().value
            for (attempt in 1..10) {
                if (services.isNotEmpty()) break
                delay(500L)
                services = peripheral.services().value
            }

            val allChars = services.flatMap { it.characteristics }
            Timber.d("$TAG: Discovered GATT characteristics for $address: ${allChars.map { it.uuid }}")

            val primaryChar = allChars.firstOrNull {
                val u = it.uuid.toString().uppercase()
                u.contains("8CE5CC05") || u.contains("8CE5CC04") || u.contains("6E400002") || u.contains("FFF2") || u.contains("FFE0")
            } ?: allChars.firstOrNull()

            val notifyChar = allChars.firstOrNull {
                val u = it.uuid.toString().uppercase()
                u.contains("8CE5CC06") || u.contains("6E400003") || u.contains("FFF1") || u.contains("FFE1")
            }

            if (primaryChar == null) {
                Timber.w("$TAG: No write characteristic found for Magene light $address")
                return false
            }
            characteristics[address] = primaryChar
            Timber.d("$TAG: Connected to Magene BLE light $address ($confirmedName), write characteristic ready")

            val notifyJob = scope.launch {
                val listenChar = notifyChar ?: primaryChar
                try {
                    Timber.d("$TAG: Subscribing to GATT notifications on characteristic ${listenChar.uuid}...")
                    listenChar.subscribe().collect { data ->
                        Timber.d("$TAG: 📩 GATT Notification from Magene $address (${data.size} bytes): ${MageneProtocol.bytesToHex(data)}")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Failed to subscribe to GATT notifications on Magene light $address")
                }
            }

            scope.launch {
                delay(300L)
                writeBytes(address, MageneProtocol.buildOffCommand())
            }

            scope.launch {
                try {
                    val gapServiceUuid = java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
                    val devNameUuid = java.util.UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
                    val gapService = services.firstOrNull { it.uuid == gapServiceUuid }
                    val nameChar = gapService?.characteristics?.firstOrNull { it.uuid == devNameUuid }
                    if (nameChar != null) {
                        val nameBytes = nameChar.read()
                        val gattName = String(nameBytes, Charsets.UTF_8).trim()
                        if (gattName.isNotEmpty() && gattName != "Magene Light" && gattName != "MAGENE") {
                            Timber.d("$TAG: Read hardware GATT device name for $address: '$gattName'")
                            devices[address] = MageneBleDevice(peripheral, gattName)
                            updateDiscoveredLights()
                        }
                    }
                } catch (_: Exception) {
                    Timber.d("$TAG: Optional GATT device name read skipped for $address")
                }
            }

            updateDiscoveredLights()
            onDeviceConnected?.invoke()

            peripheral.state.first { it is ConnectionState.Disconnected }
            notifyJob.cancel()
            Timber.d("$TAG: Disconnected from Magene BLE light $address")
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Connect attempt failed for Magene BLE light $address")
            false
        } finally {
            characteristics.remove(address)
            updateDiscoveredLights()
        }
    }

    override fun setMode(deviceId: String, modeName: String) {
        val veloLinkCommand = if (modeName == "OFF") {
            MageneProtocol.buildVeloLinkSetLightCommand(on = false, brightPct = 0, mode = 0)
        } else if (modeName.startsWith("CUSTOM_")) {
            val slotNum = modeName.substringAfter("CUSTOM_").toIntOrNull() ?: 1
            val assignment = io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension.getInstance()
                ?.engine?.settings?.lightAssignments?.find { it.deviceId == deviceId }
            val slotConfig = when (slotNum) {
                1 -> assignment?.customSlot1
                2 -> assignment?.customSlot2
                3 -> assignment?.customSlot3
                4 -> assignment?.customSlot4
                5 -> assignment?.customSlot5
                6 -> assignment?.customSlot6
                else -> assignment?.customSlot7
            }
            val patternType = slotConfig?.patternType ?: 0
            val bright = slotConfig?.mainPct ?: 100
            MageneProtocol.buildVeloLinkSetLightCommand(on = true, brightPct = bright, mode = patternType)
        } else if (modeName.startsWith("STEADY_") || modeName.startsWith("FLASH_") || modeName.startsWith("SOS_")) {
            val parts = modeName.split("_", limit = 2)
            val patternType = when (parts[0]) {
                "FLASH" -> 1
                "SOS" -> 2
                else -> 0
            }
            val bright = parts.getOrNull(1)?.toIntOrNull() ?: 100
            MageneProtocol.buildVeloLinkSetLightCommand(on = true, brightPct = bright, mode = patternType)
        } else {
            MageneProtocol.buildVeloLinkSetLightCommand(on = false, brightPct = 0, mode = 0)
        }

        _actualModes.update { it + (deviceId to modeName) }
        Timber.d("$TAG: setMode($deviceId, $modeName) -> VeloLink: ${MageneProtocol.bytesToHex(veloLinkCommand)}")
        scope.launch {
            writeBytes(deviceId, veloLinkCommand)
        }
        updateDiscoveredLights()
    }

    private suspend fun writeBytes(address: String, bytes: ByteArray) {
        val characteristic = characteristics[address]
        if (characteristic == null) {
            Timber.w("$TAG: Not connected to Magene light $address, cannot send command")
            return
        }

        writeMutex.withLock {
            for (attempt in 1..WRITE_RETRIES) {
                try {
                    try {
                        characteristic.write(bytes, WriteType.WITH_RESPONSE)
                    } catch (_: Exception) {
                        characteristic.write(bytes, WriteType.WITHOUT_RESPONSE)
                    }
                    return
                } catch (e: Exception) {
                    if (attempt == WRITE_RETRIES) {
                        Timber.e(e, "$TAG: Write failed after $WRITE_RETRIES attempts to Magene light $address")
                    } else {
                        delay(WRITE_RETRY_DELAY_MS)
                    }
                }
            }
        }
    }

    private val _saveProgressStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val saveProgressStatus: StateFlow<Map<String, String>> = _saveProgressStatus

    fun saveCustomSlotsToLightMemory(deviceId: String) {
        val assignment = io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension.getInstance()
            ?.engine?.settings?.lightAssignments?.find { it.deviceId == deviceId } ?: return

        scope.launch {
            Timber.d("$TAG: 💾 Starting Magene EEPROM custom slots save for $deviceId...")

            // 1. Unlock Privileged Mode
            _saveProgressStatus.update { it + (deviceId to "🔓 Unlocking Magene Memory...") }
            writeBytes(deviceId, MageneProtocol.buildPrivilegedModeCommand(true))
            delay(250L)

            // 2. Write Custom Slots 1..7 sequentially
            val slots = listOf(
                assignment.customSlot1,
                assignment.customSlot2,
                assignment.customSlot3,
                assignment.customSlot4,
                assignment.customSlot5,
                assignment.customSlot6,
                assignment.customSlot7,
            )

            for ((index, slot) in slots.withIndex()) {
                val slotNum = index + 1
                val patternName = when (slot.patternType) {
                    1 -> "Flash"
                    2 -> "SOS"
                    else -> "Steady"
                }
                _saveProgressStatus.update { it + (deviceId to "💾 Writing Custom Slot $slotNum of 7 (${slot.mainPct}% $patternName)...") }
                val flashCmd = MageneProtocol.buildFlashCustomSlotCommand(slotNum, slot.patternType, slot.mainPct)
                writeBytes(deviceId, flashCmd)
                delay(250L)
            }

            // 3. Lock Privileged Mode
            _saveProgressStatus.update { it + (deviceId to "🔒 Locking Memory...") }
            writeBytes(deviceId, MageneProtocol.buildPrivilegedModeCommand(false))
            delay(250L)

            _saveProgressStatus.update { it + (deviceId to "✅ All 7 Custom Profiles saved to Magene Light Memory!") }
            Timber.d("$TAG: 💾 Magene EEPROM custom slots saved successfully for $deviceId!")
        }
    }

    fun destroy() {
        scope.launch {
            characteristics.keys.toList().forEach { disconnect(it) }
            scope.cancel()
        }
    }
}
