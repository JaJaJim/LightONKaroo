package io.github.JaJaJim.lightonkaroo.ble

object BleRemoteProtocol {
    const val HID_SERVICE_UUID = "00001812-0000-1000-8000-00805f9b34fb"
    const val REPORT_CHAR_UUID = "00002a4d-0000-1000-8000-00805f9b34fb"

    val SUPPORTED_PREFIXES = setOf(
        "SHUTTER", "REMOTE", "SELFIE", "BUTTON", "KEYBOARD", "AB SHUTTER", "R1", "MEDIA", "BT", "CAMERA",
    )

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it) }
}
