package dev.lorenzo.s200

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object AesCcm {
    fun encrypt(key: ByteArray, nonce: ByteArray, pt: ByteArray, tagLen: Int = 4, aad: ByteArray = byteArrayOf()): ByteArray {
        val aes = aes(key)
        val tag = cbcMac(aes, nonce, pt, aad, tagLen)
        return xorStream(aes, nonce, pt) + tag
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray, tag: ByteArray, aad: ByteArray): ByteArray? {
        val aes = aes(key)
        val pt = xorStream(aes, nonce, ct)
        val expected = cbcMac(aes, nonce, pt, aad, tag.size)
        var diff = 0
        for (j in tag.indices) diff = diff or (expected[j].toInt() xor tag[j].toInt())
        return if (diff == 0) pt else null
    }

    private fun aes(key: ByteArray) = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    private fun ctrBlock(nonce: ByteArray, i: Int) = ByteArray(16).also { a ->
        val l = 15 - nonce.size
        a[0] = (l - 1).toByte(); nonce.copyInto(a, 1)
        for (k in 0 until l) a[15 - k] = (i ushr (8 * k)).toByte()
    }

    private fun xorStream(aes: Cipher, nonce: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        for (blk in 0 until (input.size + 15) / 16) {
            val s = aes.doFinal(ctrBlock(nonce, blk + 1))
            for (j in 0 until 16) { val idx = blk * 16 + j; if (idx < input.size) out[idx] = (input[idx].toInt() xor s[j].toInt()).toByte() }
        }
        return out
    }

    private fun cbcMac(aes: Cipher, nonce: ByteArray, pt: ByteArray, aad: ByteArray, tagLen: Int): ByteArray {
        val l = 15 - nonce.size
        val b0 = ByteArray(16)
        b0[0] = ((if (aad.isNotEmpty()) 0x40 else 0) or (((tagLen - 2) / 2) shl 3) or (l - 1)).toByte()
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
        val s0 = aes.doFinal(ctrBlock(nonce, 0))
        return ByteArray(tagLen) { (x[it].toInt() xor s0[it].toInt()).toByte() }
    }
}
