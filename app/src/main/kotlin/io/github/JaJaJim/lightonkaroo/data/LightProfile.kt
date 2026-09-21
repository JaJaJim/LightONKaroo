package io.github.JaJaJim.lightonkaroo.data

import io.github.JaJaJim.lightonkaroo.ant.LightMode
import kotlinx.serialization.Serializable

enum class DayTimeZone {
    DAY,
    NIGHT,
}

@Serializable
enum class LightRole { FRONT, REAR }

@Serializable
enum class LightProtocol { ANT_PLUS, BLE }

@Serializable
data class LightAssignment(
    val deviceId: String,
    val deviceName: String,
    val role: LightRole,
    val protocol: LightProtocol = LightProtocol.ANT_PLUS,
    val activeMode: String = "OFF",
    val secondaryMode: String = "OFF",
    val modeOff: String = "OFF",
    val radarWarnFlash: Boolean = false,
    val nickname: String = "",
) {
    fun modeForState(state: Int): String = when (state) {
        1 -> activeMode
        2 -> secondaryMode
        else -> modeOff
    }

    val displayName: String
        get() = if (nickname.isNotEmpty()) nickname else deviceName
}

data class LightModeOption(
    val id: String,
    val displayName: String,
)

interface LightModeProvider {
    fun availableModes(): List<LightModeOption>
}

object AntPlusModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> =
        LightMode.FALLBACK_MODES.map { LightModeOption(it.karooName, it.displayName) }
}

class DynamicAntPlusModeProvider(private val karooNames: Set<String>) : LightModeProvider {
    override fun availableModes(): List<LightModeOption> {
        val modes = karooNames.mapNotNull { name ->
            val mode = LightMode.fromKarooName(name)
            if (mode != null) LightModeOption(mode.karooName, mode.displayName) else null
        }.sortedBy { LightMode.fromKarooName(it.id)?.modeNumber ?: Int.MAX_VALUE }
        return if (modes.any { it.id == "OFF" }) modes else listOf(LightModeOption("OFF", "Off")) + modes
    }
}

fun modeProviderFor(protocol: LightProtocol, deviceId: String? = null): LightModeProvider = when (protocol) {
    LightProtocol.ANT_PLUS -> {
        val supported = deviceId?.let {
            io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension.getInstance()
                ?.lightControl?.supportedModes?.value?.get(it)
        }
        if (supported != null) DynamicAntPlusModeProvider(supported) else AntPlusModeProvider
    }
    LightProtocol.BLE -> {
        val config = deviceId?.let {
            io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension.getInstance()
                ?.magicshineController?.getDeviceConfig(it)
        }
        if (config != null) {
            io.github.JaJaJim.lightonkaroo.ble.MagicshineDeviceModeProvider(config)
        } else {
            io.github.JaJaJim.lightonkaroo.ble.MagicshineDeviceModeProvider(
                io.github.JaJaJim.lightonkaroo.ble.MagicshineDeviceConfig.forDevice("M1"),
            )
        }
    }
}

@Serializable
data class LightControllerSettings(
    val autoOnWithRide: Boolean = true,
    val autoOffWithRide: Boolean = true,
    val pauseBehavior: String = "NONE", // NONE, OFF, PRIMARY, SECONDARY, HARD_OFF
    val showDetailedStatus: Boolean = true,
    val threeModeEnabled: Boolean = false,
    val rotationSpeedSeconds: Int = 5,
    val showLogo: Boolean = true,
    val glowIntensity: Int = 3,
    val lightAssignments: List<LightAssignment> = emptyList(),
) {
    fun migrateProfilesToAssignments(): LightControllerSettings {
        return this
    }
}
