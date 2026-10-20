package dev.lorenzo.s200

sealed interface SpecValue {
    data class Num(val type: Int, val value: Long) : SpecValue
    data class Bool(val value: Boolean) : SpecValue
    data class Real(val value: Float) : SpecValue
    data class Str(val value: String) : SpecValue

    companion object {
        const val BOOL = 0; const val U8 = 1; const val I8 = 2; const val U16 = 3; const val I16 = 4
        const val U32 = 5; const val I32 = 6; const val U64 = 7; const val I64 = 8; const val FLOAT = 9; const val STRING = 10
    }
}

data class SpecMsg(val op: Int, val tid: Int, val siid: Int, val iid: Int, val code: Int?, val args: Map<Int, SpecValue>)

object MiotSpec {
    const val VERSION = 2
    const val ACTION = 0x05
    const val ACTION_RSP = 0x06
    const val EVENT = 0x07

    fun action(tid: Int, siid: Int, aiid: Int, args: List<Pair<Int, SpecValue>> = emptyList()): ByteArray {
        val body = byteArrayOf(siid.toByte()) + le16(aiid) + byteArrayOf(args.size.toByte()) +
            args.fold(byteArrayOf()) { acc, (piid, v) -> acc + le16(piid) + encodeValue(v) }
        return frame(tid, ACTION, body)
    }

    fun frame(tid: Int, op: Int, body: ByteArray): ByteArray {
        val len = 5 + body.size
        require(len <= 0x0FFF) { "spec frame too long" }
        return le16((VERSION shl 12) or len) + le16(tid) + byteArrayOf(op.toByte()) + body
    }

    fun encodeValue(v: SpecValue): ByteArray {
        val (type, raw) = when (v) {
            is SpecValue.Bool -> SpecValue.BOOL to byteArrayOf(if (v.value) 1 else 0)
            is SpecValue.Real -> SpecValue.FLOAT to java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(v.value).array()
            is SpecValue.Str -> SpecValue.STRING to v.value.toByteArray()
            is SpecValue.Num -> v.type to ByteArray(width(v.type)) { (v.value ushr (8 * it)).toByte() }
        }
        require(raw.size <= 0x0FFF)
        return le16((type shl 12) or raw.size) + raw
    }

    private fun width(type: Int) = when (type) {
        SpecValue.U8, SpecValue.I8 -> 1
        SpecValue.U16, SpecValue.I16 -> 2
        SpecValue.U32, SpecValue.I32 -> 4
        SpecValue.U64, SpecValue.I64 -> 8
        else -> throw IllegalArgumentException("type $type is not numeric")
    }

    fun decode(pt: ByteArray): SpecMsg? {
        if (pt.size < 5) return null
        val c = Cur(pt)
        val hdr = c.u16()
        if (hdr ushr 12 != VERSION) return null
        val tid = c.u16()
        val op = c.u8()
        return runCatching {
            when (op) {
                ACTION, EVENT -> SpecMsg(op, tid, c.u8(), c.u16(), null, args(c)).takeIf { c.done }
                ACTION_RSP -> {
                    val siid = c.u8(); val aiid = c.u16(); val mark = c.i
                    runCatching { SpecMsg(op, tid, siid, aiid, c.i16(), args(c)).takeIf { c.done } }.getOrNull()
                        ?: run { c.i = mark; SpecMsg(op, tid, siid, aiid, null, args(c)).takeIf { c.done } }
                }
                else -> SpecMsg(op, tid, -1, -1, null, emptyMap())
            }
        }.getOrNull()
    }

    private fun args(c: Cur): Map<Int, SpecValue> = (0 until c.u8()).associate { c.u16() to value(c) }

    private fun value(c: Cur): SpecValue {
        val vt = c.u16()
        val type = vt ushr 12
        val raw = c.take(vt and 0x0FFF)
        fun unsigned() = raw.indices.fold(0L) { acc, i -> acc or ((raw[i].toLong() and 0xFF) shl (8 * i)) }
        fun signed(): Long { val u = unsigned(); val bits = raw.size * 8; return if (bits < 64 && u and (1L shl (bits - 1)) != 0L) u - (1L shl bits) else u }
        return when (type) {
            SpecValue.BOOL -> SpecValue.Bool(raw.any { it.toInt() != 0 })
            SpecValue.U8, SpecValue.U16, SpecValue.U32, SpecValue.U64 -> SpecValue.Num(type, unsigned())
            SpecValue.I8, SpecValue.I16, SpecValue.I32, SpecValue.I64 -> SpecValue.Num(type, signed())
            SpecValue.FLOAT -> SpecValue.Real(java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).float)
            SpecValue.STRING -> SpecValue.Str(String(raw).trimEnd('\u0000'))
            else -> throw IllegalArgumentException("value type $type")
        }
    }

    private class Cur(val b: ByteArray) {
        var i = 0
        val done get() = i == b.size
        fun take(n: Int): ByteArray { require(i + n <= b.size); return b.copyOfRange(i, i + n).also { i += n } }
        fun u8() = take(1)[0].toInt() and 0xFF
        fun u16() = take(2).let { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }
        fun i16() = u16().toShort().toInt()
    }
}

data class OfflineRecord(val idx: Int, val miid: Long, val duid: Int, val weightKg: Double, val status: Int, val epochSec: Long) {
    companion object {
        fun parseAll(s: String): List<OfflineRecord> = s.split('_').mapNotNull(::parse)

        fun parse(s: String): OfflineRecord? {
            val f = s.trim().split(',').map { it.trim() }
            if (f.size < 6) return null
            return runCatching { OfflineRecord(f[0].toInt(), f[1].toLong(), f[2].toInt(), f[3].toLong() / 100.0, f[4].toInt(), f[5].toLong()) }.getOrNull()
        }

        fun idxString(idxs: List<Int>) = idxs.joinToString(",")
    }
}
