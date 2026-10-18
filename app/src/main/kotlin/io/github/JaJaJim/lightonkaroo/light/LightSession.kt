package io.github.JaJaJim.lightonkaroo.light

import io.github.JaJaJim.lightonkaroo.ble.LightLink
import io.github.JaJaJim.lightonkaroo.ble.LinkEvent
import io.github.JaJaJim.lightonkaroo.protocol.AutoDimTracker
import io.github.JaJaJim.lightonkaroo.protocol.CustomChange
import io.github.JaJaJim.lightonkaroo.protocol.CustomMode
import io.github.JaJaJim.lightonkaroo.protocol.CustomModeConfig
import io.github.JaJaJim.lightonkaroo.protocol.FrameAssembler
import io.github.JaJaJim.lightonkaroo.protocol.Hex
import io.github.JaJaJim.lightonkaroo.protocol.IgpsProtocol
import io.github.JaJaJim.lightonkaroo.protocol.LightModes
import io.github.JaJaJim.lightonkaroo.protocol.LightState
import io.github.JaJaJim.lightonkaroo.protocol.LightUpdate
import io.github.JaJaJim.lightonkaroo.protocol.SmartConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Turns link events into [LightState] and light commands into frames, for one paired light. [frameLog], when
 * set (debug builds), gets one line per received frame and what it parsed to.
 *
 * OFF stays off: while we have the light off, its auto sleep is paused ([SleepPause]), since asleep it would wake
 * lit when moved. [sleepPause] is the saved state from a previous session (a restart, a reboot) and
 * [onSleepPauseChanged] saves each change; it runs outside the command lock and must not call back into the
 * session's commands. A save that throws is logged and tried again after the next command.
 */
class LightSession(
    private val link: LightLink,
    private val address: String,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val frameLog: ((String) -> Unit)? = null,
    sleepPause: SleepPause = SleepPause.NONE,
    private val onSleepPauseChanged: (SleepPause) -> Unit = {},
) {
    private val _state = MutableStateFlow(LightState(autoSleepPaused = sleepPause == SleepPause.PAUSED))
    val state: StateFlow<LightState> = _state.asStateFlow()

    /**
     * Where we stand with the light's auto sleep. Written under [commandLock] ([setSleepPause]); only
     * [SleepPause.PAUSED] shows as [LightState.autoSleepPaused] ("paused while the light is off").
     */
    @Volatile
    private var sleepPause = sleepPause
    private val saveLock = Any()
    /** The value [onSleepPauseChanged] last saved (or the one we started from). Guarded by [saveLock]. */
    private var savedSleepPause = sleepPause

    private val assembler = FrameAssembler()
    private val dimTracker = AutoDimTracker()
    private var job: Job? = null
    private val commandLock = Any()
    private var offCommandAt: Long? = null
    private var reconnecting = false
    private var lastReconnectAt: Long? = null

    fun start(): Unit = synchronized(commandLock) {
        if (job != null) return
        job = scope.launch { runLink() }
    }

    fun stop(): Unit = synchronized(commandLock) {
        job?.cancel()
        job = null
        reconnecting = false
        dimTracker.reset()
        // The pause is kept (and stays saved): the next session resumes auto sleep once the light is on again.
        _state.value = LightState(autoSleepPaused = sleepPause == SleepPause.PAUSED)
    }

    /**
     * Forces an immediate reconnect while the light is not connected: drops the current link flow (and its
     * backoff) and collects a fresh one, which starts a new search right away. The last known state is kept.
     * No-op while connected or stopped. Serialized with [start]/[stop] on [commandLock].
     *
     * One reconnect in flight at a time: a call is ignored while the previous reconnect is still waiting for the
     * old link's teardown (cancelling that wait would let a new link start before the teardown ran), and within
     * [RECONNECT_MIN_INTERVAL_MS] of the last accepted call (each one starts a scan; Android throttles scans).
     */
    fun reconnectNow(): Unit = synchronized(commandLock) {
        val previous = job ?: return
        if (_state.value.connected) return
        if (reconnecting) {
            Timber.d("Reconnect ignored: the previous one is still pending")
            return
        }
        val at = now()
        lastReconnectAt?.let { last ->
            if (at - last < RECONNECT_MIN_INTERVAL_MS) {
                Timber.d("Reconnect ignored: last one was %d ms ago", at - last)
                return
            }
        }
        lastReconnectAt = at
        reconnecting = true
        // Cancelled here, not in the new job, so a stop() before the new job runs still ends the old link.
        previous.cancel()
        job = scope.launch {
            // Wait for the old flow's teardown before connecting again, so it can't close the new connection.
            if (!previous.isCompleted) previous.join()
            synchronized(commandLock) { if (job === coroutineContext.job) reconnecting = false }
            runLink()
        }
    }

    private suspend fun runLink() = coroutineScope {
        var poller: Job? = null
        link.connect(address).collect { event ->
            when (event) {
                LinkEvent.Connected -> {
                    assembler.reset()
                    command {
                        val connected = _state.value.copy(connected = true)
                        _state.value = if (sleepPause == SleepPause.PAUSED) {
                            // It couldn't sleep, so it is most likely still in our OFF: its answer to the mode
                            // read-back below (its remembered mode) must not end that, like the echo after OFF.
                            offCommandAt = now()
                            connected.copy(poweredOff = true)
                        } else {
                            connected
                        }
                        // The light had left OFF but never confirmed auto sleep back on (a frame dropped with the
                        // link): switch it on again, without assuming the light is off.
                        if (sleepPause == SleepPause.RESUMING) writeSmartConfig(SmartConfig.AUTO_SLEEP, on = true)
                    }
                    refreshAll()
                    poller?.cancel()
                    poller = launch {
                        while (true) {
                            delay(pollIntervalMs)
                            link.send(IgpsProtocol.readBattery())
                            link.send(IgpsProtocol.readRemainingTime())
                        }
                    }
                }
                LinkEvent.Disconnected -> {
                    poller?.cancel()
                    synchronized(commandLock) {
                        // The next connection starts a fresh "full" reference: an old one might no longer apply.
                        dimTracker.reset()
                        _state.value = _state.value.copy(connected = false)
                    }
                }
                is LinkEvent.Fragment -> assembler.push(event.bytes).forEach { frame ->
                    val update = IgpsProtocol.parseFrame(frame)
                    frameLog?.invoke("rx ${Hex.encode(frame)} -> $update")
                    update?.let { u -> command { onReport(u) } }
                    // Each declared custom slot's config decides whether it is SOLID or FLASH and how it's labelled.
                    update?.declaredModes?.keys?.filter { it in CustomMode.SLOTS }?.forEach {
                        link.send(IgpsProtocol.readCustomMode(it))
                    }
                }
            }
        }
    }

    /**
     * Selects [mode] ([LightModes.OFF] switches the light off), enabling it on the light first if it is
     * declared but disabled. False when not connected.
     *
     * Thread-safe: calls are serialized on [commandLock], so one call's command sequence always finishes
     * (all its frames sent) before another's starts — command sequences never interleave on the wire.
     */
    fun selectMode(mode: Int): Boolean = command {
        if (mode == LightModes.OFF) {
            if (!link.send(IgpsProtocol.setMode(LightModes.OFF))) return false
            // No read-back: the light would answer with its remembered mode, never 0.
            offCommandAt = now()
            _state.value = _state.value.copy(poweredOff = true)
            pauseSleep()
            return true
        }
        if (_state.value.declaredModes[mode] == false) {
            if (!link.send(IgpsProtocol.setModeEnabled(mode, true))) return false
            link.send(IgpsProtocol.readSupportedModes())
        }
        if (!link.send(IgpsProtocol.setMode(mode))) return false
        offCommandAt = null
        // Optimistic: the field shows the new mode right away; the read-back below confirms or corrects it.
        // Applied like a report, so the last steady / flash level is remembered too.
        _state.value = _state.value.apply(LightUpdate(mode = mode)).copy(poweredOff = false).withAutoDimmed()
        resumeSleep()
        // The light only ACKs writes: read the mode back so the UI shows what it really did.
        return link.send(IgpsProtocol.readCurrentMode())
    }

    /** Also serialized on [commandLock] (reentrant): the read of the current mode and the [selectMode] call
     * that follows it happen as one atomic step relative to other threads. */
    fun nextMode(): Boolean = command {
        val s = _state.value
        val next = LightModes.next(if (s.poweredOff) null else s.mode, s.enabledModes) ?: return false
        selectMode(next)
    }

    /**
     * Switches smart config [id] ([SmartConfig]) on or off, then reads the configs back. The field shows the
     * new status right away. False when not connected. Serialized on [commandLock].
     *
     * The user's own switch: an auto sleep we paused (or are resuming) becomes theirs again, left as they set it.
     */
    fun setSmartConfig(id: Int, on: Boolean): Boolean = command {
        val readBack = writeSmartConfig(id, on) ?: return false
        if (id == SmartConfig.AUTO_SLEEP) setSleepPause(SleepPause.NONE)
        readBack
    }

    /**
     * Writes smart config [id] and queues a read-back; the state shows the new status at once. Null when the
     * write wasn't sent (not connected), else whether the read-back was queued too. Callers hold [commandLock].
     */
    private fun writeSmartConfig(id: Int, on: Boolean): Boolean? {
        val status = if (on) SmartConfig.ON else SmartConfig.OFF
        if (!link.send(IgpsProtocol.setSmartConfig(id, status))) return null
        // Without auto light the output is never switched off for daylight, so a stale "output off" goes too.
        val clearsOutputOff = id == SmartConfig.AUTO_LIGHT && !on
        val current = _state.value
        _state.value = current.copy(
            smartConfigs = current.smartConfigs + (id to status),
            outputOff = current.outputOff && !clearsOutputOff,
        ).withAutoDimmed()
        return link.send(IgpsProtocol.readSmartConfigs())
    }

    /**
     * OFF stays off: switches the light's auto sleep off while we have the light off, when it is on. Asleep (after
     * a minute without motion) it stops advertising, and moving it wakes it lit in its last mode; kept awake, it
     * stays off. Remembered, so [resumeSleep] switches it back on; never touched when the user had it off. While a
     * resume is still unconfirmed it is ours either way, so it is paused again. Callers hold [commandLock].
     */
    private fun pauseSleep() {
        val on = _state.value.smartConfigs[SmartConfig.AUTO_SLEEP] == SmartConfig.ON
        if (!on && sleepPause != SleepPause.RESUMING) return
        // Only a write that went out counts: without it, the light's own setting is unchanged.
        if (writeSmartConfig(SmartConfig.AUTO_SLEEP, on = false) != null) setSleepPause(SleepPause.PAUSED)
    }

    /**
     * The light left our OFF: switches the auto sleep [pauseSleep] paused back on, then waits for a smart-config
     * report to confirm it ([onReport]). Until then it is [SleepPause.RESUMING], sent again on the next connect.
     * Idempotent. A write the link refuses keeps the pause, so the next mode change tries again. Callers hold
     * [commandLock].
     */
    private fun resumeSleep() {
        if (sleepPause != SleepPause.PAUSED) return
        if (writeSmartConfig(SmartConfig.AUTO_SLEEP, on = true) != null) setSleepPause(SleepPause.RESUMING)
    }

    /** Callers hold [commandLock]; [command] saves the change once the lock is released. */
    private fun setSleepPause(pause: SleepPause) {
        sleepPause = pause
        _state.value = _state.value.copy(autoSleepPaused = pause == SleepPause.PAUSED)
    }

    /**
     * Runs [block] under [commandLock], then saves a changed sleep pause ([saveSleepPause]) once the lock is
     * released, also when [block] returns early.
     */
    private inline fun <T> command(block: () -> T): T =
        try {
            synchronized(commandLock, block)
        } finally {
            saveSleepPause()
        }

    /**
     * Hands a changed [sleepPause] to [onSleepPauseChanged]. Never under [commandLock]: saving may block (a disk
     * write). A nested command skips it, and the outermost one saves once it has released the lock. [saveLock]
     * orders the saves and each reads the latest value, so the last save always wins. A save that throws is
     * logged, never thrown at the command's caller (the link collector, the UI thread), and tried again next time.
     */
    private fun saveSleepPause() {
        if (Thread.holdsLock(commandLock)) return
        synchronized(saveLock) {
            val pause = sleepPause
            if (pause == savedSleepPause) return
            try {
                onSleepPauseChanged(pause)
                savedSleepPause = pause
            } catch (e: Exception) {
                Timber.e(e, "Saving the auto-sleep pause (%s) failed", pause)
            }
        }
    }

    /**
     * Writes one [change] to custom slot [mode], shows it at once and queues a read-back of the slot (the light
     * only ACKs). The light applies an edit to the playing slot live, so nothing is re-selected. True means the
     * frames were queued on the link, not that the light took them: the read-back, or the next connect's read,
     * corrects the shown config if it didn't. False when not connected, or while the slot's config is unknown
     * (nothing to edit yet). Also false, without sending anything, for a value outside the app's ranges: the
     * light validates nothing, so the caller clamps, but a miss must not crash the process the extension service
     * runs in. Never throws. Serialized on [commandLock], like the other commands.
     */
    fun changeCustomMode(mode: Int, change: CustomChange): Boolean = synchronized(commandLock) {
        val current = _state.value.customModes[mode] ?: return false
        val frame = customFrames(mode, listOf(change))?.single() ?: return false
        if (!link.send(frame)) return false
        // Through apply(), so a pattern switch on the playing slot moves it between SOLID and FLASH at once.
        _state.value = _state.value.apply(LightUpdate(customMode = current.applied(change))).afterCustomEdit(mode)
        return link.send(IgpsProtocol.readCustomMode(mode))
    }

    /**
     * Turns custom slot [target.mode] back into [target] with only the writes that differ (the pattern switch
     * last), then queues a read-back. Without sending anything: true when the last known config already matches
     * and the light is connected; false when a write would carry a value outside the app's ranges (a snapshot the
     * light stored wrongly). False, with the state left as it was, when the link refuses a frame partway; some
     * writes may have landed, and the next connect reads the slot again. As with [changeCustomMode], true means
     * queued, not confirmed: a write the link drops after queueing it (it clears its queue on a failed write,
     * read-back included) shows as restored until the slot is read again, at the latest on the next connect.
     */
    fun restoreCustomMode(target: CustomModeConfig): Boolean = synchronized(commandLock) {
        val current = _state.value.customModes[target.mode] ?: return false
        val changes = current.changesTo(target)
        if (changes.isEmpty()) return _state.value.connected
        // Every frame is built before the first is sent, so an invalid value can't leave a half-restored slot.
        val frames = customFrames(target.mode, changes) ?: return false
        for (frame in frames) if (!link.send(frame)) return false
        val restored = changes.fold(current) { c, ch -> c.applied(ch) }
        _state.value = _state.value.apply(LightUpdate(customMode = restored)).afterCustomEdit(target.mode)
        return link.send(IgpsProtocol.readCustomMode(target.mode))
    }

    /**
     * An edit to the playing slot changes its brightness, so its run time moves: the auto-dim reference no longer
     * means anything. The next run-time report (at the new brightness) starts a fresh one; seeding it now would
     * use the run time from before the edit. Callers hold [commandLock] and assign the result to `_state.value`.
     */
    private fun LightState.afterCustomEdit(mode: Int): LightState {
        if (mode != this.mode) return this
        dimTracker.reset()
        return copy(autoDimmed = false)
    }

    /** The frames for [changes] to custom slot [mode], or null (logged) if any value is outside the app's ranges. */
    private fun customFrames(mode: Int, changes: List<CustomChange>): List<ByteArray>? = try {
        changes.map { IgpsProtocol.modifyCustomMode(mode, it) }
    } catch (e: IllegalArgumentException) {
        Timber.w(e, "Not writing custom mode %d", mode)
        null
    }

    /** SOLID: steady light, manual. Cycles the steady levels when already there, else returns to the last one. */
    fun selectSolid(): Boolean = selectManual({ it.isSteady(it.mode) }, { it.steadyLevels }) { it.steadyLevel }

    /** FLASH: the same as [selectSolid] for the flash levels (built-in flash modes, then custom slots that blink). */
    fun selectFlash(): Boolean = selectManual({ it.isFlashing(it.mode) }, { it.flashLevels }) { it.flashLevel }

    /** AUTO: switches auto light on, and the light itself on (current steady level) if we switched it off. */
    fun selectAuto(): Boolean = command {
        val wasOff = _state.value.poweredOff
        if (writeSmartConfig(SmartConfig.AUTO_LIGHT, on = true) != true) return false
        if (!wasOff) return true
        val level = _state.value.steadyLevel ?: return true
        return selectMode(level)
    }

    /**
     * Goes to a mode of a group: the next of [levels] when the light is already in the group ([inGroup]; on,
     * manual), else [current] (the last one used, or the light's first). SOLID and FLASH are manual, so auto
     * light is switched off first. One atomic step on [commandLock].
     */
    private fun selectManual(
        inGroup: (LightState) -> Boolean,
        levels: (LightState) -> List<Int>,
        current: (LightState) -> Int?,
    ): Boolean = command {
        val s = _state.value
        val cycling = !s.poweredOff && !s.autoLightOn && inGroup(s)
        val next = if (cycling) LightModes.next(s.mode, levels(s)) else null
        val target = next ?: current(s) ?: return false
        return selectManualMode(target)
    }

    /**
     * Selects [mode] as a manual mode: switches auto light off first when it is on, since the light otherwise
     * keeps driving its output itself and the mode change does nothing visible. One atomic step on [commandLock].
     */
    fun selectManualMode(mode: Int): Boolean = command {
        // Switching auto light off also clears a daylight "output off": manual modes always light.
        if (_state.value.autoLightOn && writeSmartConfig(SmartConfig.AUTO_LIGHT, on = false) != true) return false
        selectMode(mode)
    }

    /**
     * Applies one report from the light. When it ends "off" ([applyReport]; e.g. the light's own button), the
     * light has left our OFF, so the auto sleep we paused goes back on; a smart-config report showing it on
     * confirms that. Callers hold [commandLock].
     */
    private fun onReport(update: LightUpdate) {
        val before = _state.value
        val after = applyReport(before, update)
        _state.value = after
        if (before.poweredOff && !after.poweredOff) resumeSleep()
        // Only a report of auto sleep on ends a resume: a queued write can still be dropped with the link.
        if (sleepPause == SleepPause.RESUMING && update.smartConfigs?.get(SmartConfig.AUTO_SLEEP) == SmartConfig.ON) {
            setSleepPause(SleepPause.NONE)
        }
    }

    /**
     * Evidence that the light is on ends "off", unless it arrives within [OFF_SETTLE_MS] of our OFF command
     * (the light echoes its remembered mode right after it): a mode report (a button press, or the read-back
     * after a reconnect), or a spontaneous run-time state frame with a run time ([LightUpdate.outputOff] ==
     * false), which the light sends only while lit — switched back on by its own button it may send no mode
     * report at all. A polled run time is no evidence: the light answers it while off too. A (re)connect while
     * auto sleep is paused opens the same window, for the connect's mode read-back.
     *
     * Callers must already hold [commandLock]: this reads [offCommandAt] and, via [withAutoDimmed], mutates
     * [dimTracker].
     */
    private fun applyReport(state: LightState, update: LightUpdate): LightState {
        val applied = state.apply(update)
        val settling = offCommandAt?.let { now() - it < OFF_SETTLE_MS } ?: false
        val lit = update.mode != null || update.outputOff == false
        val powered =
            if (state.poweredOff && lit && !settling) applied.copy(poweredOff = false) else applied
        return powered.withAutoDimmed()
    }

    /**
     * Recomputes [LightState.autoDimmed] from the current mode / auto light / remaining time via [dimTracker].
     *
     * Callers must already hold [commandLock] and must assign the result directly to `_state.value`, never
     * from inside a `_state.update { }` transform: [dimTracker] is mutable, and `update {}`'s compare-and-swap
     * retry loop can invoke its lambda more than once under contention, so a discarded attempt would still
     * mutate the tracker's reference and leave it transiently wrong.
     */
    private fun LightState.withAutoDimmed(): LightState =
        copy(autoDimmed = dimTracker.update(mode, autoLightOn, remainingMinutes))

    private fun refreshAll() {
        link.send(IgpsProtocol.readSupportedModes())
        link.send(IgpsProtocol.readCurrentMode())
        link.send(IgpsProtocol.readBattery())
        link.send(IgpsProtocol.readRemainingTime())
        link.send(IgpsProtocol.readSmartConfigs())
    }

    private companion object {
        const val OFF_SETTLE_MS = 3_000L
        const val RECONNECT_MIN_INTERVAL_MS = 10_000L
    }
}


