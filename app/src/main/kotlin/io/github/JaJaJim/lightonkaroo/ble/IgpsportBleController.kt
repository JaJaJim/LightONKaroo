package io.github.JaJaJim.lightonkaroo.ble

import android.content.Context
import io.github.JaJaJim.lightonkaroo.DiscoveredLight
import io.github.JaJaJim.lightonkaroo.data.LightProtocol
import io.github.JaJaJim.lightonkaroo.light.LightController
import io.github.JaJaJim.lightonkaroo.protocol.IgpsProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

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
            Timber.d("$TAG: Querying raw battery for $address...")
            writeBytes(address, IgpsProtocol.readBattery())
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
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
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

    private fun startConnectionSupervisor(address: String) {
        if (connectionJobs[address]?.isActive == true) return
        connectionJobs[address] = scope.launch {
            var backoff = RECONNECT_MIN_MS
            while (isActive && (address in assignedDeviceIds || isSettingsUiActive)) {
                val wasConnected = attemptConnect(address)
                backoff = if (wasConnected) RECONNECT_MIN_MS else minOf(backoff * 2, RECONNECT_MAX_MS)
                delay(backoff)
            }
            connectionJobs.remove(address)
        }
    }

    fun disconnect(address: String) {
        connectionJobs.remove(address)?.cancel()
        characteristics.remove(address)
        batteryLevelsRaw.remove(address)
        updateDiscoveredLights()
    }

    fun allConnected(deviceIds: Set<String>): Boolean {
        if (deviceIds.isEmpty()) return true
        return deviceIds.all { characteristics.containsKey(it) }
    }

    private suspend fun attemptConnect(address: String): Boolean {
        val peripheral = devices[address]?.peripheral
            ?: centralManager.getPeripheralsById(listOf(address)).firstOrNull()
            ?: return false
        val name = devices[address]?.name ?: peripheral.name ?: "iGPSPORT Light"
        devices.putIfAbsent(address, BleDevice(peripheral, name))
        deviceConfigs.putIfAbsent(address, IgpsportDeviceConfig())

        return try {
            Timber.d("$TAG: Connecting to iGPSPORT light at $address ($name)")
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
            Timber.d("$TAG: Connected to iGPSPORT light $address ($name), write & notify characteristics ready")

            val notifyJob = scope.launch {
                try {
                    val listenChar = notifyChar ?: writeChar
                    Timber.d("$TAG: Subscribing to GATT notifications on characteristic ${listenChar.uuid}...")
                    listenChar.subscribe().collect { data -> parseNotification(address, data) }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Failed to subscribe to GATT notifications on $address")
                }
            }

            // Give 2.0s buffer after GATT connection & service discovery before querying battery
            delay(2000)
            Timber.d("$TAG: Sending immediate readBattery query to iGPSPORT $address...")
            writeBytes(address, IgpsProtocol.readBattery())

            updateDiscoveredLights()
            onDeviceConnected?.invoke()

            peripheral.state.first { it is ConnectionState.Disconnected }
            notifyJob.cancel()
            Timber.d("$TAG: Disconnected from iGPSPORT light $address")
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Connect attempt failed for iGPSPORT light $address")
            false
        } finally {
            characteristics.remove(address)
            batteryLevelsRaw.remove(address)
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
                batteryPercent = null, // Disconnected from data field
                rawBatteryHex = batteryLevelsRaw[address],
            )
        }.toList()
        _discoveredLights.value = newList
    }

    private fun parseNotification(address: String, data: ByteArray) {
        if (data.isEmpty()) return
        val hex = IgpsportProtocol.bytesToHex(data)
        Timber.d("$TAG: 📩 Raw Notification from iGPSPORT $address: $hex")

        batteryLevelsRaw[address] = hex
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

        val chunks = IgpsProtocol.chunks(combinedFrame)
        Timber.d("$TAG: setMode($deviceId, $modeName, autoDim=$autoDimForMode) -> ${chunks.size} chunks: ${IgpsportProtocol.bytesToHex(combinedFrame)}")
        scope.launch {
            for (chunk in chunks) {
                writeBytes(deviceId, chunk)
                delay(20)
            }
        }
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
        scope.launch {
            val chunks = IgpsProtocol.chunks(command)
            for (chunk in chunks) {
                writeBytes(address, chunk)
                delay(20)
            }
        }
    }

    fun setLowBatterySave(address: String, enabled: Boolean) {
        val config = getDeviceConfig(address)
        deviceConfigs[address] = config.copy(lowBatterySaveEnabled = enabled)
        val command = IgpsportProtocol.buildLowBatterySaveCommand(enabled)
        scope.launch {
            val chunks = IgpsProtocol.chunks(command)
            for (chunk in chunks) {
                writeBytes(address, chunk)
                delay(20)
            }
        }
    }

    fun setCustomModeConfig(
        address: String,
        slotNumber: Int,
        patternType: Int,
        highBeamPct: Int,
        lowBeamPct: Int,
    ) {
        val command = IgpsportProtocol.buildCustomModeConfigCommand(
            slotNumber, patternType, highBeamPct, lowBeamPct
        )
        scope.launch {
            val chunks = IgpsProtocol.chunks(command)
            for (chunk in chunks) {
                writeBytes(address, chunk)
                delay(20)
            }
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
