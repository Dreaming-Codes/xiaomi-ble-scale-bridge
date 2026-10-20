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
    @Test fun malformedConfigRejected() {
        assertNull(BindKey.parse("653b"))
        assertNull(ScaleMac.parse("D0:7B:6F:27:D7"))
    }
}
