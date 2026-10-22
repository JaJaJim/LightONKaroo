package io.github.JaJaJim.lightonkaroo.ble

import io.github.JaJaJim.lightonkaroo.data.LightModeOption
import io.github.JaJaJim.lightonkaroo.data.LightModeProvider

object VeloLinkCodec {

    private val CRC16_TABLE = intArrayOf(
        0, 4129, 8258, 12387, 16516, 20645, 24774, 28903, 33032, 37161, 41290, 45419, 49548, 53677, 57806, 61935,
        4657, 528, 12915, 8786, 21173, 17044, 29431, 25302, 37689, 33560, 45947, 41818, 54205, 50076, 62463, 58334,
        9314, 13379, 1056, 5121, 25830, 29895, 17572, 21637, 42346, 46411, 34088, 38153, 58862, 62927, 50604, 54669,
        13907, 9842, 5649, 1584, 30423, 26358, 22165, 18100, 46939, 42874, 38681, 34616, 63455, 59390, 55197, 51132,
        18532, 22597, 26662, 30727, 2272, 6337, 10402, 14531, 51500, 55565, 59758, 63823, 35240, 39305, 43498, 47563,
        23061, 18996, 31319, 27254, 6801, 2736, 15059, 10994, 56093, 52028, 64351, 60286, 39833, 35768, 48091, 44026,
        27718, 31847, 19460, 23589, 11458, 15587, 3200, 7329, 60750, 64879, 52492, 56621, 44490, 48619, 36232, 40361,
        32375, 28246, 24117, 19988, 16115, 11986, 7857, 3728, 65407, 61278, 57149, 53020, 49147, 45018, 40889, 36760,
        37256, 33193, 45514, 41451, 53516, 49453, 61774, 57711, 4224, 161, 12482, 8419, 20484, 16421, 28742, 24679,
        33721, 37784, 41979, 46042, 49981, 54044, 58239, 62302, 689, 4752, 8947, 13010, 16949, 21012, 25207, 29270,
        46570, 42443, 38312, 34185, 62830, 58703, 54572, 50445, 13538, 9411, 5280, 1153, 29798, 25671, 21540, 17413,
        42971, 47098, 34713, 38840, 59231, 63358, 50973, 55100, 9939, 14066, 1681, 5808, 26199, 30326, 17941, 22068,
        55788, 51661, 63918, 59791, 39272, 35145, 47402, 43275, 22756, 18629, 30886, 26759, 6240, 2113, 14370, 10243,
        52093, 56156, 60223, 64286, 35833, 39896, 43963, 48026, 19061, 23124, 27191, 31254, 2801, 6864, 10931, 14994,
        64814, 60687, 56684, 52557, 48554, 44427, 40424, 36297, 31782, 27655, 23652, 19525, 15522, 11395, 7392, 3265,
        61215, 65342, 53085, 57212, 44955, 49082, 36825, 40952, 28183, 32310, 20053, 24180, 11923, 16050, 3793, 7920
    )

    fun crc16(data: ByteArray, seed: Int = 0): Int {
        var crc = seed and 0xFFFF
        for (b in data) {
            val i = b.toInt() and 0xFF
            crc = ((crc shl 8) xor CRC16_TABLE[(i xor (crc ushr 8)) and 0xFF]) and 0xFFFF
        }
        return crc
    }

    fun buildTlv(type: Byte, seq: Byte, siid: Byte, iid: Byte, value: ByteArray): ByteArray {
        val tlv = ByteArray(value.size + 5)
        tlv[0] = type
        tlv[1] = seq
        tlv[2] = siid
        tlv[3] = iid
        tlv[4] = value.size.toByte()
        if (value.isNotEmpty()) {
            System.arraycopy(value, 0, tlv, 5, value.size)
        }
        return tlv
    }

    fun encodeFrame(srcId: Short = 257, dstId: Short = 4097, payload: ByteArray): ByteArray {
        val len = payload.size
        val frame = ByteArray(len + 9)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x02
        frame[2] = len.toByte()
        frame[3] = (srcId.toInt() and 0xFF).toByte()
        frame[4] = ((srcId.toInt() shr 8) and 0xFF).toByte()
        frame[5] = (dstId.toInt() and 0xFF).toByte()
        frame[6] = ((dstId.toInt() shr 8) and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, frame, 7, payload.size)
        }
        val crc = crc16(frame.copyOf(len + 7))
        frame[len + 7] = (crc and 0xFF).toByte()
        frame[len + 8] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }
}

object MageneProtocol {

    const val SERVICE_UUID = "0000FFF0-0000-1000-8000-00805F9B34FB"
    const val WRITE_CHARACTERISTIC_UUID = "0000FFF2-0000-1000-8000-00805F9B34FB"
    const val NOTIFY_CHARACTERISTIC_UUID = "0000FFF1-0000-1000-8000-00805F9B34FB"

    val SUPPORTED_PREFIXES = setOf("MAGENE", "AT1200", "AT1600", "AT", "L508")

    fun buildOffCommand(): ByteArray = MagicshineProtocol.buildOffCommand(0)

    fun buildCustomSlotCommand(
        patternType: Int, // 0: Steady, 1: Flash, 2: SOS
        brightnessPct: Int, // 0..100
    ): ByteArray {
        val model = when (patternType) {
            1 -> MagicshineProtocol.MODE_FAST_FLASH
            2 -> MagicshineProtocol.MODE_SOS
            else -> MagicshineProtocol.MODE_STEADY
        }
        val bright = brightnessPct.coerceIn(0, 100)
        return MagicshineProtocol.buildBrightCommand(presetIndex = 1, channel = 0, model = model, bright = bright)
    }

    fun buildVeloLinkSetLightCommand(on: Boolean, brightPct: Int, mode: Int): ByteArray {
        val tlv1 = VeloLinkCodec.buildTlv(0x02, 0x00, 0x01, 0x01, byteArrayOf(if (on) 1 else 0))
        val tlv2 = VeloLinkCodec.buildTlv(0x02, 0x00, 0x01, 0x03, byteArrayOf(brightPct.coerceIn(0, 100).toByte()))
        val tlv3 = VeloLinkCodec.buildTlv(0x02, 0x00, 0x01, 0x04, byteArrayOf(mode.coerceIn(0, 3).toByte()))
        return VeloLinkCodec.encodeFrame(payload = tlv1 + tlv2 + tlv3)
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}

object MageneDeviceModeProvider : LightModeProvider {
    override fun availableModes(): List<LightModeOption> = listOf(
        LightModeOption("OFF", "Off"),
        LightModeOption("STEADY_100", "Steady 100%"),
        LightModeOption("STEADY_50", "Steady 50%"),
        LightModeOption("STEADY_25", "Steady 25%"),
        LightModeOption("FLASH_100", "Flash 100%"),
        LightModeOption("SOS_100", "SOS 100%"),
        LightModeOption("CUSTOM_1", "Custom 1"),
        LightModeOption("CUSTOM_2", "Custom 2"),
        LightModeOption("CUSTOM_3", "Custom 3"),
        LightModeOption("CUSTOM_4", "Custom 4"),
        LightModeOption("CUSTOM_5", "Custom 5"),
        LightModeOption("CUSTOM_6", "Custom 6"),
        LightModeOption("CUSTOM_7", "Custom 7"),
    )
}
