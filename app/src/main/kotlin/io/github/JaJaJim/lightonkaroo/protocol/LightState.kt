package io.github.JaJaJim.lightonkaroo.protocol

/** Everything currently known about the light. Null = not reported yet. */
data class LightState(
    val connected: Boolean = false,
    val mode: Int? = null,
    val batteryPercent: Int? = null,
    val remainingMinutes: Int? = null,
    val declaredModes: Map<Int, Boolean> = emptyMap(),
    /** True after we switched the light off: the light itself keeps reporting its remembered mode. */
    val poweredOff: Boolean = false,
    /** [SmartConfig] id to status, in the light's order. */
    val smartConfigs: Map<Int, Int> = emptyMap(),
    /** The 0x6B battery reading ([LightUpdate.auxBatteryPercent]); not shown. */
    val auxBatteryPercent: Int? = null,
    /** Auto light has switched the output off (daylight); the light still reports its mode as before. */
    val outputOff: Boolean = false,
    /** The last steady / flashing mode reported ([isSteady] / [isFlashing]), so SOLID / FLASH go back to it. */
    val lastSteadyMode: Int? = null,
    val lastFlashMode: Int? = null,
    /** Custom slot configs read from the light (sub 3), by mode. */
    val customModes: Map<Int, CustomModeConfig> = emptyMap(),
    /**
     * True while auto light looks like it dimmed itself (inferred from run-time jumps by [AutoDimTracker]).
     * Like [poweredOff], `apply()` must leave this untouched: it is owned and recomputed by the session.
     */
    val autoDimmed: Boolean = false,
    /**
     * True while the app has switched the light's auto sleep ([SmartConfig.AUTO_SLEEP]) off because we switched
     * the light off: asleep, moving it would wake it lit. Resumed when the light is on again. Like [poweredOff],
     * `apply()` must leave this untouched: it is owned by the session (and saved across restarts).
     */
    val autoSleepPaused: Boolean = false,
) {
    /** Modes that can be selected right now (enabled on the light), in the light's order. */
    val enabledModes: List<Int> get() = declaredModes.filterValues { it }.keys.toList()

    val autoLightOn: Boolean get() = smartConfigs[SmartConfig.AUTO_LIGHT] == SmartConfig.ON

    /**
     * The steady levels SOLID cycles: the enabled ones, else the declared ones even though disabled (selecting
     * a disabled mode enables it first), so a light that ships a group disabled still offers it. Once any level
     * of a group is enabled, the disabled ones are left alone.
     */
    val steadyLevels: List<Int> get() = selectable { LightModes.steadyLevels(it, customModes) }

    /** The flash levels FLASH cycles, chosen like [steadyLevels]. */
    val flashLevels: List<Int> get() = selectable { LightModes.flashLevels(it, customModes) }

    /**
     * The steady level SOLID goes to: the last one used while it is still steady, else the light's first. Null if
     * none is known.
     */
    val steadyLevel: Int? get() = lastSteadyMode?.takeIf { isSteady(it) } ?: steadyLevels.firstOrNull()

    /**
     * The flash level FLASH goes to: the last one used while it still blinks, else the light's first. Null if none
     * is known.
     */
    val flashLevel: Int? get() = lastFlashMode?.takeIf { isFlashing(it) } ?: flashLevels.firstOrNull()

    /** Whether [mode] is steady (SOLID), classifying a custom slot by its known config ([LightModes.isSteady]). */
    fun isSteady(mode: Int?): Boolean = mode != null && LightModes.isSteady(mode, customModes)

    /** Whether [mode] blinks (FLASH), classifying a custom slot by its known config ([LightModes.isFlashing]). */
    fun isFlashing(mode: Int?): Boolean = mode != null && LightModes.isFlashing(mode, customModes)

    fun labelOf(mode: Int): String = LightModes.label(mode, customModes)

    fun shortLabelOf(mode: Int): String = LightModes.shortLabel(mode, customModes)

    private fun selectable(levels: (List<Int>) -> List<Int>): List<Int> =
        levels(enabledModes).ifEmpty { levels(declaredModes.keys.toList()) }

    fun apply(update: LightUpdate): LightState {
        val newMode = update.mode
        // A config without its selected pattern's data says nothing about the slot: it counts as unknown.
        val customs = update.customMode?.let { if (it.known) customModes + (it.mode to it) else customModes - it.mode } ?: customModes
        // A new mode is classified as it arrives. So is the playing slot when its config arrives: it is read
        // after the mode on connect, and a pattern switch brings no mode report.
        val classify = newMode ?: mode?.takeIf { it == update.customMode?.mode }
        return copy(
            mode = newMode ?: mode,
            batteryPercent = update.batteryPercent ?: batteryPercent,
            remainingMinutes = update.remainingMinutes ?: remainingMinutes,
            declaredModes = update.declaredModes ?: declaredModes,
            smartConfigs = update.smartConfigs ?: smartConfigs,
            auxBatteryPercent = update.auxBatteryPercent ?: auxBatteryPercent,
            outputOff = update.outputOff ?: outputOff,
            customModes = customs,
            lastSteadyMode = classify?.takeIf { LightModes.isSteady(it, customs) } ?: lastSteadyMode,
            lastFlashMode = classify?.takeIf { LightModes.isFlashing(it, customs) } ?: lastFlashMode,
        )
    }
}


