package com.lin.hippyagent.core.cron

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object IntRangeSerializer : KSerializer<IntRange> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IntRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IntRange) {
        encoder.encodeString("${value.first}-${value.last}")
    }

    override fun deserialize(decoder: Decoder): IntRange {
        val raw = decoder.decodeString()
        val parts = raw.split("-", limit = 2)
        return if (parts.size == 2) {
            val lo = parts[0].toIntOrNull() ?: 0
            val hi = parts[1].toIntOrNull() ?: 0
            lo..hi
        } else {
            val v = raw.toIntOrNull() ?: 0
            v..v
        }
    }
}

object NullableIntRangeSerializer : KSerializer<IntRange?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableIntRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IntRange?) {
        if (value == null) encoder.encodeString("")
        else encoder.encodeString("${value.first}-${value.last}")
    }

    override fun deserialize(decoder: Decoder): IntRange? {
        val raw = decoder.decodeString()
        if (raw.isEmpty()) return null
        val parts = raw.split("-", limit = 2)
        return if (parts.size == 2) {
            val lo = parts[0].toIntOrNull() ?: 0
            val hi = parts[1].toIntOrNull() ?: 0
            lo..hi
        } else {
            val v = raw.toIntOrNull() ?: 0
            v..v
        }
    }
}
