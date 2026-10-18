package io.github.JaJaJim.lightonkaroo.protocol

/** Custom light modes (sub-service 3): the protocol's constants and the iGPSPORT app's ranges. */
object CustomMode {
    /** BLE_LIGHT_CUSTOM_SUBTYPE: the pattern a slot plays. */
    const val STEADY = 0
    const val FLASH = 1
    const val BREATH = 2

    /** lightNum: the LED channel a brightness applies to. */
    const val HIGH_BEAM = 0
    const val LOW_BEAM = 1
    const val MAIN = 2

    /** BLM_CUSTOMIZE_1..12. */
    val SLOTS = 64..75

    /** The slot's number as the iGPSPORT app shows it: 64 is CUSTOM 1. */
    fun number(mode: Int): Int = mode - SLOTS.first + 1
    val SUBTYPES = STEADY..BREATH

    /** The iGPSPORT app's slider ranges. The light itself validates nothing (it stores 101 %, and keeps it). */
    val PCT = 0..100
    val CYCLE_SECONDS = 1..4
    val RATIO_PERCENT = 10..50
}

/** One LED channel's brightness in a pattern. */
data class CustomLight(val lightNum: Int, val pct: Int)

/** One pattern of a slot, with its own settings. Cycle and ratio are null when the light sends none (steady). */
data class CustomPattern(
    val subtype: Int,
    val lights: List<CustomLight>,
    val cycleSeconds: Int? = null,
    val ratioPercent: Int? = null,
) {
    /** The brightness the iGPSPORT app shows on its device card: the main or high-beam channel, else the first. */
    val mainPct: Int?
        get() = (lights.firstOrNull { it.lightNum == CustomMode.MAIN || it.lightNum == CustomMode.HIGH_BEAM } ?: lights.firstOrNull())?.pct
}

/** A custom slot: which pattern it plays ([selected]) and the settings of every pattern the light supports for it. */
data class CustomModeConfig(val mode: Int, val selected: Int, val patterns: List<CustomPattern>) {
    val active: CustomPattern? get() = patterns.firstOrNull { it.subtype == selected }

    /** Whether the light said what the slot plays: the selected pattern's data came with it. */
    val known: Boolean get() = active != null

    /** Flash and breath blink; steady doesn't. A config that isn't [known] counts as steady, like no config. */
    val blinks: Boolean get() = known && selected != CustomMode.STEADY

    val brightness: Int? get() = active?.mainPct

    /**
     * This config after the light accepted [change]. A change also selects its pattern: the iGPSPORT app always
     * sends the pattern it wants playing with every write. The session's read-back corrects it if the light
     * disagrees.
     */
    fun applied(change: CustomChange): CustomModeConfig = copy(
        selected = change.subtype,
        patterns = patterns.map { p ->
            if (p.subtype != change.subtype) p else when (change) {
                is CustomChange.Pattern -> p
                is CustomChange.Brightness ->
                    p.copy(lights = p.lights.map { if (it.lightNum == change.lightNum) it.copy(pct = change.pct) else it })
                is CustomChange.Cycle -> p.copy(cycleSeconds = change.seconds)
                is CustomChange.Ratio -> p.copy(ratioPercent = change.percent)
            }
        },
    )

    /**
     * The writes that turn this config into [target] (restore): every differing value, the other patterns first
     * and [target]'s selected pattern last, then a final pattern switch so [target]'s selected pattern plays.
     * Empty when nothing differs.
     */
    fun changesTo(target: CustomModeConfig): List<CustomChange> {
        val values = target.patterns.sortedBy { it.subtype == target.selected }.flatMap { t ->
            val current = patterns.firstOrNull { it.subtype == t.subtype }
            buildList {
                t.cycleSeconds?.takeIf { it != current?.cycleSeconds }?.let { add(CustomChange.Cycle(t.subtype, it)) }
                t.ratioPercent?.takeIf { it != current?.ratioPercent }?.let { add(CustomChange.Ratio(t.subtype, it)) }
                t.lights.forEach { light ->
                    if (current?.lights?.firstOrNull { it.lightNum == light.lightNum }?.pct != light.pct) {
                        add(CustomChange.Brightness(t.subtype, light.lightNum, light.pct))
                    }
                }
            }
        }
        if (values.isEmpty() && selected == target.selected) return emptyList()
        return values + CustomChange.Pattern(target.selected)
    }
}

/** One write to a custom slot, playing [subtype] (IgpsProtocol.modifyCustomMode). */
sealed interface CustomChange {
    val subtype: Int

    /** Switch the slot to [subtype] without changing any value. */
    data class Pattern(override val subtype: Int) : CustomChange
    data class Brightness(override val subtype: Int, val lightNum: Int, val pct: Int) : CustomChange
    data class Cycle(override val subtype: Int, val seconds: Int) : CustomChange
    data class Ratio(override val subtype: Int, val percent: Int) : CustomChange
}


