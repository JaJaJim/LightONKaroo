package io.github.JaJaJim.lightonkaroo.ble

import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightModeProvider
import io.github.JaJaJim.lightonkaroo.protocol.CustomChange
import io.github.JaJaJim.lightonkaroo.protocol.CustomMode
import io.github.JaJaJim.lightonkaroo.protocol.IgpsProtocol
import io.github.JaJaJim.lightonkaroo.protocol.LightModes
import io.github.JaJaJim.lightonkaroo.protocol.SmartConfig

object IgpsportProtocol {

    const val SERVICE_UUID = "0000FF12-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000FF01-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_PREFIXES = setOf("VS1800", "VS1200", "VS800", "VS500", "VS", "IGP", "IGPSPORT")

    fun buildOffCommand(): ByteArray = IgpsProtocol.setMode(LightModes.OFF)

    fun buildModeCommand(modeCode: Int): ByteArray {
        val enableFrame = IgpsProtocol.setModeEnabled(modeCode, true)
        val modeFrame = IgpsProtocol.setMode(modeCode)
        return enableFrame + modeFrame
    }

    fun buildAutoDimmingCommand(enabled: Boolean): ByteArray {
        val status = if (enabled) SmartConfig.ON else SmartConfig.OFF
        return IgpsProtocol.setSmartConfig(SmartConfig.AUTO_LIGHT, status)
    }

    fun buildLowBatterySaveCommand(enabled: Boolean): ByteArray {
        val status = if (enabled) SmartConfig.ON else SmartConfig.OFF
        return IgpsProtocol.setSmartConfig(SmartConfig.AUTO_LOWBAT, status)
    }

    fun buildCustomModeConfigCommand(
        slotNumber: Int, // 1, 2, or 3
        patternType: Int, // 0: Steady, 1: Flash, 2: Breath
        highBeamPct: Int, // 0 to 100
        lowBeamPct: Int, // 0 to 100
    ): ByteArray {
        val slot = 63 + slotNumber.coerceIn(1, 3)
        val changePattern = CustomChange.Pattern(patternType)
        val changeHigh = CustomChange.Brightness(patternType, CustomMode.HIGH_BEAM, highBeamPct.coerceIn(0, 100))
        val changeLow = CustomChange.Brightness(patternType, CustomMode.LOW_BEAM, lowBeamPct.coerceIn(0, 100))

        val f1 = IgpsProtocol.modifyCustomMode(slot, changePattern)
        val f2 = IgpsProtocol.modifyCustomMode(slot, changeHigh)
        val f3 = IgpsProtocol.modifyCustomMode(slot, changeLow)
        return f1 + f2 + f3
    }

    fun buildQueryBattery(): ByteArray = IgpsProtocol.readBattery()

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

data class IgpsportDeviceConfig(
    val autoDimmingEnabled: Boolean = false,
    val lowBatterySaveEnabled: Boolean = false,
    val custom1HighBeam: Int = 100,
    val custom1LowBeam: Int = 100,
    val custom2HighBeam: Int = 50,
    val custom2LowBeam: Int = 50,
    val custom3HighBeam: Int = 25,
    val custom3LowBeam: Int = 25,
) : LightModeProvider {

    override fun availableModes(): List<LightModeOption> = listOf(
        LightModeOption("OFF", "Off"),
        LightModeOption("HIGH", "High"),
        LightModeOption("LOW", "Low"),
        LightModeOption("FLASH", "Flash"),
        LightModeOption("CUSTOM_1", "Custom 1"),
        LightModeOption("CUSTOM_2", "Custom 2"),
        LightModeOption("CUSTOM_3", "Custom 3"),
    )

    fun buildCommand(modeId: String, autoDimming: Boolean = false): ByteArray? {
        val modeCode = when (modeId.uppercase()) {
            "HIGH", "STEADY_100", "STEADY_HIGH" -> 1
            "MID", "STEADY_50", "STEADY_MID" -> 2
            "LOW", "STEADY_25", "STEADY_LOW" -> 3
            "FLASH_HI", "FLASH", "FLASH_100" -> 4
            "FLASH_LO", "FLASH_50" -> 5
            "CUSTOM_1", "CUSTOM1", "C1" -> 64
            "CUSTOM_2", "CUSTOM2", "C2" -> 65
            "CUSTOM_3", "CUSTOM3", "C3" -> 66
            "OFF" -> 0
            else -> 0
        }
        if (modeCode == 0) {
            return IgpsProtocol.setMode(0)
        }
        val enableFrame = IgpsProtocol.setModeEnabled(modeCode, true)
        val modeFrame = IgpsProtocol.setMode(modeCode)
        return enableFrame + modeFrame
    }
}
