package dev.lorenzo.s200.cloud

import java.security.MessageDigest
import java.util.Base64

internal object MiCloudCrypto {
    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun nonce(random: ByteArray, epochMillis: Long): String {
        val minutes = (epochMillis / 60000).toInt()
        return b64e.encodeToString(random + byteArrayOf((minutes ushr 24).toByte(), (minutes ushr 16).toByte(), (minutes ushr 8).toByte(), minutes.toByte()))
    }

    fun signedNonce(ssecurity: String, nonce: String): String =
        b64e.encodeToString(sha256(b64d.decode(ssecurity) + b64d.decode(nonce)))

    fun encryptParams(url: String, signedNonce: String, nonce: String, ssecurity: String, params: Map<String, String>): Map<String, String> {
        val withHash = params + ("rc4_hash__" to signature(url, signedNonce, params))
        val encrypted = withHash.mapValues { (_, v) -> b64e.encodeToString(rc4(signedNonce, v.toByteArray())) }
        return encrypted + mapOf("signature" to signature(url, signedNonce, encrypted), "ssecurity" to ssecurity, "_nonce" to nonce)
    }

    fun decrypt(signedNonce: String, body: String): String = String(rc4(signedNonce, b64d.decode(body.trim())))

    private fun signature(url: String, signedNonce: String, params: Map<String, String>): String {
        val path = url.substringAfter("com").replace("/app/", "/")
        val text = (listOf("POST", path) + params.map { (k, v) -> "$k=$v" } + signedNonce).joinToString("&")
        return b64e.encodeToString(MessageDigest.getInstance("SHA-1").digest(text.toByteArray()))
    }

    private fun rc4(keyB64: String, data: ByteArray): ByteArray {
        val key = b64d.decode(keyB64)
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            s[i] = s[j].also { s[j] = s[i] }
        }
        var i = 0
        j = 0
        fun next(): Int {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            s[i] = s[j].also { s[j] = s[i] }
            return s[(s[i] + s[j]) and 0xFF]
        }
        repeat(1024) { next() }
        return ByteArray(data.size) { (data[it].toInt() xor next()).toByte() }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
}
