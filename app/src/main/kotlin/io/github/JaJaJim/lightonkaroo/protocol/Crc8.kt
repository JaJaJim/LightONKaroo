package io.github.JaJaJim.lightonkaroo.protocol

/** CRC-8/MAXIM (Dallas 1-Wire): reflected polynomial 0x8C, init 0x00, no final XOR. */
object Crc8 {
    fun maxim(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8C else crc ushr 1
            }
        }
        return crc
    }
}


