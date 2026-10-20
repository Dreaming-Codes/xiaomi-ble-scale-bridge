package dev.lorenzo.s200.cloud

import kotlin.test.Test
import kotlin.test.assertEquals

class MiCloudCryptoTest {
    private val ssecurity = "AAECAwQFBgcICQoLDA0ODw=="
    private val nonce = "ZGVmZ2hpamtsbW5v"
    private val signed = "+JfC+ScjV+jXa9PgCRtBpAfTWAAwPFTV0LabBt47GcA="
    private val url = "https://de.api.io.mi.com/app/v2/device/blt_get_beaconkey"
    private val plain = """{"code":0,"result":{"beaconkey":"00112233445566778899aabbccddeeff"}}"""

    @Test fun signedNonceMatchesExtractor() = assertEquals(signed, MiCloudCrypto.signedNonce(ssecurity, nonce))

    @Test fun nonceMatchesExtractor() =
        assertEquals("AQIDBAUGBwgBx9zo", MiCloudCrypto.nonce(ByteArray(8) { (it + 1).toByte() }, 1792525964873))

    @Test fun encryptedParamsMatchExtractor() = assertEquals(
        listOf(
            "data" to "cBCJvoKFz2dVTSd4BfsOWK/6tKVQRTaiNVDcdtY=",
            "rc4_hash__" to "T1iPuI/mzTRBQzAyBr5DRJL0190WTx+/PV2veg==",
            "signature" to "DBEN/ZYNPBkiQlVIwG3wKa9vquE=",
            "ssecurity" to ssecurity,
            "_nonce" to nonce,
        ),
        MiCloudCrypto.encryptParams(url, signed, nonce, ssecurity, mapOf("data" to """{"did":"blt.4.test","pdid":1}""")).toList(),
    )

    @Test fun decryptsExtractorCiphertext() = assertEquals(
        plain,
        MiCloudCrypto.decrypt(signed, "cBCOuILC138HDXEkVKYPUaisrPJQVzeqMh2ILM7QaHtOhWLaU1eXkl08/lAg8+eaRFG368uAFKDPGAyDWMYX1xJE3oM="),
    )
}
