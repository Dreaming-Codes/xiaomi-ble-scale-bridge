package dev.lorenzo.s200

class FakeScale(
    private val token: MiToken,
    private val devRand: ByteArray,
    private val records: List<String>,
    private val withCode: Boolean = true,
    private val sglRandom: Boolean = true,
    private val answersOffline: Boolean = true,
    private val offlineCode: Int = 0,
    private val confirmCode: Int = 0,
) : Link {
    private val notifications = mutableMapOf<Int, ArrayDeque<ByteArray>>()
    private val rxSeg = mutableMapOf<Int, Triple<Int, Int, MutableList<ByteArray>>>()
    private val outbox = mutableMapOf<Int, ArrayDeque<Pair<Int, ByteArray>>>()
    private val inFlight = mutableMapOf<Int, List<ByteArray>?>()
    private var appRand: ByteArray? = null
    private var channel: MiChannel? = null
    val writes = mutableListOf<Pair<Int, ByteArray>>()
    val actions = mutableListOf<SpecMsg>()
    var loggedIn = false

    override fun read(ch: Int, timeoutMs: Long): ByteArray? = notifications[ch]?.removeFirstOrNull()

    override fun write(ch: Int, value: ByteArray) {
        writes += ch to value
        if (ch == Gatt.UPNP) return
        val sn = (value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8)
        if (sn != 0) {
            val (type, count, parts) = rxSeg.getValue(ch)
            parts += value.copyOfRange(2, value.size)
            if (parts.size == count) {
                rxSeg.remove(ch)
                notify(ch, Rxfer.segAck(Rxfer.ACK_SUCCESS))
                onMessage(ch, type, parts.fold(byteArrayOf()) { a, b -> a + b })
            }
            return
        }
        val mode = value[2].toInt() and 0xFF
        val type = value[3].toInt() and 0xFF
        when (mode) {
            Rxfer.SEG_CMD -> { rxSeg[ch] = Triple(type, (value[4].toInt() and 0xFF) or ((value[5].toInt() and 0xFF) shl 8), mutableListOf()); notify(ch, Rxfer.segAck(Rxfer.ACK_READY)) }
            Rxfer.SGL_CMD -> { notify(ch, Rxfer.sglAck(Rxfer.ACK_SUCCESS)); onMessage(ch, type, value.copyOfRange(4, value.size)) }
            Rxfer.SEG_ACK -> when (type) {
                Rxfer.ACK_READY -> inFlight[ch]?.forEach { notify(ch, it) }
                Rxfer.ACK_SUCCESS -> { inFlight[ch] = null; pump(ch) }
            }
            Rxfer.SGL_ACK -> { inFlight[ch] = null; pump(ch) }
        }
    }

    private fun notify(ch: Int, v: ByteArray) { notifications.getOrPut(ch) { ArrayDeque() }.addLast(v) }

    private fun send(ch: Int, type: Int, data: ByteArray, sgl: Boolean) {
        outbox.getOrPut(ch) { ArrayDeque() }.addLast((if (sgl) 1 else 0) * 0x100 + type to data)
        if (inFlight[ch] == null) pump(ch)
    }

    private fun pump(ch: Int) {
        val (tag, data) = outbox[ch]?.removeFirstOrNull() ?: return
        val type = tag and 0xFF
        if (tag >= 0x100) { inFlight[ch] = emptyList(); notify(ch, Rxfer.sglCmd(type, data)) }
        else { val segs = Rxfer.segments(data); inFlight[ch] = segs; notify(ch, Rxfer.segCmd(type, segs.size)) }
    }

    private fun onMessage(ch: Int, type: Int, data: ByteArray) {
        when {
            ch == Gatt.AVDTP && type == Rxfer.APP_RANDOM -> {
                appRand = data
                val k = MiLogin.deriveKeys(token, data, devRand)
                send(Gatt.AVDTP, Rxfer.DEV_RANDOM, devRand, sglRandom)
                send(Gatt.AVDTP, Rxfer.DEV_CONFIRMATION, MiLogin.devConfirmation(k, data, devRand), false)
            }
            ch == Gatt.AVDTP && type == Rxfer.APP_CONFIRMATION -> {
                val k = MiLogin.deriveKeys(token, appRand!!, devRand)
                if (MiLogin.appConfirmation(k, appRand!!, devRand).contentEquals(data)) {
                    loggedIn = true; channel = MiChannel(k, asDevice = true); notify(Gatt.UPNP, AckSession.LOGIN_OK)
                } else notify(Gatt.UPNP, AckSession.LOGIN_ERR)
            }
            ch == Gatt.TX && type == Rxfer.PASS_THROUGH -> {
                val ch2 = channel!!
                val m = MiotSpec.decode(ch2.open(data)!!)!!
                actions += m
                when (m.siid to m.iid) {
                    6 to 1 -> if (!answersOffline) Unit else if (offlineCode != 0) reply(m, emptyList(), offlineCode) else {
                        reply(m, listOf(3 to SpecValue.Num(SpecValue.U8, records.sumOf { it.split('_').size }.toLong())))
                        records.forEach { r -> event(6, 1, listOf(1 to SpecValue.Str(r))) }
                    }
                    6 to 2 -> reply(m, emptyList(), confirmCode)
                    else -> reply(m, emptyList())
                }
            }
        }
    }

    private fun reply(m: SpecMsg, out: List<Pair<Int, SpecValue>>, code: Int = 0) {
        val body = byteArrayOf(m.siid.toByte()) + le16(m.iid) + (if (withCode) le16(code) else byteArrayOf()) +
            byteArrayOf(out.size.toByte()) + out.fold(byteArrayOf()) { a, (p, v) -> a + le16(p) + MiotSpec.encodeValue(v) }
        val blob = channel!!.seal(MiotSpec.frame(m.tid, MiotSpec.ACTION_RSP, body))
        send(Gatt.RX, Rxfer.PASS_THROUGH, blob, blob.size <= 16)
    }

    private fun event(siid: Int, eiid: Int, args: List<Pair<Int, SpecValue>>) {
        val body = byteArrayOf(siid.toByte()) + le16(eiid) + byteArrayOf(args.size.toByte()) +
            args.fold(byteArrayOf()) { a, (p, v) -> a + le16(p) + MiotSpec.encodeValue(v) }
        send(Gatt.RX, Rxfer.PASS_THROUGH, channel!!.seal(MiotSpec.frame(77, MiotSpec.EVENT, body)), false)
    }
}
