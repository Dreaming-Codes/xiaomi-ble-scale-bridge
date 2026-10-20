package dev.lorenzo.s200

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@JvmInline
value class MiToken private constructor(val bytes: ByteArray) {
    val hex: String get() = bytes.toHex()

    companion object {
        fun parse(hex: String): MiToken? = hex.takeIf { it.matches(Regex("[0-9a-fA-F]{24}")) }?.let { MiToken(it.hexBytes()) }
    }
}

class SessionKeys(val devKey: ByteArray, val appKey: ByteArray, val devIv: ByteArray, val appIv: ByteArray)

object MiLogin {
    private val INFO = "mible-login-info".toByteArray()

    fun deriveKeys(token: MiToken, appRand: ByteArray, devRand: ByteArray): SessionKeys {
        val okm = hkdfSha256(token.bytes, appRand + devRand, INFO, 64)
        return SessionKeys(okm.copyOfRange(0, 16), okm.copyOfRange(16, 32), okm.copyOfRange(32, 36), okm.copyOfRange(36, 40))
    }

    fun devConfirmation(k: SessionKeys, appRand: ByteArray, devRand: ByteArray) = hmacSha256(k.devKey, devRand + appRand)
    fun appConfirmation(k: SessionKeys, appRand: ByteArray, devRand: ByteArray) = hmacSha256(k.appKey, appRand + devRand)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, len: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        var t = byteArrayOf()
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        while (out.size() < len) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t); i++
        }
        return out.toByteArray().copyOf(len)
    }
}

class MiChannel(keys: SessionKeys, asDevice: Boolean = false) {
    private val txKey = if (asDevice) keys.devKey else keys.appKey
    private val txIv = if (asDevice) keys.devIv else keys.appIv
    private val rxKey = if (asDevice) keys.appKey else keys.devKey
    private val rxIv = if (asDevice) keys.appIv else keys.devIv
    private var txCounter = 0L
    private var rxCounter = -1L

    fun seal(pt: ByteArray): ByteArray {
        val ctr = txCounter++
        return le16(ctr.toInt()) + AesCcm.encrypt(txKey, nonce(txIv, ctr), pt)
    }

    fun open(blob: ByteArray): ByteArray? {
        if (blob.size < 6) return null
        val ctr = fullCounter(rxCounter, (blob[0].toInt() and 0xFF) or ((blob[1].toInt() and 0xFF) shl 8))
        val body = blob.copyOfRange(2, blob.size - 4)
        val tag = blob.copyOfRange(blob.size - 4, blob.size)
        return AesCcm.decrypt(rxKey, nonce(rxIv, ctr), body, tag, byteArrayOf())?.also { rxCounter = ctr }
    }

    companion object {
        fun nonce(iv: ByteArray, ctr: Long): ByteArray = iv + ByteArray(4) + ByteArray(4) { (ctr ushr (8 * it)).toByte() }

        fun fullCounter(last: Long, low16: Int): Long {
            if (last < 0) return low16.toLong()
            var c = (last and 0xFFFF.inv().toLong()) or low16.toLong()
            if (c < last - 0x8000) c += 0x10000
            return c
        }
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
internal fun String.hexBytes(): ByteArray = replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
