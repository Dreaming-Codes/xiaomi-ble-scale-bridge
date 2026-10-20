package dev.lorenzo.s200

import kotlin.test.*

class MiBleTest {
    private val hotoToken = MiToken.parse("728188d09c8b2d304cd87bf6")!!
    private val hotoApp = "1b864b0d0e227c243f1d8fe5b105d48f".hexBytes()
    private val hotoDev = "41b8c8027dce475f2f51477628c3cc56".hexBytes()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun hotoKeysAndConfirmations() {
        val k = MiLogin.deriveKeys(hotoToken, hotoApp, hotoDev)
        assertEquals("0d871ae201051ed666676cabda91b7aa", k.devKey.hex())
        assertEquals("f354d2358cb843546cbba496ef9a7e08", k.appKey.hex())
        assertEquals("e978f66d", k.devIv.hex())
        assertEquals("a5496fe4", k.appIv.hex())
        assertEquals("ae040d78df2edfb7d051404995639bea1b43ef316340d78c48473c375ec7d183", MiLogin.devConfirmation(k, hotoApp, hotoDev).hex())
        assertEquals("21d026952441fdc4292375c37be021ea35bc0eec2b138f4ee3a88f3501c9daae", MiLogin.appConfirmation(k, hotoApp, hotoDev).hex())
    }

    @Test fun hotoChannelFrames() {
        val k = MiLogin.deriveKeys(hotoToken, hotoApp, hotoDev)
        assertEquals("0000c97aad8216ab371108f06a8c", MiChannel(k).seal("55aa0004010004fe".hexBytes()).hex())
        val rx = MiChannel(k)
        listOf(
            "0200" + "5470549031741d791a982c" to "55aa01020002fe",
            "0800" + "5e1af132be137bd70e791a3d94df" to "55aa01070347000051fe",
            "0c00" + "9c2fd5991c08c2151cf693c574ac" to "55aa0108030000000bfe",
        ).forEach { (wire, pt) -> assertEquals(pt, rx.open(wire.hexBytes())?.hex()) }
        assertNull(MiChannel(k).open(("0200" + "5470549031741d791a982d").hexBytes()))
    }

    @Test fun counterRollover() {
        assertEquals(5, MiChannel.fullCounter(-1, 5))
        assertEquals(0x10001, MiChannel.fullCounter(0xFFFE, 1))
        assertEquals(0x10002, MiChannel.fullCounter(0x10001, 2))
    }

    @Test fun tokenParsing() {
        assertNotNull(MiToken.parse("00112233445566778899AABB"))
        assertNull(MiToken.parse("0011"))
        assertNull(MiToken.parse("00112233445566778899aabbcc"))
    }
}

class MiotSpecTest {
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test fun requestOfflineFrame() {
        assertEquals("0920020005060100" + "00", MiotSpec.action(2, 6, 1).hex())
    }

    @Test fun confirmOfflineFrame() {
        val f = MiotSpec.action(3, 6, 2, listOf(2 to SpecValue.Str("4,5")))
        assertEquals("1020030005060200" + "01" + "0200" + "03a0" + "342c35", f.hex())
    }

    @Test fun actionResponseWithAndWithoutCode() {
        val with = MiotSpec.decode(MiotSpec.frame(2, 6, "06010000000103000110 02".hexBytes()))!!
        assertEquals(0, with.code); assertEquals(SpecValue.Num(SpecValue.U8, 2), with.args[3])
        val bare = MiotSpec.decode(MiotSpec.frame(3, 6, "06020000".hexBytes()))!!
        assertNull(bare.code); assertTrue(bare.args.isEmpty())
        val err = MiotSpec.decode(MiotSpec.frame(4, 6, "060100 5ff0 00".hexBytes()))!!
        assertEquals(-4001, err.code)
    }

    @Test fun s400EventShape() {
        val pt = MiotSpec.frame(7, 7, byteArrayOf(8) + le16(1) + byteArrayOf(1) + le16(1) + MiotSpec.encodeValue(SpecValue.Str("523,1")))
        assertEquals(0x20, pt[1].toInt())
        val m = MiotSpec.decode(pt)!!
        assertEquals(8, m.siid); assertEquals(1, m.iid); assertEquals(SpecValue.Str("523,1"), m.args[1])
    }

    @Test fun signedAndWideValues() {
        val v = SpecValue.Num(SpecValue.I64, 9876543210L)
        val pt = MiotSpec.action(1, 4, 2, listOf(1 to v, 2 to SpecValue.Num(SpecValue.I16, -3), 3 to SpecValue.Bool(true)))
        val m = MiotSpec.decode(pt)!!
        assertEquals(v, m.args[1]); assertEquals(SpecValue.Num(SpecValue.I16, -3), m.args[2]); assertEquals(SpecValue.Bool(true), m.args[3])
    }

    @Test fun cuktechSetIsRecognised() {
        assertEquals(0x00, MiotSpec.decode("0c20210000010206000110 04".hexBytes())?.op)
    }

    @Test fun offlineRecordsFromPluginFormat() {
        val r = OfflineRecord.parseAll("12,1234567890,1,7235,0,1792499000_13,0,0,7180,2,1792585400")
        assertEquals(2, r.size)
        assertEquals(OfflineRecord(12, 1234567890L, 1, 72.35, 0, 1792499000L), r[0])
        assertEquals(13, r[1].idx); assertEquals(71.8, r[1].weightKg, 1e-9); assertEquals(2, r[1].status)
        assertEquals("12,13", OfflineRecord.idxString(r.map { it.idx }))
        assertTrue(OfflineRecord.parseAll("garbage").isEmpty())
    }
}

class AckSessionTest {
    private val token = MiToken.parse("00112233445566778899aabb")!!
    private val app = ByteArray(16) { it.toByte() }
    private val dev = ByteArray(16) { (it + 16).toByte() }

    @Test fun fullSessionConfirmsReportedRecords() {
        val scale = FakeScale(token, dev, listOf("1,1234567890,1,7235,0,1792499000", "2,1234567890,1,7190,0,1792585400_3,0,0,7000,2,1792585500"))
        val s = AckSession.login(scale, token, { app.copyOf(it) }, stepTimeoutMs = 50, eventTimeoutMs = 50)
        assertTrue(scale.loggedIn)
        val recs = s.requestOffline()
        assertEquals(listOf(1, 2, 3), recs.map { it.idx })
        s.confirmOffline(listOf(1, 2))
        val confirm = scale.actions.last()
        assertEquals(6 to 2, confirm.siid to confirm.iid)
        assertEquals(SpecValue.Str("1,2"), confirm.args[2])
        assertEquals(listOf(6 to 1, 6 to 2), scale.actions.map { it.siid to it.iid })
    }

    @Test fun responseWithoutCodeAndSegmentedRandom() {
        val scale = FakeScale(token, dev, emptyList(), withCode = false, sglRandom = false)
        val s = AckSession.login(scale, token, { app.copyOf(it) }, stepTimeoutMs = 50, eventTimeoutMs = 50)
        assertEquals(emptyList(), s.requestOffline())
        s.confirmOffline(emptyList())
    }

    @Test fun unansweredRequestMeansNothingStored() {
        val scale = FakeScale(token, dev, emptyList(), answersOffline = false)
        val s = AckSession.login(scale, token, { app.copyOf(it) }, stepTimeoutMs = 50, offlineTimeoutMs = 20)
        assertTrue(scale.loggedIn)
        assertEquals(emptyList(), s.requestOffline())
        s.confirmOffline(emptyList())
        assertEquals(listOf(6 to 1), scale.actions.map { it.siid to it.iid })
    }

    @Test fun errorCodeOnRequestMeansNothingStored() {
        val scale = FakeScale(token, dev, emptyList(), offlineCode = -4004)
        val s = AckSession.login(scale, token, { app.copyOf(it) }, stepTimeoutMs = 50, offlineTimeoutMs = 20)
        assertEquals(emptyList(), s.requestOffline())
    }

    @Test fun rejectedConfirmFails() {
        val scale = FakeScale(token, dev, listOf("1,1234567890,1,7235,0,1792499000"), confirmCode = -4001)
        val s = AckSession.login(scale, token, { app.copyOf(it) }, stepTimeoutMs = 50, eventTimeoutMs = 50)
        assertEquals(listOf(1), s.requestOffline().map { it.idx })
        assertEquals("confirm code -4001", assertFailsWith<IllegalStateException> { s.confirmOffline(listOf(1)) }.message)
    }

    @Test fun wrongTokenFailsBeforeConfirming() {
        val scale = FakeScale(MiToken.parse("ffffffffffffffffffffffff")!!, dev, emptyList())
        assertFailsWith<LoginException> { AckSession.login(scale, token, { app.copyOf(it) }, stepTimeoutMs = 50) }
        assertFalse(scale.loggedIn)
        assertTrue(scale.writes.none { it.first == Gatt.TX })
    }

    @Test fun loginWritesExpectedOpening() {
        val scale = FakeScale(token, dev, emptyList())
        AckSession.login(scale, token, { app.copyOf(it) }, stepTimeoutMs = 50)
        val hex = scale.writes.take(3).map { (ch, v) -> ch to v.joinToString("") { "%02x".format(it) } }
        assertEquals(listOf(Gatt.UPNP to "24000000", Gatt.AVDTP to "0000000b0100", Gatt.AVDTP to "0100000102030405060708090a0b0c0d0e0f"), hex)
    }
}
