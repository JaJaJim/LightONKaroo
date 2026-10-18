package io.github.JaJaJim.lightonkaroo.protocol

/**
 * iGPSPORT VS-series light protocol over the Nordic UART service.
 *
 * Every frame is a 20-byte transport header followed by a protobuf payload. Reverse-engineered by
 * cparfait/Bike-Light-Control (MIT) from the iGPSPORT Ride app and an HCI capture of a VS1800S;
 * the VS1200S specifics (its CUSTOM 1 low level, the 0x6B service, run-time state frames) come from
 * captures of a real VS1200S.
 */
object IgpsProtocol {
    const val UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    /** Central to light (write). */
    const val UART_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    /** Light to central (notify). */
    const val UART_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    /**
     * Advertised by the whole family. The VS1200S also advertises [UART_SERVICE], the VS1800S doesn't, so
     * scans match on this marker (or the model name) rather than on the UART service.
     */
    const val ADVERT_MARKER = "a238c112-8136-52a9-364b-d61ac015024e"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    const val HEADER_LEN = 20
    const val MAX_CHUNK = 20

    const val TYPE_DATA = 0x01
    const val TYPE_ACK = 0x02
    const val TYPE_STATE = 0x03

    private const val SERVICE_LIGHT = 106
    /** The VS1200S's second service (0x6B): only its sub-7 battery report is understood. */
    private const val SERVICE_AUX = 107
    private const val OP_WRITE = 1
    private const val OP_READ = 2

    private const val SUB_MODE_SUPPORTED = 1
    private const val SUB_MODE_CURRENT = 2
    private const val SUB_CUSTOM_MODE = 3
    private const val SUB_SMART_CONFIG = 4
    private const val SUB_REMAINING_TIME = 5
    private const val SUB_BATTERY = 6
    private const val SUB_MODE_ENABLE = 7
    private const val SUB_AUX_BATTERY = 7

    private const val F_SERVICE = 1
    private const val F_OPERATE = 2
    private const val F_SUB = 3
    private const val F_MODE_DECLARED = 6
    private const val F_CUSTOM_MODE_GET = 7
    private const val F_CUSTOM_MODE_ARG = 8
    private const val F_SMART_CONFIG_DECLARED = 9
    private const val F_SMART_CONFIG_SET = 10
    private const val F_MODE_ENABLE = 11
    private const val F_CUSTOM_MODE_MODIFY = 12
    private const val F_CURRENT_MODE = 13
    private const val F_REMAINING_TIME = 14
    private const val F_BATTERY = 15

    fun readSupportedModes(): ByteArray = message(SUB_MODE_SUPPORTED, OP_READ)
    fun readCurrentMode(): ByteArray = message(SUB_MODE_CURRENT, OP_READ)
    fun readRemainingTime(): ByteArray = message(SUB_REMAINING_TIME, OP_READ)
    fun readBattery(): ByteArray = message(SUB_BATTERY, OP_READ)
    fun readSmartConfigs(): ByteArray = message(SUB_SMART_CONFIG, OP_READ)

    /** Sets smart config [id] ([SmartConfig]) to [status]. Status 0 (off) is the protobuf default, so it is omitted. */
    fun setSmartConfig(id: Int, status: Int): ByteArray {
        var inner = ProtoWire.varintField(1, id.toLong())
        if (status != SmartConfig.OFF) inner += ProtoWire.varintField(2, status.toLong())
        return message(SUB_SMART_CONFIG, OP_WRITE, ProtoWire.messageField(F_SMART_CONFIG_SET, inner))
    }

    /** Mode 0 (off) is the protobuf default, so its inner message is sent empty. */
    fun setMode(mode: Int): ByteArray {
        val inner = if (mode == LightModes.OFF) ByteArray(0) else ProtoWire.varintField(1, mode.toLong())
        return message(SUB_MODE_CURRENT, OP_WRITE, ProtoWire.messageField(F_CURRENT_MODE, inner))
    }

    /** Enables or disables a mode in the light's list: flash and custom modes ship disabled. */
    fun setModeEnabled(mode: Int, enabled: Boolean): ByteArray {
        var inner = ProtoWire.varintField(1, mode.toLong())
        if (enabled) inner += ProtoWire.varintField(2, 1)
        return message(SUB_MODE_ENABLE, OP_WRITE, ProtoWire.messageField(F_MODE_ENABLE, inner))
    }

    /** Asks for custom slot [mode]'s config; the light answers with field 8 ([LightUpdate.customMode]). */
    fun readCustomMode(mode: Int): ByteArray {
        require(mode in CustomMode.SLOTS) { "not a custom mode: $mode" }
        return message(SUB_CUSTOM_MODE, OP_READ, ProtoWire.messageField(F_CUSTOM_MODE_GET, ProtoWire.varintField(1, mode.toLong())))
    }

    /**
     * One change to custom slot [mode] (blt_cus_mode_modify). Zero values are left out, like proto3 and the
     * iGPSPORT app do. Values outside the app's ranges are rejected: the light itself stores anything.
     */
    fun modifyCustomMode(mode: Int, change: CustomChange): ByteArray {
        require(mode in CustomMode.SLOTS) { "not a custom mode: $mode" }
        require(change.subtype in CustomMode.SUBTYPES) { "unknown pattern: ${change.subtype}" }
        val body = when (change) {
            is CustomChange.Pattern -> ByteArray(0)
            is CustomChange.Brightness -> {
                require(change.pct in CustomMode.PCT) { "brightness out of range: ${change.pct}" }
                ProtoWire.messageField(3, optionalVarint(1, change.lightNum) + optionalVarint(2, change.pct))
            }
            is CustomChange.Cycle -> {
                require(change.seconds in CustomMode.CYCLE_SECONDS) { "cycle out of range: ${change.seconds}" }
                ProtoWire.messageField(4, optionalVarint(1, change.seconds))
            }
            is CustomChange.Ratio -> {
                require(change.percent in CustomMode.RATIO_PERCENT) { "ratio out of range: ${change.percent}" }
                ProtoWire.messageField(5, optionalVarint(1, change.percent))
            }
        }
        val inner = ProtoWire.varintField(1, mode.toLong()) + optionalVarint(2, change.subtype) + body
        return message(SUB_CUSTOM_MODE, OP_WRITE, ProtoWire.messageField(F_CUSTOM_MODE_MODIFY, inner))
    }

    /** proto3 leaves a zero value out of the frame. */
    private fun optionalVarint(field: Int, value: Int): ByteArray =
        if (value == 0) ByteArray(0) else ProtoWire.varintField(field, value.toLong())

    private fun message(sub: Int, op: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val payload = ProtoWire.varintField(F_SERVICE, SERVICE_LIGHT.toLong()) +
            ProtoWire.varintField(F_OPERATE, op.toLong()) +
            ProtoWire.varintField(F_SUB, sub.toLong()) +
            body
        require(payload.size <= 0xFF) { "payload too long: ${payload.size}" }
        val header = ByteArray(HEADER_LEN) { 0xFF.toByte() }
        header[0] = TYPE_DATA.toByte()
        header[1] = SERVICE_LIGHT.toByte()
        header[2] = sub.toByte()
        header[4] = op.toByte()
        header[7] = 0
        header[8] = payload.size.toByte()
        header[9] = Crc8.maxim(payload).toByte()
        header[10] = 1
        header[19] = Crc8.maxim(header, 0, HEADER_LEN - 1).toByte()
        return header + payload
    }

    fun chunks(frame: ByteArray, size: Int = MAX_CHUNK): List<ByteArray> =
        (frame.indices step size).map { frame.copyOfRange(it, minOf(it + size, frame.size)) }

    fun headerValid(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_LEN &&
            Crc8.maxim(bytes, 0, HEADER_LEN - 1) == (bytes[HEADER_LEN - 1].toInt() and 0xFF)

    /** Length of the frame starting at bytes[0]. Only data frames carry a payload; byte 8 is not a length otherwise. */
    fun expectedLength(bytes: ByteArray): Int =
        if (bytes.size >= HEADER_LEN && bytes[0].toInt() == TYPE_DATA) {
            HEADER_LEN + (bytes[8].toInt() and 0xFF)
        } else {
            HEADER_LEN
        }

    fun parseFrame(bytes: ByteArray): LightUpdate? {
        if (!headerValid(bytes)) return null
        val service = bytes[1].toInt() and 0xFF
        // The VS1200S also sends type-03 frames for service 0x6B: only the sub-7 one, a battery reading, is understood.
        if (service == SERVICE_AUX && bytes[0].toInt() == TYPE_STATE && (bytes[2].toInt() and 0xFF) == SUB_AUX_BATTERY) {
            return LightUpdate(auxBatteryPercent = bytes[7].toInt() and 0xFF)
        }
        if (service != SERVICE_LIGHT) return null
        return when (bytes[0].toInt()) {
            TYPE_STATE -> parseState(bytes)
            TYPE_DATA -> parseData(bytes)
            else -> null
        }
    }

    private fun parseState(bytes: ByteArray): LightUpdate? = when (bytes[2].toInt() and 0xFF) {
        SUB_MODE_CURRENT -> LightUpdate(mode = bytes[7].toInt() and 0xFF)
        // No run time (bytes 11..14 all FF) means auto light has switched the output off.
        SUB_REMAINING_TIME -> if ((11..14).all { bytes[it] == 0xFF.toByte() }) {
            LightUpdate(outputOff = true)
        } else {
            LightUpdate(
                remainingMinutes = (bytes[11].toInt() and 0xFF) or
                    ((bytes[12].toInt() and 0xFF) shl 8) or
                    ((bytes[13].toInt() and 0xFF) shl 16),
                outputOff = false,
            )
        }
        else -> null
    }

    private fun parseData(bytes: ByteArray): LightUpdate? {
        val length = bytes[8].toInt() and 0xFF
        if (bytes.size < HEADER_LEN + length) return null
        val payload = bytes.copyOfRange(HEADER_LEN, HEADER_LEN + length)
        if (Crc8.maxim(payload) != (bytes[9].toInt() and 0xFF)) return null
        val fields = ProtoWire.parse(payload) ?: return null
        val declared = entries(fields, F_MODE_DECLARED) { e ->
            val mode = e.varint(1)?.toInt() ?: return@entries null
            mode to ((e.varint(3) ?: 0L) != 0L)
        }
        // A missing status is the protobuf default: off.
        val smartConfigs = entries(fields, F_SMART_CONFIG_DECLARED) { e ->
            val id = e.varint(1)?.toInt() ?: return@entries null
            id to (e.varint(2)?.toInt() ?: SmartConfig.OFF)
        }
        return LightUpdate(
            // An empty inner message means the protobuf default: off.
            mode = fields.message(F_CURRENT_MODE)?.let { ProtoWire.parse(it)?.varint(1)?.toInt() ?: LightModes.OFF },
            batteryPercent = fields.message(F_BATTERY)?.let { ProtoWire.parse(it)?.varint(13)?.toInt() },
            remainingMinutes = fields.message(F_REMAINING_TIME)?.let { ProtoWire.parse(it)?.varint(1)?.toInt() },
            declaredModes = declared,
            smartConfigs = smartConfigs,
            customMode = fields.message(F_CUSTOM_MODE_ARG)?.let(::parseCustomMode),
        )
    }

    /** blt_cus_mode_arg; null without a mode (an empty reply). Missing values are the proto3 default, 0. */
    private fun parseCustomMode(bytes: ByteArray): CustomModeConfig? {
        val f = ProtoWire.parse(bytes) ?: return null
        val mode = f.varint(1)?.toInt() ?: return null
        val patterns = f.messages(3).mapNotNull { raw ->
            val p = ProtoWire.parse(raw) ?: return@mapNotNull null
            CustomPattern(
                subtype = p.varint(1)?.toInt() ?: CustomMode.STEADY,
                lights = p.messages(2).mapNotNull { l ->
                    ProtoWire.parse(l)?.let { CustomLight(it.varint(1)?.toInt() ?: 0, it.varint(2)?.toInt() ?: 0) }
                },
                cycleSeconds = p.message(3)?.let { ProtoWire.parse(it)?.varint(1)?.toInt() ?: 0 },
                ratioPercent = p.message(4)?.let { ProtoWire.parse(it)?.varint(1)?.toInt() ?: 0 },
            )
        }
        return CustomModeConfig(mode, f.varint(2)?.toInt() ?: CustomMode.STEADY, patterns)
    }

    /** Repeated message [field] as an ordered map built by [entry]; null when the frame has no such field. */
    private fun <K, V> entries(
        fields: Map<Int, List<ProtoWire.Value>>,
        field: Int,
        entry: (Map<Int, List<ProtoWire.Value>>) -> Pair<K, V>?,
    ): Map<K, V>? {
        if (field !in fields) return null
        return fields.messages(field).mapNotNull { bytes -> ProtoWire.parse(bytes)?.let(entry) }.toMap(LinkedHashMap())
    }
}


