package io.github.JaJaJim.lightonkaroo.light

/**
 * Where the app stands with the light's auto sleep ([io.github.JaJaJim.lightonkaroo.protocol.SmartConfig.AUTO_SLEEP]),
 * which [LightSession] switches off while it has the light off. Saved per light, so a restart picks it up.
 */
enum class SleepPause {
    /** Not ours: the light's own setting, left as it is. */
    NONE,

    /** Switched off by the app while it has the light off; the light is assumed off. */
    PAUSED,

    /**
     * The light has left OFF and the switch back on was sent, but no smart-config report has shown it on yet.
     * Sent again on the next connect; the light is not assumed off.
     */
    RESUMING,
}


