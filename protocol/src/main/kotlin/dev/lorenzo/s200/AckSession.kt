package dev.lorenzo.s200

class LoginException(msg: String) : Exception(msg)

object Ms113 {
    const val SIID_OFFLINE = 6
    const val AIID_REQUEST_OFFLINE = 1
    const val AIID_CONFIRM_OFFLINE = 2
    const val EIID_REPORT_OFFLINE = 1
    const val PIID_OFFLINE_DATA = 1
    const val PIID_IDX_STRING = 2
    const val PIID_DATA_NUM = 3
}

class AckSession(
    private val link: Link,
    private val token: MiToken,
    private val random: (Int) -> ByteArray,
    private val stepTimeoutMs: Long = 5_000,
    private val eventTimeoutMs: Long = 8_000,
    private val offlineTimeoutMs: Long = 3_000,
) {
    private val avdtp = Endpoint(link, Gatt.AVDTP, stepTimeoutMs)
    private val tx = Endpoint(link, Gatt.TX, stepTimeoutMs)
    private val rx = Endpoint(link, Gatt.RX, stepTimeoutMs)
    private var channel: MiChannel? = null
    private var tid = 0
    private val events = ArrayDeque<SpecMsg>()

    fun login() {
        link.write(Gatt.UPNP, LOGIN)
        val appRand = random(16)
        avdtp.send(Rxfer.APP_RANDOM, appRand)
        val got = mutableMapOf<Int, ByteArray>()
        while (Rxfer.DEV_RANDOM !in got || Rxfer.DEV_CONFIRMATION !in got) {
            val (t, d) = avdtp.receive() ?: throw LoginException("no device random/confirmation")
            got[t] = d
        }
        val devRand = got.getValue(Rxfer.DEV_RANDOM)
        val devConfirm = got.getValue(Rxfer.DEV_CONFIRMATION)
        if (devRand.size != 16) throw LoginException("dev random is ${devRand.size} bytes")
        val keys = MiLogin.deriveKeys(token, appRand, devRand)
        if (!MiLogin.devConfirmation(keys, appRand, devRand).contentEquals(devConfirm)) throw LoginException("device confirmation mismatch: wrong or rotated token")
        avdtp.send(Rxfer.APP_CONFIRMATION, MiLogin.appConfirmation(keys, appRand, devRand))
        val deadline = System.currentTimeMillis() + stepTimeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val v = (if (left > 0) link.read(Gatt.UPNP, left) else null) ?: throw LoginException("no login result")
            when {
                v.contentEquals(LOGIN_OK) -> break
                v.contentEquals(LOGIN_ERR) -> throw LoginException("scale rejected login")
            }
        }
        channel = MiChannel(keys)
    }

    fun requestOffline(): List<OfflineRecord> {
        val rsp = try { call(Ms113.SIID_OFFLINE, Ms113.AIID_REQUEST_OFFLINE, timeoutMs = offlineTimeoutMs) } catch (e: RxferException) { return emptyList() }
        if (rsp.code != null && rsp.code != 0) return emptyList()
        val n = (rsp.args[Ms113.PIID_DATA_NUM] as? SpecValue.Num)?.value?.toInt() ?: 0
        val recs = mutableListOf<OfflineRecord>()
        val deadline = System.currentTimeMillis() + eventTimeoutMs
        while (recs.size < n) {
            val ev = nextEvent(deadline) ?: break
            if (ev.siid != Ms113.SIID_OFFLINE || ev.iid != Ms113.EIID_REPORT_OFFLINE) continue
            val s = (ev.args[Ms113.PIID_OFFLINE_DATA] as? SpecValue.Str)?.value ?: ev.args.values.filterIsInstance<SpecValue.Str>().firstOrNull()?.value ?: continue
            recs += OfflineRecord.parseAll(s)
        }
        return recs
    }

    fun confirmOffline(idxs: List<Int>): Int? {
        if (idxs.isEmpty()) return 0
        val rsp = call(Ms113.SIID_OFFLINE, Ms113.AIID_CONFIRM_OFFLINE, listOf(Ms113.PIID_IDX_STRING to SpecValue.Str(OfflineRecord.idxString(idxs))))
        return rsp.code
    }

    private fun call(siid: Int, aiid: Int, args: List<Pair<Int, SpecValue>> = emptyList(), timeoutMs: Long = stepTimeoutMs): SpecMsg {
        val ch = channel ?: throw IllegalStateException("not logged in")
        val id = ++tid
        tx.send(Rxfer.PASS_THROUGH, ch.seal(MiotSpec.action(id, siid, aiid, args)))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val m = nextSpec(deadline) ?: throw RxferException("no response to action $siid.$aiid")
            if (m.op == MiotSpec.ACTION_RSP && m.tid == id) return m
            if (m.op == MiotSpec.EVENT && events.size < 64) events.addLast(m)
        }
    }

    private fun nextEvent(deadline: Long): SpecMsg? {
        events.removeFirstOrNull()?.let { return it }
        while (true) {
            val m = nextSpec(deadline) ?: return null
            if (m.op == MiotSpec.EVENT) return m
        }
    }

    private fun nextSpec(deadline: Long): SpecMsg? {
        val ch = channel ?: return null
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val (type, blob) = rx.receive(left) ?: return null
            if (type != Rxfer.PASS_THROUGH) continue
            return ch.open(blob)?.let(MiotSpec::decode) ?: continue
        }
    }

    companion object {
        val LOGIN = byteArrayOf(0x24, 0, 0, 0)
        val LOGIN_OK = byteArrayOf(0x21, 0, 0, 0)
        val LOGIN_ERR = byteArrayOf(0x23, 0, 0, 0)
    }
}
