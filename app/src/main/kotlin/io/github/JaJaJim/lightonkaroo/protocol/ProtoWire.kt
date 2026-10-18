package io.github.JaJaJim.lightonkaroo.protocol

import java.io.ByteArrayOutputStream

/** Minimal protobuf wire format: varint (type 0) and length-delimited (type 2) only. */
object ProtoWire {
    sealed interface Value {
        data class Varint(val value: Long) : Value
        class Bytes(val bytes: ByteArray) : Value
    }

    fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            if (v and 0x7FL.inv() == 0L) {
                out.write(v.toInt())
                return out.toByteArray()
            }
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
    }

    fun varintField(field: Int, value: Long): ByteArray = varint(field.toLong() shl 3) + varint(value)

    fun messageField(field: Int, inner: ByteArray): ByteArray =
        varint((field.toLong() shl 3) or 2L) + varint(inner.size.toLong()) + inner

    /** One message level -> field number to values, repeated fields in order; null if malformed. */
    fun parse(bytes: ByteArray): Map<Int, List<Value>>? {
        val result = LinkedHashMap<Int, MutableList<Value>>()
        var pos = 0

        fun readVarint(): Long? {
            var shift = 0
            var acc = 0L
            while (pos < bytes.size && shift < 64) {
                val b = bytes[pos++].toInt() and 0xFF
                acc = acc or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return acc
                shift += 7
            }
            return null
        }

        while (pos < bytes.size) {
            val key = readVarint() ?: return null
            val field = (key ushr 3).toInt()
            when ((key and 7L).toInt()) {
                0 -> {
                    val value = readVarint() ?: return null
                    result.getOrPut(field) { mutableListOf() }.add(Value.Varint(value))
                }
                2 -> {
                    // Compared as a Long against the bytes left: an Int cast or pos + len could wrap.
                    val rawLen = readVarint() ?: return null
                    if (rawLen < 0 || rawLen > bytes.size - pos) return null
                    val len = rawLen.toInt()
                    result.getOrPut(field) { mutableListOf() }.add(Value.Bytes(bytes.copyOfRange(pos, pos + len)))
                    pos += len
                }
                else -> return null
            }
        }
        return result
    }
}

fun Map<Int, List<ProtoWire.Value>>.varint(field: Int): Long? =
    (this[field]?.firstOrNull() as? ProtoWire.Value.Varint)?.value

fun Map<Int, List<ProtoWire.Value>>.message(field: Int): ByteArray? =
    (this[field]?.firstOrNull() as? ProtoWire.Value.Bytes)?.bytes

fun Map<Int, List<ProtoWire.Value>>.messages(field: Int): List<ByteArray> =
    this[field].orEmpty().filterIsInstance<ProtoWire.Value.Bytes>().map { it.bytes }


