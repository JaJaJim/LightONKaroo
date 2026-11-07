package io.github.JaJaJim.lightonkaroo.ble

import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightModeProvider

object MageneProtocol {

    const val SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
    const val WRITE_CHARACTERISTIC_UUID = "8CE5CC05-0A4D-11E9-AB14-D663BD873D93"
    const val NOTIFY_CHARACTERISTIC_UUID = "8CE5CC06-0A4D-11E9-AB14-D663BD873D93"

    val SUPPORTED_PREFIXES = setOf("MAGENE", "AT1200", "AT1600", "AT", "L508", "L308")

    private var seq: Byte = 0x01

    private fun nextSeq(): Byte {
        val current = seq
        seq = ((seq.toInt() + 1) % 0xFF).toByte()
        return current
    }

    fun buildOffCommand(): ByteArray {
        return MagicshineProtocol.buildOffCommand(channel = 0)
    }

    fun buildModeCommand(modeIndex: Int, brightPct: Int = 100): ByteArray {
        val modelCode = when (modeIndex) {
            1, 2, 3 -> MagicshineProtocol.MODE_STEADY
            4, 5 -> MagicshineProtocol.MODE_FAST_FLASH
            6 -> MagicshineProtocol.MODE_SOS
            else -> MagicshineProtocol.MODE_STEADY
        }
        val pct = when (modeIndex) {
            1 -> 25
            2 -> 50
            3, 4 -> 100
            5 -> 50
            6 -> 100
            else -> brightPct.coerceIn(0, 100)
        }
        return MagicshineProtocol.buildBrightCommand(presetIndex = 1, channel = 0, model = modelCode, bright = pct)
    }

    fun buildAutoPowerOffCommand(enabled: Boolean, timeoutSeconds: Int): ByteArray {
        val content = ByteArray(16)
        content[0] = nextSeq()
        content[1] = 0x01
        content[2] = 0x00
        content[3] = 0x11
        content[4] = 0x11
        content[5] = 0x01
        content[6] = 0x80.toByte()
        val sec = timeoutSeconds.coerceIn(10, 3600)
        content[7] = (sec and 0xFF).toByte()
        content[8] = ((sec shl 8) and 0xFF).toByte()
        content[9] = 0x01
        content[10] = if (enabled) 0x01 else 0x00
        content[11] = 0x80.toByte()
        content[12] = 0x10
        content[13] = 0x27
        content[14] = 0x02
        return content
    }

    fun buildPrivilegedModeCommand(enable: Boolean): ByteArray {
        val flag = if (enable) 0x01.toByte() else 0x00.toByte()
        return byteArrayOf(0xDE.toByte(), 0x08, 0xB0.toByte(), 0x01, flag, 0x00, 0xBB.toByte(), (0x08 xor 0xB0 xor 0x01 xor flag.toInt()).toByte(), 0xED.toByte())
    }

    fun buildCustomSlotCommand(
        slotNumber: Int,
        patternType: Int,
        lumenValue: Int,
        durationSec: Float,
    ): ByteArray {
        val modelCode = when (patternType) {
            1 -> MagicshineProtocol.MODE_FAST_FLASH
            2 -> MagicshineProtocol.MODE_SOS
            else -> MagicshineProtocol.MODE_STEADY
        }
        val brightPct = (lumenValue / 12).coerceIn(0, 100)
        return MagicshineProtocol.buildBrightCommand(presetIndex = slotNumber.coerceIn(1, 4), channel = 0, model = modelCode, bright = brightPct)
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

object MageneDeviceModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = listOf(
        LightModeOption("OFF", "Off"),
        LightModeOption("PRESET_1", "Low Brightness"),
        LightModeOption("PRESET_2", "Medium Brightness"),
        LightModeOption("PRESET_3", "High Brightness"),
        LightModeOption("PRESET_4", "Day Flash"),
        LightModeOption("PRESET_5", "Night Flash"),
        LightModeOption("PRESET_6", "Breathing"),
        LightModeOption("CUSTOM_1", "Custom 1"),
        LightModeOption("CUSTOM_2", "Custom 2"),
        LightModeOption("CUSTOM_3", "Custom 3"),
        LightModeOption("CUSTOM_4", "Custom 4"),
    )
}
