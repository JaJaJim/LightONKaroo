package io.github.JaJaJim.lightonkaroo.ble

import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightModeProvider
import io.github.JaJaJim.lightonkaroo.protocol.IgpsProtocol
import io.github.JaJaJim.lightonkaroo.protocol.LightModes

object IgpsportProtocol {

    const val SERVICE_UUID = "0000FF12-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000FF01-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_PREFIXES = setOf("VS1800", "VS1200", "VS800", "VS500", "VS", "IGP", "IGPSPORT")

    fun buildOffCommand(): ByteArray = IgpsProtocol.setMode(LightModes.OFF)

    fun buildModeCommand(modeCode: Int): ByteArray = IgpsProtocol.setMode(modeCode)

    fun buildQueryBattery(): ByteArray = IgpsProtocol.readBattery()

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

data class IgpsportDeviceConfig(
    val autoDimmingEnabled: Boolean = false,
) : LightModeProvider {

    override fun availableModes(): List<LightModeOption> = listOf(
        LightModeOption("OFF", "Off"),
        LightModeOption("HIGH", "High"),
        LightModeOption("MID", "Mid"),
        LightModeOption("LOW", "Low"),
        LightModeOption("FLASH_HI", "Flash Hi"),
        LightModeOption("FLASH_LO", "Flash Lo"),
        LightModeOption("CUSTOM_1", "Custom 1 (C1)"),
        LightModeOption("CUSTOM_2", "Custom 2 (C2)"),
        LightModeOption("CUSTOM_3", "Custom 3 (C3)"),
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
        return IgpsProtocol.setMode(modeCode)
    }
}
