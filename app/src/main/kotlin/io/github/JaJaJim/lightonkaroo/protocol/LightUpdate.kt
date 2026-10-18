package io.github.JaJaJim.lightonkaroo.protocol

/** What one frame from the light said. Null = not mentioned in this frame. */
data class LightUpdate(
    val mode: Int? = null,
    val batteryPercent: Int? = null,
    val remainingMinutes: Int? = null,
    /** Every mode the light declares, in its order, mapped to whether it is enabled. */
    val declaredModes: Map<Int, Boolean>? = null,
    /** Every smart config the light declares ([SmartConfig] id), in its order, mapped to its status. */
    val smartConfigs: Map<Int, Int>? = null,
    /**
     * A battery percentage from the VS1200S's 0x6B service (sub 7), close to but not always equal to
     * [batteryPercent]. Parsed and kept, but not shown anywhere.
     */
    val auxBatteryPercent: Int? = null,
    /**
     * Only set by the light's spontaneous run-time state frames (type 03), never by a polled read-back: true when
     * it carries no run time (auto light has switched the output off, daylight), false when it does (lit).
     */
    val outputOff: Boolean? = null,
    /** A custom slot's config, from a custom-mode reply (sub 3). */
    val customMode: CustomModeConfig? = null,
)


