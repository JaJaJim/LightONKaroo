package io.github.JaJaJim.lightonkaroo.ble

import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightModeProvider

object IgpsportProtocol {

    const val SERVICE_UUID = "0000FF12-0000-1000-8000-00805f9b34fb"
    const val CHARACTERISTIC_UUID = "0000FF01-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_PREFIXES = setOf("VS1800", "VS1200", "VS800", "VS500", "VS", "IGP", "IGPSPORT")

    fun buildOffCommand(): ByteArray {
        return byteArrayOf(0xAA.toByte(), 0x05, 0x00, 0x00, 0x00, 0x55.toByte())
    }

    fun buildModeCommand(
        modeCode: Int, // 1: Steady, 2: Flash, 3: Pulse, 4: Custom
        brightnessPercent: Int = 100,
        autoDimming: Boolean = false,
    ): ByteArray {
        val brightByte = brightnessPercent.coerceIn(10, 100).toByte()
        val autoByte = if (autoDimming) 0x01.toByte() else 0x00.toByte()
        val checksum = (0xAA xor 0x05 xor modeCode xor brightByte.toInt() xor autoByte.toInt()).toByte()

        return byteArrayOf(
            0xAA.toByte(),
            0x05.toByte(),
            modeCode.toByte(),
            brightByte,
            autoByte,
            checksum,
        )
    }

    fun buildQueryBattery(): ByteArray {
        return byteArrayOf(0xAA.toByte(), 0x03, 0x01, 0xA8.toByte())
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

data class IgpsportDeviceConfig(
    val autoDimmingEnabled: Boolean = false,
) : LightModeProvider {

    override fun availableModes(): List<LightModeOption> = listOf(
        LightModeOption("OFF", "Off"),
        LightModeOption("STEADY_100", "Steady 100%"),
        LightModeOption("STEADY_50", "Steady 50%"),
        LightModeOption("STEADY_25", "Steady 25%"),
        LightModeOption("FLASH_100", "Flash 100%"),
        LightModeOption("FLASH_50", "Flash 50%"),
        LightModeOption("PULSE_100", "Pulse 100%"),
        LightModeOption("PULSE_50", "Pulse 50%"),
        LightModeOption("CUSTOM_100", "Custom High"),
        LightModeOption("CUSTOM_50", "Custom Mid"),
        LightModeOption("CUSTOM_25", "Custom Low"),
    )

    fun buildCommand(modeId: String, autoDimming: Boolean = false): ByteArray? {
        if (modeId == "OFF") return IgpsportProtocol.buildOffCommand()

        val parts = modeId.split("_", limit = 2)
        if (parts.size != 2) return null
        val bright = parts[1].toIntOrNull() ?: 100

        val modeCode = when (parts[0]) {
            "STEADY" -> 1
            "FLASH" -> 2
            "PULSE" -> 3
            "CUSTOM" -> 4
            else -> 1
        }

        return IgpsportProtocol.buildModeCommand(modeCode, bright, autoDimming)
    }
}
