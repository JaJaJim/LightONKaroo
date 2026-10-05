package io.github.JaJaJim.lightonkaroo.ant

enum class LightMode(val modeNumber: Int, val displayName: String, val karooName: String, val aliases: Set<String> = emptySet()) {
    OFF(0, "Off", "OFF", setOf("LIGHT_MODE_OFF", "DISABLED")),
    STEADY_1(1, "Steady 1", "STEADY1", setOf("STEADY_MEDIUM", "MEDIUM", "LIGHT_MODE_STEADY_MEDIUM")),
    STEADY_2(2, "Steady 2", "STEADY2", setOf("STEADY_MEDIUM_2")),
    STEADY_3(3, "Steady 3", "STEADY3"),
    STEADY_HIGH(4, "Steady High", "STEADY4", setOf("STEADY_HIGH", "HIGH", "LIGHT_MODE_STEADY_HIGH")),
    STEADY_LOW(5, "Steady Low", "STEADY5", setOf("STEADY_LOW", "LOW", "LIGHT_MODE_STEADY_LOW")),
    SLOW_FLASH(6, "Slow Flash", "SLOW_FLASH", setOf("DAY_PULSE", "NIGHT_FLASH", "LIGHT_MODE_SLOW_FLASH")),
    FAST_FLASH(7, "Fast Flash", "FAST_FLASH", setOf("DAY_FLASH", "FLASH", "LIGHT_MODE_FAST_FLASH")),
    RANDOM_FLASH(8, "Random Flash", "RANDOM_FLASH", setOf("LIGHT_MODE_RANDOM_FLASH")),
    AUTO(9, "Auto", "AUTO", setOf("AUTO_DAY", "AUTO_NIGHT", "SMART", "LIGHT_MODE_AUTO")),
    SIGNAL_LEFT_AUTO(10, "Signal Left Auto", "SIGNAL_LEFT_AUTO"),
    SIGNAL_LEFT(11, "Signal Left", "SIGNAL_LEFT"),
    SIGNAL_RIGHT_AUTO(12, "Signal Right Auto", "SIGNAL_RIGHT_AUTO"),
    SIGNAL_RIGHT(13, "Signal Right", "SIGNAL_RIGHT"),
    HAZARD(14, "Hazard", "HAZARD"),
    CUSTOM_1(15, "Custom 1", "CUSTOM_MODE_1", setOf("CUSTOM1", "MODE_1")),
    CUSTOM_2(16, "Custom 2", "CUSTOM_MODE_2", setOf("CUSTOM2", "MODE_2")),
    CUSTOM_3(17, "Custom 3", "CUSTOM_MODE_3", setOf("CUSTOM3", "MODE_3")),
    CUSTOM_4(18, "Custom 4", "CUSTOM_MODE_4", setOf("CUSTOM4", "MODE_4")),
    CUSTOM_5(19, "Custom 5", "CUSTOM_MODE_5", setOf("CUSTOM5", "MODE_5")),
    CUSTOM_6(20, "Custom 6", "CUSTOM_MODE_6", setOf("CUSTOM6", "MODE_6")),
    CUSTOM_7(21, "Custom 7", "CUSTOM_MODE_7", setOf("CUSTOM7", "MODE_7")),
    CUSTOM_8(22, "Custom 8", "CUSTOM_MODE_8", setOf("CUSTOM8", "MODE_8")),
    ;

    companion object {
        fun fromModeNumber(number: Int): LightMode? {
            return entries.find { it.modeNumber == number }
        }

        fun fromKarooName(name: String): LightMode? {
            val upper = name.uppercase()
            return entries.find { it.karooName == upper || upper in it.aliases }
        }

        val FALLBACK_MODES = listOf(OFF, STEADY_HIGH, STEADY_LOW, SLOW_FLASH, FAST_FLASH)
    }
}
