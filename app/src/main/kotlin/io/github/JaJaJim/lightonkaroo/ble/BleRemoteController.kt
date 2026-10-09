package io.github.JaJaJim.lightonkaroo.ble

import android.content.Context
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
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.kotlin.ble.client.android.CentralManager
import no.nordicsemi.kotlin.ble.client.android.Peripheral
import no.nordicsemi.kotlin.ble.client.android.native
import no.nordicsemi.kotlin.ble.client.RemoteCharacteristic
import no.nordicsemi.kotlin.ble.core.ConnectionState
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class DiscoveredRemote(
    val address: String,
    val name: String,
    val connected: Boolean = false,
)

@OptIn(ExperimentalUuidApi::class)
class BleRemoteController(context: Context) {

    companion object {
        private const val TAG = "BleRemote"
        private const val RECONNECT_MIN_MS = 10_000L
        private const val RECONNECT_MAX_MS = 60_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val centralManager by lazy { CentralManager.native(appContext, scope) }

    private val targetService = Uuid.parse(BleRemoteProtocol.HID_SERVICE_UUID)
    private val targetChar = Uuid.parse(BleRemoteProtocol.REPORT_CHAR_UUID)

    private val discoveredRemotesMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _discoveredRemotes = MutableStateFlow<List<DiscoveredRemote>>(emptyList())
    val discoveredRemotes: StateFlow<List<DiscoveredRemote>> = _discoveredRemotes

    var boundAddress: String = ""
    var onButtonNotification: ((bytesHex: String) -> Unit)? = null
    var isSettingsUiActive: Boolean = false

    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private var connectionJob: Job? = null
    private var isConnected: Boolean = false

    private val bleScanner: android.bluetooth.le.BluetoothLeScanner?
        get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
            ?.adapter?.bluetoothLeScanner

    fun registerFoundDevice(address: String, name: String) {
        discoveredRemotesMap[address] = name
        updateDiscoveredList()
    }

    fun startDiscovery() {
        if (scanCallback != null) return
        scope.launch {
            for (attempt in 1..5) {
                if (scanCallback != null) break
                val scanner = bleScanner
                if (scanner != null) {
                    Timber.d("$TAG: Starting BLE Remote discovery (attempt $attempt)")
                    executeBleScan(scanner)
                    break
                }
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
                    val rawName = result.scanRecord?.deviceName ?: try { result.device?.name } catch (_: SecurityException) { null } ?: "BLE Remote"
                    val nameUpper = rawName.uppercase()

                    val isRemote = BleRemoteProtocol.SUPPORTED_PREFIXES.any { nameUpper.contains(it) } ||
                                   address == boundAddress

                    if (isRemote) {
                        registerFoundDevice(address, rawName)
                        Timber.d("$TAG: Found BLE Remote: $rawName ($address)")
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Skipping remote scan result")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.e("$TAG: BLE Remote scan failed: $errorCode")
            }
        }
        try {
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start BLE Remote scan")
        }
    }

    fun stopDiscovery() {
        scanCallback?.let { cb ->
            try {
                bleScanner?.stopScan(cb)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to stop BLE Remote scan")
            }
        }
        scanCallback = null
    }

    fun startSupervisor() {
        if (boundAddress.isEmpty()) return
        if (connectionJob?.isActive == true) return

        connectionJob = scope.launch {
            var backoff = RECONNECT_MIN_MS
            while (isActive && boundAddress.isNotEmpty()) {
                val wasConnected = attemptConnect(boundAddress)
                backoff = if (wasConnected) RECONNECT_MIN_MS else minOf(backoff * 2, RECONNECT_MAX_MS)
                delay(backoff)
            }
            connectionJob = null
        }
    }

    private suspend fun attemptConnect(address: String): Boolean {
        val peripheral = centralManager.getPeripheralsById(listOf(address)).firstOrNull() ?: return false
        return try {
            Timber.d("$TAG: Connecting to BLE Remote at $address")
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

            if (!connected) return false

            val char = findReportCharacteristic(peripheral) ?: return false
            isConnected = true
            updateDiscoveredList()
            Timber.d("$TAG: Subscribing to GATT Report Notifications on $address")

            scope.launch {
                try {
                    char.subscribe().collect { bytes ->
                        val hex = BleRemoteProtocol.bytesToHex(bytes)
                        Timber.d("$TAG: Remote GATT notification from $address: $hex")
                        onButtonNotification?.invoke(hex)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: Notification stream ended for $address")
                }
            }

            peripheral.state.first { it is ConnectionState.Disconnected }
            isConnected = false
            updateDiscoveredList()
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Connection attempt failed for remote $address")
            isConnected = false
            updateDiscoveredList()
            false
        }
    }

    private suspend fun findReportCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        for (attempt in 1..10) {
            try {
                val services = peripheral.services().value
                val service = services.firstOrNull { it.uuid == targetService } ?: services.firstOrNull()
                val char = service?.characteristics?.firstOrNull { it.uuid == targetChar } ?: service?.characteristics?.firstOrNull()
                if (char != null) return char
            } catch (_: Exception) { }
            delay(300)
        }
        return null
    }

    private fun updateDiscoveredList() {
        _discoveredRemotes.value = discoveredRemotesMap.map { (address, name) ->
            DiscoveredRemote(
                address = address,
                name = name,
                connected = isConnected && address == boundAddress,
            )
        }
    }

    fun destroy() {
        scope.launch {
            connectionJob?.cancel()
            stopDiscovery()
            scope.cancel()
        }
    }
}
