package dev.lorenzo.s200

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@JvmInline
value class BindKey private constructor(val bytes: ByteArray) {
    companion object {
        fun parse(hex: String): BindKey? = hex.takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }?.let { BindKey(it.chunked(2).map { b -> b.toInt(16).toByte() }.toByteArray()) }
    }
}

@JvmInline
value class ScaleMac private constructor(val address: String) {
    val nonceBytes: ByteArray get() = address.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()

    companion object {
        fun parse(s: String): ScaleMac? = s.uppercase().takeIf { it.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }?.let(::ScaleMac)
    }
}

data class S200Measurement(val profileId: Int, val weightKg: Double, val timestampEpochSec: Long, val frameCounter: Int)

object S200Parser {
    val PRODUCT_IDS = setOf(0x45C9, 0x4C04, 0x4DCB)
    const val FRAME_CTRL = 0x5948
    const val OBJ_WEIGHT = 0x4E16

    sealed interface Result {
        data class Ok(val m: S200Measurement) : Result
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
        return Result.Ok(S200Measurement(profile, weight / 100.0, ts, d[4].toInt() and 0xFF))
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[o + i].toLong() and 0xFF) shl (8 * i)) }
}

internal object AesCcm {
    fun decrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray? {
        val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        val l = 15 - nonce.size
        fun ctr(i: Int) = ByteArray(16).also { a ->
            a[0] = (l - 1).toByte(); nonce.copyInto(a, 1)
            for (k in 0 until l) a[15 - k] = (i ushr (8 * k)).toByte()
        }
        val pt = ByteArray(ct.size)
        for (blk in 0 until (ct.size + 15) / 16) {
            val s = aes.doFinal(ctr(blk + 1))
            for (j in 0 until 16) { val idx = blk * 16 + j; if (idx < ct.size) pt[idx] = (ct[idx].toInt() xor s[j].toInt()).toByte() }
        }
        val b0 = ByteArray(16)
        b0[0] = ((if (aad.isNotEmpty()) 0x40 else 0) or (((tag.size - 2) / 2) shl 3) or (l - 1)).toByte()
        nonce.copyInto(b0, 1)
        for (k in 0 until l) b0[15 - k] = (pt.size ushr (8 * k)).toByte()
        var x = aes.doFinal(b0)
        fun absorb(data: ByteArray) {
            var o = 0
            while (o < data.size) {
                val blk = ByteArray(16); data.copyInto(blk, 0, o, minOf(o + 16, data.size))
                for (j in 0 until 16) blk[j] = (blk[j].toInt() xor x[j].toInt()).toByte()
                x = aes.doFinal(blk); o += 16
            }
        }
        if (aad.isNotEmpty()) absorb(byteArrayOf((aad.size ushr 8).toByte(), aad.size.toByte()) + aad)
        absorb(pt)
        val s0 = aes.doFinal(ctr(0))
        var diff = 0
        for (j in tag.indices) diff = diff or ((x[j].toInt() xor s0[j].toInt()) xor tag[j].toInt())
        return if (diff == 0) pt else null
    }
}
