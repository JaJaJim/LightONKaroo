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
import kotlinx.coroutines.launch
import timber.log.Timber

data class DiscoveredRemote(
    val address: String,
    val name: String,
    val connected: Boolean = false,
)

class BleRemoteController(context: Context) {

    companion object {
        private const val TAG = "BleRemote"
        private const val DEBOUNCE_MS = 300L
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val discoveredRemotesMap = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val _discoveredRemotes = MutableStateFlow<List<DiscoveredRemote>>(emptyList())
    val discoveredRemotes: StateFlow<List<DiscoveredRemote>> = _discoveredRemotes

    var boundAddress: String = ""
    var boundPatternHex: String = ""
    var onAdvertisingPacketReceived: ((address: String, name: String, hexPattern: String) -> Unit)? = null
    var onRemoteButtonPressed: (() -> Unit)? = null
    var isSettingsUiActive: Boolean = false

    private var scanCallback: android.bluetooth.le.ScanCallback? = null
    private var lastTriggerTimestamp = 0L

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
                    Timber.d("$TAG: Starting Raw BLE Advertising Scanner (attempt $attempt)")
                    executeBleScan(scanner)
                    break
                }
                delay(500L)
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
                Timber.d("$TAG: ⚡ Raw BLE Advertising Packet received from bound remote $address! Triggering action...")
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
                Timber.e("$TAG: Raw BLE Advertising scan failed: $errorCode")
            }
        }
        try {
            val settings = android.bluetooth.le.ScanSettings.Builder()
                .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            scanner.startScan(null, settings, callback)
            scanCallback = callback
            Timber.d("$TAG: Raw BLE Advertising Scanner started successfully")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start Raw BLE Advertising scan")
        }
    }

    fun stopDiscovery() {
        scanCallback?.let { cb ->
            try {
                bleScanner?.stopScan(cb)
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to stop Raw BLE Advertising scan")
            }
        }
        scanCallback = null
    }

    private fun updateDiscoveredList() {
        _discoveredRemotes.value = discoveredRemotesMap.map { (address, name) ->
            DiscoveredRemote(
                address = address,
                name = name,
                connected = boundAddress.isNotEmpty() && address.equals(boundAddress, ignoreCase = true),
            )
        }
    }

    fun destroy() {
        scope.launch {
            stopDiscovery()
            scope.cancel()
        }
    }
}
