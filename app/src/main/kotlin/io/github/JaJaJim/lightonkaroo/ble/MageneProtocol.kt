package io.github.JaJaJim.lightonkaroo.ble

import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightModeProvider

object MageneProtocol {

    const val SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
    const val WRITE_CHARACTERISTIC_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"
    const val NOTIFY_CHARACTERISTIC_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"

    val SUPPORTED_PREFIXES = setOf("MAGENE", "AT1200", "AT1600", "AT", "L508")

    fun buildOffCommand(): ByteArray = MagicshineProtocol.MODULE2_OFF

    fun buildCustomSlotCommand(
        patternType: Int, // 0: Steady, 1: Flash, 2: SOS
        brightnessPct: Int, // 0..100
    ): ByteArray {
        val modeCode = when (patternType) {
            1 -> 0x03 // Flash
            2 -> 0x02 // SOS / Pulse
            else -> 0x01 // Steady
        }
        val bright = brightnessPct.coerceIn(0, 100)
        return MagicshineProtocol.buildModule2Frame(modeCode, bright)
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

object MageneDeviceModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = listOf(
        LightModeOption("OFF", "Off"),
        LightModeOption("CUSTOM_1", "Custom 1"),
        LightModeOption("CUSTOM_2", "Custom 2"),
        LightModeOption("CUSTOM_3", "Custom 3"),
        LightModeOption("CUSTOM_4", "Custom 4"),
        LightModeOption("CUSTOM_5", "Custom 5"),
        LightModeOption("CUSTOM_6", "Custom 6"),
        LightModeOption("CUSTOM_7", "Custom 7"),
    )
}
