package io.github.JaJaJim.lightonkaroo.protocol

object LightModes {
    const val OFF = 0

    /**
     * CUSTOM 1. The VS1200S ships it as a low steady level (~700 min run time, measured on the light), hence the
     * "LOW" label while its config is unknown; it can be edited, so a known config labels it by what it does.
     */
    const val CUSTOM_LOW = 64

    private val CUSTOM = CustomMode.SLOTS

    /** Steady modes, brightest first: the SOLID cycle order. Custom modes (dimmest) follow, ascending. */
    private val STEADY_RANK = listOf(16, 1, 7, 10, 2, 8, 11, 3, 9, 12) + CUSTOM

    /** Flash modes in FLASH cycle order. */
    private val FLASH_RANK = listOf(4, 5, 6, 17)

    /** Built-in modes that light continuously (SOLID); custom slots are classified by [isSteady]. */
    private val STEADY: Set<Int> = STEADY_RANK.toSet()

    /** Built-in modes that blink (FLASH). */
    private val FLASHING: Set<Int> = FLASH_RANK.toSet()

    private val LABELS = mapOf(
        0 to "OFF", 1 to "HIGH", 2 to "MID", 3 to "LOW", 4 to "FLASH HI", 5 to "FLASH LO", 6 to "PULSE",
        7 to "HB HIGH", 8 to "HB MID", 9 to "HB LOW", 10 to "LB HIGH", 11 to "LB MID", 12 to "LB LOW",
        16 to "BOOST", 17 to "SOS", CUSTOM_LOW to "LOW",
    )

    private val SHORT_LABELS = mapOf(
        0 to "OFF", 1 to "HI", 2 to "MID", 3 to "LO", 4 to "FL HI", 5 to "FL LO", 6 to "PULSE",
        7 to "HB HI", 8 to "HB MID", 9 to "HB LO", 10 to "LB HI", 11 to "LB MID", 12 to "LB LO",
        16 to "BOOST", 17 to "SOS", CUSTOM_LOW to "LO",
    )

    fun label(mode: Int): String = LABELS[mode] ?: if (mode in CUSTOM) "CUSTOM ${CustomMode.number(mode)}" else "MODE $mode"

    /** A label short enough for a one-row field. */
    fun shortLabel(mode: Int): String = SHORT_LABELS[mode] ?: if (mode in CUSTOM) "C${CustomMode.number(mode)}" else "M$mode"

    /** The mode after [current] in [enabled], wrapping around. Off is never part of the cycle. */
    fun next(current: Int?, enabled: List<Int>): Int? {
        val cycle = enabled.filter { it != OFF }
        if (cycle.isEmpty()) return null
        val index = cycle.indexOf(current)
        return if (index < 0) cycle.first() else cycle[(index + 1) % cycle.size]
    }

    /**
     * Steady (SOLID): a custom slot counts while its selected pattern is steady, or while its config is unknown
     * (that was the behaviour before configs could be read). Other modes by [STEADY].
     */
    fun isSteady(mode: Int, customs: Map<Int, CustomModeConfig> = emptyMap()): Boolean =
        if (mode in CUSTOM) customs[mode]?.blinks != true else mode in STEADY

    /** Flashing (FLASH): a custom slot only while its selected pattern is known to blink (flash or breath). */
    fun isFlashing(mode: Int, customs: Map<Int, CustomModeConfig> = emptyMap()): Boolean =
        if (mode in CUSTOM) customs[mode]?.blinks == true else mode in FLASHING

    /** The steady modes among [modes], brightest first (VS1200S: HIGH, MID, CUSTOM 1 while steady). */
    fun steadyLevels(modes: List<Int>, customs: Map<Int, CustomModeConfig> = emptyMap()): List<Int> =
        STEADY_RANK.filter { it in modes && isSteady(it, customs) }

    /** The flash modes among [modes], in [FLASH_RANK] order, then custom slots that blink. */
    fun flashLevels(modes: List<Int>, customs: Map<Int, CustomModeConfig> = emptyMap()): List<Int> =
        (FLASH_RANK + CUSTOM).filter { it in modes && isFlashing(it, customs) }

    /** [label], but a custom slot with a known config says what it does: "C1 30%", "C1 FLASH", "C1 BREATH". */
    fun label(mode: Int, customs: Map<Int, CustomModeConfig>): String {
        val config = customs[mode]?.takeIf { mode in CUSTOM && it.known } ?: return label(mode)
        val slot = "C${CustomMode.number(mode)}"
        return when (config.selected) {
            CustomMode.STEADY -> config.brightness?.let { "$slot $it%" } ?: slot
            CustomMode.FLASH -> "$slot FLASH"
            CustomMode.BREATH -> "$slot BREATH"
            else -> slot
        }
    }

    /** [shortLabel], but "C<n>" for a custom slot with a known config (the old "LO" may no longer be true). */
    fun shortLabel(mode: Int, customs: Map<Int, CustomModeConfig>): String =
        if (mode in CUSTOM && customs[mode]?.known == true) "C${CustomMode.number(mode)}" else shortLabel(mode)
}


