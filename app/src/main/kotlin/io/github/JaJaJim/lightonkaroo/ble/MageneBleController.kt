package io.github.JaJaJim.lightonkaroo.ble

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
import kotlinx.coroutines.flow.update
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

class MageneBleDevice(
    val peripheral: Peripheral,
    val name: String,
)

@OptIn(ExperimentalUuidApi::class)
class MageneBleController(
    private val appContext: Context,
) : LightController {

    companion object {
        private const val TAG = "MageneBle"
        private const val WRITE_RETRIES = 3
        private const val WRITE_RETRY_DELAY_MS = 150L
    }

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
            )
        }
        _discoveredLights.value = newList
    }

    fun registerFoundDevice(address: String, name: String) {
        if (devices.containsKey(address)) return
        val peripheral = centralManager.getPeripheralsById(listOf(address)).firstOrNull() ?: run {
            Timber.w("$TAG: No peripheral for $address")
            return
        }
        devices[address] = MageneBleDevice(peripheral, name)
        Timber.d("$TAG: Magene device registered: $name ($address)")
        updateDiscoveredLights()
        startConnectionSupervisor(address)
    }

    fun connect(address: String) {
        if (characteristics.containsKey(address)) return
        startConnectionSupervisor(address)
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

    private suspend fun attemptConnect(address: String): Boolean {
        val peripheral = devices[address]?.peripheral
            ?: centralManager.getPeripheralsById(listOf(address)).firstOrNull()
            ?: return false
        val name = devices[address]?.name ?: peripheral.name ?: "Magene"
        devices.putIfAbsent(address, MageneBleDevice(peripheral, name))

        return try {
            Timber.d("$TAG: Connecting to Magene light $address")
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
                Timber.w("$TAG: Connection timeout for Magene $address")
                return false
            }

            val characteristic = findTargetCharacteristic(peripheral)
            if (characteristic == null) {
                Timber.w("$TAG: Magene write characteristic not found for $address")
                return false
            }
            characteristics[address] = characteristic
            Timber.d("$TAG: Connected to Magene $address, write characteristic found")

            delay(200L)
            updateDiscoveredLights()
            onDeviceConnected?.invoke()

            peripheral.state.first { it is ConnectionState.Disconnected }
            Timber.d("$TAG: Disconnected from Magene $address")
            true
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Connect attempt failed for Magene $address")
            false
        } finally {
            characteristics.remove(address)
            updateDiscoveredLights()
        }
    }

    private suspend fun findTargetCharacteristic(peripheral: Peripheral): RemoteCharacteristic? {
        val targetUuid = Uuid.parse(MageneProtocol.WRITE_CHARACTERISTIC_UUID)
        for (attempt in 1..10) {
            try {
                val services = peripheral.services().value
                val characteristic = services.flatMap { it.characteristics }.firstOrNull { it.uuid == targetUuid }
                if (characteristic != null) return characteristic
            } catch (_: Exception) { }
            delay(360L)
        }
        return null
    }

    override fun setMode(deviceId: String, modeName: String) {
        val modeIndex = when (modeName.uppercase()) {
            "PRESET_1", "LOW" -> 1
            "PRESET_2", "MEDIUM" -> 2
            "PRESET_3", "HIGH" -> 3
            "PRESET_4", "DAY_FLASH" -> 4
            "PRESET_5", "NIGHT_FLASH" -> 5
            "PRESET_6", "BREATHING" -> 6
            "CUSTOM_1" -> 7
            "CUSTOM_2" -> 8
            "CUSTOM_3" -> 9
            "CUSTOM_4" -> 10
            else -> 0
        }

        val command = if (modeIndex == 0 || modeName == "OFF") {
            MageneProtocol.buildOffCommand()
        } else {
            MageneProtocol.buildModeCommand(modeIndex)
        }

        _actualModes.update { it + (deviceId to modeName) }
        scope.launch {
            writeBytes(deviceId, command)
        }
        updateDiscoveredLights()
    }

    fun previewCustomSlot(deviceId: String, patternType: Int, lumenValue: Int, durationSec: Float) {
        val command = MageneProtocol.buildCustomSlotCommand(1, patternType, lumenValue, durationSec)
        scope.launch {
            writeBytes(deviceId, command)
        }
    }

    fun saveCustomSlotConfig(deviceId: String, slotNumber: Int, patternType: Int, lumenValue: Int, durationSec: Float) {
        scope.launch {
            Timber.d("$TAG: 💾 Saving Magene custom slot $slotNumber to EEPROM for $deviceId")
            writeBytes(deviceId, MageneProtocol.buildPrivilegedModeCommand(true))
            delay(250L)
            val cmd = MageneProtocol.buildCustomSlotCommand(slotNumber, patternType, lumenValue, durationSec)
            writeBytes(deviceId, cmd)
            delay(250L)
            writeBytes(deviceId, MageneProtocol.buildPrivilegedModeCommand(false))
            delay(250L)
            Timber.d("$TAG: 💾 Magene custom slot $slotNumber saved successfully!")
        }
    }

    fun setAutoPowerOff(deviceId: String, enabled: Boolean, timeoutSec: Int) {
        val command = MageneProtocol.buildAutoPowerOffCommand(enabled, timeoutSec)
        scope.launch {
            writeBytes(deviceId, command)
        }
    }

    private suspend fun writeBytes(address: String, bytes: ByteArray) {
        val characteristic = characteristics[address] ?: run {
            Timber.w("$TAG: Cannot write, characteristic null for $address")
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

    fun destroy() {
        scope.launch {
            characteristics.keys.toList().forEach { disconnect(it) }
            scope.cancel()
        }
    }
}
