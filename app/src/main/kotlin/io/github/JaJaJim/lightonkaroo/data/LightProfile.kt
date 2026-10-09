package io.github.JaJaJim.lightonkaroo.data

import io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension
import io.github.JaJaJim.lightonkaroo.ant.LightMode
import io.github.JaJaJim.lightonkaroo.ble.MagicshineDeviceConfig
import io.github.JaJaJim.lightonkaroo.ble.MagicshineDeviceModeProvider
import kotlinx.serialization.Serializable

@Serializable
enum class LightRole { FRONT, REAR }

@Serializable
enum class LightProtocol { ANT_PLUS, BLE }

@Serializable
data class LightAssignment(
    val deviceId: String,
    val deviceName: String,
    val role: LightRole = LightRole.REAR,
    val protocol: LightProtocol = LightProtocol.ANT_PLUS,
    val enabled: Boolean = true,
    val useForThreatMode: Boolean = false,
    val activeMode: String = "OFF",
    val secondaryMode: String = "OFF",
    val modeOff: String = "OFF",
    val softwareThreatMode: String = "FAST_FLASH",
    val threatModeYellow: String = "FAST_FLASH",
    val threatModeRed: String = "FAST_FLASH",
    val nickname: String = "",
) {
    val isThreatModeEnabled: Boolean
        get() = enabled && useForThreatMode

    val threatMode: String
        get() = if (useForThreatMode) softwareThreatMode else "DISABLED"

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
        val modes = karooNames.map { name ->
            val mode = LightMode.fromKarooName(name)
            if (mode != null) {
                LightModeOption(mode.karooName, mode.displayName)
            } else {
                val formatted = name.replace("_", " ")
                    .lowercase()
                    .split(" ")
                    .joinToString(" ") { word -> word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
                LightModeOption(name, formatted)
            }
        }.sortedBy { LightMode.fromKarooName(it.id)?.modeNumber ?: Int.MAX_VALUE }
        return if (modes.any { it.id == "OFF" }) modes else listOf(LightModeOption("OFF", "Off")) + modes
    }
}

fun modeProviderFor(protocol: LightProtocol, deviceId: String? = null): LightModeProvider = when (protocol) {
    LightProtocol.ANT_PLUS -> {
        val supported = deviceId?.let {
            KarooLightControllerExtension.getInstance()
                ?.lightControl?.supportedModes?.value?.get(it)
        }
        if (supported != null && supported.isNotEmpty()) DynamicAntPlusModeProvider(supported) else AntPlusModeProvider
    }
    LightProtocol.BLE -> {
        val config = deviceId?.let {
            KarooLightControllerExtension.getInstance()
                ?.magicshineController?.getDeviceConfig(it)
        }
        if (config != null) {
            MagicshineDeviceModeProvider(config)
        } else {
            MagicshineDeviceModeProvider(
                MagicshineDeviceConfig.forDevice("M1"),
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
    val threatHoldTimeSeconds: Int = 3,
    val overrideActiveModes: Boolean = false,
    val simulateRadar: Boolean = false,
    val softwareThreatModeEnabled: Boolean = false,
    val remoteDeviceAddress: String = "",
    val remoteDeviceName: String = "",
    val remoteBoundBytesHex: String = "",
    val remoteSecondaryBytesHex: String = "",
    val remoteSniffingActive: Boolean = false,
    val remoteSniffingTarget: String = "PRIMARY", // PRIMARY or SECONDARY
    val lightAssignments: List<LightAssignment> = emptyList(),
) {
    fun migrateProfilesToAssignments(): LightControllerSettings {
        return this
    }
}
