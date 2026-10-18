package io.github.JaJaJim.lightonkaroo.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import io.github.JaJaJim.lightonkaroo.protocol.IgpsProtocol
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Raw android.bluetooth link to the light's Nordic UART service.
 *
 * - Scan-then-connect: a blind connectGatt on a busy Karoo radio often hangs; connecting right
 *   after an advertisement lands in the light's listen window (pattern from KarooB54).
 * - One GATT operation in flight: chunks are written one at a time, each after the previous ack.
 * - Reconnects with 1/2/4/8 s backoff until the flow is cancelled; watchdogs restart a scan or a
 *   GATT setup that stalls without a callback.
 */
@SuppressLint("MissingPermission")
class GattLightLink(private val context: Context) : LightLink {

    private val adapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val pending = ArrayDeque<ByteArray>()
    private var writeInFlight = false
    private var setupWatchdog: Job? = null

    override fun send(frame: ByteArray): Boolean {
        synchronized(lock) {
            if (gatt == null || writeChar == null) return false
            IgpsProtocol.chunks(frame).forEach { pending.addLast(it) }
            pumpLocked()
        }
        return true
    }

    @Suppress("DEPRECATION") // Karoo 3 is Android 12: the API 33 write overload doesn't exist there.
    private fun pumpLocked() {
        if (writeInFlight) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val next = pending.removeFirstOrNull() ?: return
        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        c.value = next
        writeInFlight = g.writeCharacteristic(c)
        if (!writeInFlight) {
            Timber.w("GATT write rejected; dropping %d queued chunks", pending.size)
            pending.clear()
        }
    }

    private fun clearLocked() {
        gatt = null
        writeChar = null
        pending.clear()
        writeInFlight = false
        setupWatchdog?.cancel()
        setupWatchdog = null
    }

    override fun connect(address: String): Flow<LinkEvent> = callbackFlow {
        val bt = adapter
        if (bt == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            Timber.w("Cannot connect to %s (no adapter or bad address)", address)
            close()
            return@callbackFlow
        }
        val device = bt.getRemoteDevice(address)
        // Flipped to false only under [lock], so a scan result can't open a GATT after teardown.
        val active = AtomicBoolean(true)
        val attempt = AtomicInteger(0)
        val scanCallbackRef = AtomicReference<ScanCallback?>(null)
        val pendingWriteChar = AtomicReference<BluetoothGattCharacteristic?>(null)
        // When the current search for the light began (elapsedRealtime); null while connected. Guarded by [lock].
        var searchStartedAt: Long? = null
        var startConnect: () -> Unit = {}

        fun scheduleRetry() {
            if (!active.get()) return
            val backoffMs = minOf(1000L shl attempt.getAndIncrement().coerceAtMost(3), 8000L)
            launch {
                delay(backoffMs)
                if (active.get()) startConnect()
            }
        }

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // HIGH only for the setup round trips; dropped to LOW_POWER once notifications are on.
                        val high = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                        Timber.i("GATT connected (status=%d, high priority=%b); discovering services", status, high)
                        if (!g.discoverServices()) {
                            Timber.w("discoverServices rejected; dropping the connection")
                            g.disconnect()
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.i("GATT disconnected (status=%d)", status)
                        // A gatt the setup watchdog already dropped has scheduled its own retry.
                        val current = synchronized(lock) { (gatt === g).also { if (it) clearLocked() } }
                        g.close()
                        if (current) {
                            trySend(LinkEvent.Disconnected)
                            scheduleRetry()
                        }
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(UART_SERVICE)
                val write = service?.getCharacteristic(UART_WRITE)
                val notify = service?.getCharacteristic(UART_NOTIFY)
                val cccd = notify?.getDescriptor(CCCD)
                if (write == null || notify == null || cccd == null) {
                    Timber.w("UART service missing on %s: not an iGPSPORT VS-series light, or its services are not ready yet", address)
                    g.disconnect()
                    return
                }
                g.setCharacteristicNotification(notify, true)
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                pendingWriteChar.set(write)
                if (!g.writeDescriptor(cccd)) {
                    Timber.w("CCCD write rejected; dropping the connection")
                    g.disconnect()
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (descriptor.uuid != CCCD) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w("Enabling notifications failed: %d", status)
                    g.disconnect()
                    return
                }
                synchronized(lock) {
                    if (gatt !== g) return // torn down or timed out meanwhile
                    // The link only carries a 60 s poll and taps, so a long connection interval saves Karoo and
                    // light power. Requested before writeChar is published, so no chunk write is in flight yet.
                    val lowPower = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
                    Timber.i("Setup done; low-power connection priority requested: %b", lowPower)
                    writeChar = pendingWriteChar.getAndSet(null)
                    setupWatchdog?.cancel()
                    setupWatchdog = null
                    searchStartedAt = null // connected: the next search starts again in BALANCED
                }
                attempt.set(0)
                trySend(LinkEvent.Connected)
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) Timber.w("Chunk write failed: %d", status)
                synchronized(lock) {
                    writeInFlight = false
                    pumpLocked()
                }
            }

            // Only the pre-API-33 callback: Karoo 3 is Android 12, and API 33+ still calls this one too
            // (overriding both would deliver every notification twice there).
            @Deprecated("Deprecated in Java")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == UART_NOTIFY) {
                    characteristic.value?.let { trySend(LinkEvent.Fragment(it.copyOf())) }
                }
            }
        }

        /** Setup watchdog: drops [g] and retries if the CCCD ack (Connected) hasn't arrived in time. */
        fun armSetupWatchdogLocked(g: BluetoothGatt) {
            setupWatchdog = launch {
                delay(SETUP_TIMEOUT_MS)
                synchronized(lock) {
                    if (gatt !== g || writeChar != null) return@launch
                    setupWatchdog = null // this job; don't let clearLocked cancel it
                    clearLocked()
                }
                Timber.w("GATT setup stalled for %d ms; reconnecting", SETUP_TIMEOUT_MS)
                runCatching { g.disconnect(); g.close() }
                scheduleRetry()
            }
        }

        /** Scan watchdog: a throttled or silently killed scan never reports, so replace it with a fresh one. */
        fun armScanWatchdog(scanner: BluetoothLeScanner, scanCallback: ScanCallback) {
            launch {
                delay(SCAN_RESTART_MS)
                if (active.get() && scanCallbackRef.compareAndSet(scanCallback, null)) {
                    Timber.i("No advert from %s in %d ms; restarting scan", address, SCAN_RESTART_MS)
                    runCatching { scanner.stopScan(scanCallback) }
                    startConnect()
                }
            }
        }

        startConnect = start@{
            val scanner = bt.bluetoothLeScanner
            if (scanner == null) {
                Timber.w("No BLE scanner (Bluetooth off?); retrying")
                scheduleRetry()
                return@start
            }
            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!scanCallbackRef.compareAndSet(this, null)) return
                    runCatching { scanner.stopScan(this) }
                    // Held across connectGatt + assignment: teardown either sees this gatt or stops us opening it,
                    // and an immediate disconnect callback can't run before `gatt` is set.
                    synchronized(lock) {
                        if (!active.get()) return
                        Timber.i("Found %s (rssi=%d); connecting", address, result.rssi)
                        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                        if (g == null) {
                            Timber.w("connectGatt returned null (Bluetooth off?); retrying")
                            scheduleRetry()
                            return
                        }
                        gatt = g
                        armSetupWatchdogLocked(g)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Timber.w("Connect scan failed: %d", errorCode)
                    scanCallbackRef.compareAndSet(this, null)
                    scheduleRetry()
                }
            }
            // Same lock as teardown's `active` flip: awaitClose either stops this scan or it never starts.
            synchronized(lock) {
                if (!active.get()) return@start
                // Duty cycle: after LOW_POWER_SCAN_AFTER_MS without finding the light, restarted scans go low power.
                val now = SystemClock.elapsedRealtime()
                val searchAge = now - (searchStartedAt ?: now.also { searchStartedAt = it })
                val lowPowerScan = searchAge >= LOW_POWER_SCAN_AFTER_MS
                val scanMode = if (lowPowerScan) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_BALANCED
                scanCallbackRef.getAndSet(scanCallback)?.let { old -> runCatching { scanner.stopScan(old) } }
                val failure = try {
                    scanner.startScan(
                        listOf(ScanFilter.Builder().setDeviceAddress(address).build()),
                        ScanSettings.Builder().setScanMode(scanMode).build(),
                        scanCallback,
                    )
                    null
                } catch (e: IllegalStateException) {
                    e // Bluetooth turned off
                } catch (e: SecurityException) {
                    e // Bluetooth permission revoked
                }
                if (failure != null) {
                    Timber.w(failure, "Connect scan could not start (Bluetooth off or permission revoked?); retrying")
                    scanCallbackRef.compareAndSet(scanCallback, null)
                    scheduleRetry()
                    return@start
                }
                Timber.d("Scanning for %s (low power=%b, searching for %d ms)", address, lowPowerScan, searchAge)
                armScanWatchdog(scanner, scanCallback)
            }
        }
        startConnect()

        awaitClose {
            synchronized(lock) {
                active.set(false)
                gatt?.let { g -> runCatching { g.disconnect(); g.close() } }
                clearLocked()
            }
            scanCallbackRef.getAndSet(null)?.let { cb -> runCatching { bt.bluetoothLeScanner?.stopScan(cb) } }
        }
    }

    private companion object {
        val UART_SERVICE: UUID = UUID.fromString(IgpsProtocol.UART_SERVICE)
        val UART_WRITE: UUID = UUID.fromString(IgpsProtocol.UART_WRITE)
        val UART_NOTIFY: UUID = UUID.fromString(IgpsProtocol.UART_NOTIFY)
        val CCCD: UUID = UUID.fromString(IgpsProtocol.CCCD)

        /** From connectGatt to the CCCD ack; a healthy setup takes 1-3 s. */
        const val SETUP_TIMEOUT_MS = 15_000L

        /** Restart an unanswered connect scan this often: one startScan per 30 s stays under Android's 5-per-30 s throttle. */
        const val SCAN_RESTART_MS = 30_000L

        /** Switch the connect scan from BALANCED to LOW_POWER once a search has gone this long without finding the light. */
        const val LOW_POWER_SCAN_AFTER_MS = 120_000L
    }
}


