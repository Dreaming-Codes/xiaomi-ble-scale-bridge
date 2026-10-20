package dev.lorenzo.s200

import kotlin.test.*

class S200ParserTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val adv = hex("48 59 04 4c 01 9a 80 a2 75 93 90 10 f0 ab c4 fa dc 06 00 00 3d 29 c0 44")
    private val key = BindKey.parse("653b1b10e1cb35e4ac5e60fa45f3bf29")!!
    private val mac = ScaleMac.parse("d0:7b:6f:27:d7:29")!!

    @Test fun xiaomiBleVector() {
        val r = S200Parser.parse(adv, mac, key)
        assertIs<S200Parser.Result.Ok>(r)
        assertEquals(62.25, r.reading.kg, 1e-9)
        assertEquals(1, r.reading.profile)
        assertEquals(0x69111b8aL, r.reading.epochSec)
    }
    @Test fun wrongKeyRejected() {
        val bad = BindKey.parse("753b1b10e1cb35e4ac5e60fa45f3bf29")!!
        assertIs<S200Parser.Result.Ignored>(S200Parser.parse(adv, mac, bad))
    }
    @Test fun tamperedRejected() {
        val t = adv.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertIs<S200Parser.Result.Ignored>(S200Parser.parse(t, mac, key))
    }
    private fun advert(pid: Int): ByteArray {
        val head = byteArrayOf(0x48, 0x59, pid.toByte(), (pid shr 8).toByte(), 0x07)
        val ext = byteArrayOf(0x01, 0x02, 0x03)
        val pt = hex("16 4e 09 02 52 1c 00 00 00 5e d0 69")
        val sealed = AesCcm.encrypt(key.bytes, mac.nonceBytes + head.copyOfRange(2, 5) + ext, pt, aad = byteArrayOf(0x11))
        return head + sealed.copyOfRange(0, pt.size) + ext + sealed.copyOfRange(pt.size, sealed.size)
    }

    @Test fun everyS200VariantDecodes() {
        for (pid in listOf(0x45C9, 0x4C04, 0x4D36, 0x4DCB, 0x4DCC)) {
            val r = S200Parser.parse(advert(pid), mac, key)
            assertIs<S200Parser.Result.Ok>(r, "product %04x".format(pid))
            assertEquals(Reading(0x69d05e00L, 72.5, 2), r.reading)
        }
    }
    @Test fun otherScalesIgnoredEvenWithAValidMic() {
        for (pid in listOf(0x30D9, 0x4B05, 0x51E2)) assertIs<S200Parser.Result.Ignored>(S200Parser.parse(advert(pid), mac, key))
    }
    @Test fun malformedConfigRejected() {
        assertNull(BindKey.parse("653b"))
        assertNull(ScaleMac.parse("D0:7B:6F:27:D7"))
    }
}
