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
class MagicshineBleController(context: Context) : LightController {

    companion object {
        private const val TAG = "MagicshineBle"
        private const val WRITE_RETRIES = 5
        private const val WRITE_RETRY_DELAY_MS = 60L
        private const val RECONNECT_MIN_MS = 10_000L
        private const val RECONNECT_MAX_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val centralManager by lazy { CentralManager.native(appContext, scope) }
    private val writeMutex = Mutex()

    private val targetService = Uuid.parse(MagicshineProtocol.SERVICE_UUID)
    private val targetChar = Uuid.parse(MagicshineProtocol.CHARACTERISTIC_UUID)

    private data class BleDevice(val peripheral: Peripheral, val name: String)

    private val devices = ConcurrentHashMap<String, BleDevice>()
    private val characteristics = ConcurrentHashMap<String, RemoteCharacteristic>()
    private val deviceConfigs = ConcurrentHashMap<String, MagicshineDeviceConfig>()
    private val batteryLevels = ConcurrentHashMap<String, Int>()
    private val temperatures = ConcurrentHashMap<String, Int>()

    private val _discoveredLights = MutableStateFlow<List<DiscoveredLight>>(emptyList())
    val discoveredLights: StateFlow<List<DiscoveredLight>> = _discoveredLights

    var onDeviceConnected: (() -> Unit)? = null
    var assignedDeviceIds: Set<String> = emptySet()
    var isSettingsUiActive: Boolean = false

    fun getDeviceConfig(address: String): MagicshineDeviceConfig {
        val name = devices[address]?.name ?: "Magicshine"
        val config = MagicshineDeviceConfig.forDevice(name)
        deviceConfigs[address] = config
        return config
    }

    private var scanCallback: ScanCallback? = null
    private val connectionJobs = ConcurrentHashMap<String, Job>()

    private val bleScanner: BluetoothLeScanner?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    fun startDiscovery() {
        if (scanCallback != null) return
        scope.launch {
            for (attempt in 1..5) {
                if (scanCallback != null) break
                val scanner = bleScanner
                if (scanner != null) {
                    Timber.d("$TAG: Starting BLE discovery (attempt $attempt)")
                    executeBleScan(scanner)
                    break
                }
                Timber.w("$TAG: BLE scanner null, retrying in 500ms (attempt $attempt)...")
                delay(500L)
            }
        }
    }

    private fun executeBleScan(scanner: BluetoothLeScanner) {
        if (scanCallback != null) return
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                try {
                    val address = result.device?.address ?: return
                    if (devices.containsKey(address)) return

                    val rawName = result.scanRecord?.deviceName ?: result.device?.name ?: return
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
                                       nameUpper.contains("MONTEER") ||
                                       address in assignedDeviceIds

                    if (isMagicshine) {
                        Timber.d("$TAG: Found Magicshine light: $rawName ($address)")
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
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start BLE scan")
        }
    }

    internal fun registerFoundDevice(address: String, name: String) {
        if (devices.containsKey(address)) return
        val peripheral = centralManager.getPeripheralsById(listOf(address)).firstOrNull() ?: run {
            Timber.w("$TAG: No peripheral for $address")
            return
        }
        devices[address] = BleDevice(peripheral, name)
        deviceConfigs[address] = MagicshineDeviceConfig.forDevice(name)
        Timber.d("$TAG: Device config for $name: module=${deviceConfigs[address]?.moduleType}")
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
                Timber.w(e, "$TAG: Failed to stop BLE scan")
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
        val name = devices[address]?.name ?: peripheral.name ?: "Magicshine"
        devices.putIfAbsent(address, BleDevice(peripheral, name))
        deviceConfigs.putIfAbsent(address, MagicshineDeviceConfig.forDevice(name))

        return try {
            Timber.d("$TAG: Connecting to $address")
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
                Timber.w("$TAG: Connection timeout for $address")
                return false
            }

            val characteristic = findTargetCharacteristic(peripheral)
            if (characteristic == null) {
                Timber.w("$TAG: Characteristic not found for $address")
                return false
            }
            characteristics[address] = characteristic
            Timber.d("$TAG: Connected to $address, characteristic found")

            scope.launch {
                try {
                    characteristic.subscribe().collect { data -> parseNotification(address, data) }
                } catch (_: Exception) { }
            }

            delay(180)
            writeBytes(address, MagicshineProtocol.buildQuery(0xA4.toByte()))
            delay(100)
            writeBytes(address, MagicshineProtocol.buildQuery(0xA1.toByte()))

            updateDiscoveredLights()
            onDeviceConnected?.invoke()

            scope.launch {
                while (characteristics.containsKey(address)) {
                    delay(60_000)
                    if (!characteristics.containsKey(address)) break
                    writeBytes(address, MagicshineProtocol.buildQuery(0xA4.toByte()))
                    delay(100)
                    writeBytes(address, MagicshineProtocol.buildQuery(0xA1.toByte()))
                }
            }

            peripheral.state.first { it is ConnectionState.Disconnected }
            Timber.d("$TAG: Disconnected from $address")
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Connect attempt failed for $address")
            false
        } finally {
            characteristics.remove(address)
            updateDiscoveredLights()
        }
    }

    private suspend fun findTargetCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        for (attempt in 1..10) {
            try {
                val services = peripheral.services().value
                val service = services.firstOrNull { it.uuid == targetService }
                val characteristic = service?.characteristics?.firstOrNull { it.uuid == targetChar }
                if (characteristic != null) return characteristic
            } catch (_: Exception) { }
            delay(360)
        }
        return null
    }

    private fun updateDiscoveredLights() {
        _discoveredLights.value = devices.map { (address, device) ->
            DiscoveredLight(
                id = address,
                name = device.name,
                manufacturer = "Magicshine",
                protocol = LightProtocol.BLE,
                connected = characteristics.containsKey(address),
                batteryPercent = batteryLevels[address],
                temperature = temperatures[address],
            )
        }
    }

    private fun parseNotification(address: String, data: ByteArray) {
        if (data.size < 6) return
        Timber.d("$TAG: Notification from $address: ${MagicshineProtocol.bytesToHex(data)}")
        val type = data[2].toInt() and 0xFF
        val content = if (data.size > 6) data.sliceArray(4 until data.size - 2) else byteArrayOf()

        when (type) {
            0xB4 -> {
                if (content.size >= 5) {
                    val battery = content[4].toInt() and 0xFF
                    if (battery in 0..100) {
                        batteryLevels[address] = battery
                        updateDiscoveredLights()
                    }
                }
            }
            0xB1 -> {
                if (content.size >= 3) {
                    val temp = content[2].toInt()
                    temperatures[address] = temp
                    updateDiscoveredLights()
                }
            }
        }
    }

    override fun setMode(deviceId: String, modeName: String) {
        val config = deviceConfigs.getOrPut(deviceId) { MagicshineDeviceConfig.forDevice("Magicshine") }
        val command = config.buildCommand(modeName)
        if (command == null) {
            Timber.w("$TAG: Unknown mode: $modeName for device $deviceId")
            return
        }
        Timber.d("$TAG: setMode($deviceId, $modeName) -> ${MagicshineProtocol.bytesToHex(command)}")
        scope.launch {
            writeBytes(deviceId, command)
        }
    }

    private suspend fun writeBytes(address: String, bytes: ByteArray) {
        val characteristic = characteristics[address]
        if (characteristic == null) {
            Timber.w("$TAG: Not connected to $address, cannot send command")
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
