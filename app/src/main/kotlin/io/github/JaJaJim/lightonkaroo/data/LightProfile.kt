package io.github.JaJaJim.lightonkaroo.data

import io.github.JaJaJim.lightonkaroo.KarooLightControllerExtension
import io.github.JaJaJim.lightonkaroo.ant.LightMode
import io.github.JaJaJim.lightonkaroo.ble.MagicshineDeviceConfig
import io.github.JaJaJim.lightonkaroo.ble.MagicshineDeviceModeProvider
import kotlinx.serialization.Serializable

@Serializable
enum class LightRole { FRONT, REAR }

@Serializable
enum class LightProtocol { ANT_PLUS, BLE, IGPSPORT_BLE }

@Serializable
data class CustomSlotConfig(
    val slotNumber: Int = 1,
    val patternType: Int = 0, // 0: Steady, 1: Flash, 2: Breath
    val highBeamPct: Int = 100,
    val lowBeamPct: Int = 100,
    val mainPct: Int = 100,
    val autoDimming: Boolean = false,
)

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
    val showCustomProfiles: Boolean = false,
    val lowBatterySaveEnabled: Boolean = false,
    val customSlot1: CustomSlotConfig = CustomSlotConfig(1, 0, 100, 100, 100, false),
    val customSlot2: CustomSlotConfig = CustomSlotConfig(2, 0, 50, 50, 50, false),
    val customSlot3: CustomSlotConfig = CustomSlotConfig(3, 0, 25, 25, 25, false),
    val customSlot4: CustomSlotConfig = CustomSlotConfig(4, 0, 20, 20, 20, false),
    val customSlot5: CustomSlotConfig = CustomSlotConfig(5, 0, 15, 15, 15, false),
    val customSlot6: CustomSlotConfig = CustomSlotConfig(6, 0, 10, 10, 10, false),
    val brakeLightEnabled: Boolean = false,
    val brakeGThreshold: Float = 0.20f,
    val brakeHoldTimeSeconds: Int = 2,
    val brakeLightMode: String = "FAST_FLASH",
    val speedAdaptiveEnabled: Boolean = false,
    val speedThresholdKmh: Int = 30,
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

object IgpsportDeviceModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = listOf(
        LightModeOption("OFF", "Off"),
        LightModeOption("CUSTOM_1", "Custom 1"),
        LightModeOption("CUSTOM_2", "Custom 2"),
        LightModeOption("CUSTOM_3", "Custom 3"),
        LightModeOption("CUSTOM_4", "Custom 4"),
        LightModeOption("CUSTOM_5", "Custom 5"),
        LightModeOption("CUSTOM_6", "Custom 6"),
    )
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
    LightProtocol.IGPSPORT_BLE -> IgpsportDeviceModeProvider
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
    val remoteEnabled: Boolean = false,
    val remoteDeviceAddress: String = "",
    val remoteDeviceName: String = "",
    val remoteBoundBytesHex: String = "",
    val remoteSecondaryBytesHex: String = "",
    val remoteBoundKeycode: Int = 0,
    val remoteSecondaryKeycode: Int = 0,
    val remoteSniffingActive: Boolean = false,
    val remoteSniffingTarget: String = "PRIMARY", // PRIMARY or SECONDARY
    val remoteSniffingAddress: String = "",
    val lightAssignments: List<LightAssignment> = emptyList(),
) {
    fun migrateProfilesToAssignments(): LightControllerSettings {
        return this
    }
}
