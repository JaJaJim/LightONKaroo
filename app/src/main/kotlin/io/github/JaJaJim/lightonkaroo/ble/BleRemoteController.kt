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
import no.nordicsemi.kotlin.ble.core.ConnectionState
import timber.log.Timber
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

data class DiscoveredRemote(
    val address: String,
    val name: String,
    val connected: Boolean = false,
)

@OptIn(ExperimentalUuidApi::class)
class BleRemoteController(context: Context) {

    companion object {
        private const val TAG = "BleRemote"
        private const val DEBOUNCE_MS = 300L
        private const val RECONNECT_MIN_MS = 5_000L
        private const val RECONNECT_MAX_MS = 30_000L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val centralManager by lazy { CentralManager.native(appContext, scope) }

    private val discoveredRemotesMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _discoveredRemotes = MutableStateFlow<List<DiscoveredRemote>>(emptyList())
    val discoveredRemotes: StateFlow<List<DiscoveredRemote>> = _discoveredRemotes

    var boundAddress: String = ""
    var onAdvertisingPacketReceived: ((address: String, name: String, hexPattern: String) -> Unit)? = null
    var onRemoteButtonPressed: (() -> Unit)? = null
    var isSettingsUiActive: Boolean = false

    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private var lastTriggerTimestamp = 0L
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
                    Timber.d("$TAG: Starting BLE Remote Scanner (attempt $attempt)")
                    executeBleScan(scanner)
                    break
                }
                delay(500L)
            }
        }
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

            isConnected = true
            updateDiscoveredList()
            Timber.d("$TAG: Connected via GATT to BLE Remote at $address! Subscribing 360° net...")

            subscribeToAllNotifyCharacteristics(peripheral, address)

            peripheral.state.first { it is ConnectionState.Disconnected }
            isConnected = false
            updateDiscoveredList()
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: GATT connection attempt failed for remote $address")
            isConnected = false
            updateDiscoveredList()
            false
        }
    }

    private fun subscribeToAllNotifyCharacteristics(peripheral: Peripheral, address: String) {
        scope.launch {
            try {
                delay(500)
                val services = peripheral.services().value
                Timber.d("$TAG: [360° GATT Net] Services found on remote: ${services.size}")

                for (service in services) {
                    for (char in service.characteristics) {
                        scope.launch {
                            try {
                                char.subscribe().collect { bytes ->
                                    val hex = BleRemoteProtocol.bytesToHex(bytes)
                                    if (hex.isNotEmpty() && !hex.all { it == '0' }) {
                                        Timber.d("$TAG: 🎯 [360° GATT Net] Received notification on char ${char.uuid}: $hex")
                                        handleAdvertisingPacket(address, peripheral.name ?: "Remote", bytes)
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to discover GATT characteristics")
            }
        }
    }

    fun handleAdvertisingPacket(address: String, name: String, rawBytes: ByteArray?) {
        val bytesHex = if (rawBytes != null && rawBytes.isNotEmpty()) BleRemoteProtocol.bytesToHex(rawBytes) else "ADV"

        registerFoundDevice(address, name)

        // Delegate to Sniffing or Action listener
        onAdvertisingPacketReceived?.invoke(address, name, bytesHex)

        // Trigger action if advertisement packet matches bound remote address
        if (boundAddress.isNotEmpty() && address.equals(boundAddress, ignoreCase = true)) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTimestamp > DEBOUNCE_MS) {
                lastTriggerTimestamp = now
                Timber.d("$TAG: ⚡ BLE Remote packet received from bound remote $address! Triggering action...")
                onRemoteButtonPressed?.invoke()
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
                    val bytes = result.scanRecord?.bytes

                    handleAdvertisingPacket(address, rawName, bytes)
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
            Timber.d("$TAG: BLE Remote Scanner started successfully")
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

    private fun updateDiscoveredList() {
        _discoveredRemotes.value = discoveredRemotesMap.map { (address, name) ->
            DiscoveredRemote(
                address = address,
                name = name,
                connected = isConnected || (boundAddress.isNotEmpty() && address.equals(boundAddress, ignoreCase = true)),
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
