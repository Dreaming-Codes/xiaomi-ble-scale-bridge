package dev.lorenzo.s200

@JvmInline
value class BindKey private constructor(val bytes: ByteArray) {
    val hex: String get() = bytes.toHex()

    companion object {
        fun parse(hex: String): BindKey? = hex.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }?.let { BindKey(it.hexBytes()) }
    }
}

@JvmInline
value class ScaleMac private constructor(val address: String) {
    val nonceBytes: ByteArray get() = address.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()

    companion object {
        fun parse(s: String): ScaleMac? = s.uppercase().takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }?.let(::ScaleMac)
    }
}

data class Reading(val epochSec: Long, val kg: Double, val profile: Int)

object S200Parser {
    val PRODUCT_IDS = setOf(0x45C9, 0x4C04, 0x4DCB)
    const val FRAME_CTRL = 0x5948
    const val OBJ_WEIGHT = 0x4E16

    sealed interface Result {
        data class Ok(val reading: Reading) : Result
        data class Ignored(val reason: String) : Result
    }

    fun parse(d: ByteArray, mac: ScaleMac, key: BindKey): Result {
        if (d.size < 5 + 3 + 7) return Result.Ignored("too short")
        val fc = u16(d, 0)
        if (fc != FRAME_CTRL) return Result.Ignored("frame ctrl %04x".format(fc))
        val pid = u16(d, 2)
        if (pid !in PRODUCT_IDS) return Result.Ignored("product %04x".format(pid))
        val nonce = mac.nonceBytes + d.copyOfRange(2, 5) + d.copyOfRange(d.size - 7, d.size - 4)
        val ct = d.copyOfRange(5, d.size - 7)
        val mic = d.copyOfRange(d.size - 4, d.size)
        val pt = AesCcm.decrypt(key.bytes, nonce, ct, mic, byteArrayOf(0x11)) ?: return Result.Ignored("MIC mismatch (wrong key/MAC?)")
        if (pt.size < 3) return Result.Ignored("empty payload")
        val obj = u16(pt, 0); val len = pt[2].toInt() and 0xFF
        if (obj != OBJ_WEIGHT || len != 9 || pt.size < 12) return Result.Ignored("object %04x len %d".format(obj, len))
        val profile = pt[3].toInt() and 0xFF
        val weight = u32(pt, 4)
        val ts = u32(pt, 8)
        if (weight == 0L) return Result.Ignored("weight 0")
        return Result.Ok(Reading(ts, weight / 100.0, profile))
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
}
