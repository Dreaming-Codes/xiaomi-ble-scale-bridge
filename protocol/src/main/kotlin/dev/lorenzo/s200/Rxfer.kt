package dev.lorenzo.s200

interface Link {
    fun write(ch: Int, value: ByteArray)
    fun read(ch: Int, timeoutMs: Long): ByteArray?
}

object Gatt {
    const val UPNP = 0x0010
    const val AVDTP = 0x0019
    const val TX = 0x001A
    const val RX = 0x001B
}

object Rxfer {
    const val SEG_CMD = 0
    const val SEG_ACK = 1
    const val SGL_CMD = 2
    const val SGL_ACK = 3
    const val MNG_CMD = 4
    const val MNG_ACK = 5

    const val ACK_SUCCESS = 0
    const val ACK_READY = 1
    const val ACK_LOST = 5

    const val PASS_THROUGH = 0x00
    const val APP_CONFIRMATION = 0x0A
    const val APP_RANDOM = 0x0B
    const val DEV_CONFIRMATION = 0x0C
    const val DEV_RANDOM = 0x0D

    const val DMTU = 18

    fun ctrl(mode: Int, vararg rest: Int): ByteArray = byteArrayOf(0, 0, mode.toByte()) + ByteArray(rest.size) { rest[it].toByte() }
    fun segCmd(type: Int, count: Int) = ctrl(SEG_CMD, type, count and 0xFF, count ushr 8)
    fun segAck(type: Int) = ctrl(SEG_ACK, type)
    fun sglCmd(type: Int, data: ByteArray) = ctrl(SGL_CMD, type) + data
    fun sglAck(type: Int) = ctrl(SGL_ACK, type)
    fun segments(data: ByteArray, dmtu: Int = DMTU): List<ByteArray> =
        data.toList().chunked(dmtu).mapIndexed { i, c -> le16(i + 1) + c.toByteArray() }
}

class RxferException(msg: String) : Exception(msg)

class Endpoint(private val link: Link, private val ch: Int, private val timeoutMs: Long) {
    private data class Ack(val mode: Int, val type: Int, val lost: List<Int>)

    private val acks = ArrayDeque<Ack>()
    private val inbox = ArrayDeque<Pair<Int, ByteArray>>()
    private var segType = -1
    private var segCount = 0
    private val segs = sortedMapOf<Int, ByteArray>()

    fun send(type: Int, data: ByteArray) {
        val parts = Rxfer.segments(data)
        link.write(ch, Rxfer.segCmd(type, parts.size))
        val ready = awaitAck()
        if (ready.mode != Rxfer.SEG_ACK || ready.type != Rxfer.ACK_READY) throw RxferException("ch%02x not ready %d/%d".format(ch, ready.mode, ready.type))
        var pending = parts.indices.toList()
        repeat(3) {
            pending.forEach { link.write(ch, parts[it]) }
            val done = awaitAck()
            when {
                done.mode == Rxfer.SEG_ACK && done.type == Rxfer.ACK_SUCCESS -> return
                done.mode == Rxfer.SEG_ACK && done.type == Rxfer.ACK_LOST && done.lost.isNotEmpty() -> pending = done.lost.map { it - 1 }.filter { it in parts.indices }
                else -> throw RxferException("ch%02x seg ack %d/%d".format(ch, done.mode, done.type))
            }
        }
        throw RxferException("ch%02x segments lost".format(ch))
    }

    fun receive(timeout: Long = timeoutMs): Pair<Int, ByteArray>? {
        val deadline = System.currentTimeMillis() + timeout
        while (inbox.isEmpty()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0 || !pump(left)) return null
        }
        return inbox.removeFirst()
    }

    private fun awaitAck(): Ack {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (acks.isEmpty()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0 || !pump(left)) throw RxferException("ch%02x ack timeout".format(ch))
        }
        return acks.removeFirst()
    }

    private fun pump(timeout: Long): Boolean {
        val v = link.read(ch, timeout) ?: return false
        feed(v)
        return true
    }

    private fun feed(v: ByteArray) {
        if (v.size < 2) return
        val sn = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8)
        if (sn != 0) {
            if (segType < 0) return
            segs[sn] = v.copyOfRange(2, v.size)
            if (segs.size >= segCount) {
                inbox.addLast(segType to segs.values.fold(byteArrayOf()) { a, b -> a + b })
                segType = -1; segs.clear()
                link.write(ch, Rxfer.segAck(Rxfer.ACK_SUCCESS))
            }
            return
        }
        if (v.size < 4) return
        val mode = v[2].toInt() and 0xFF
        val type = v[3].toInt() and 0xFF
        when (mode) {
            Rxfer.SEG_CMD -> {
                segType = type
                segCount = if (v.size >= 6) (v[4].toInt() and 0xFF) or ((v[5].toInt() and 0xFF) shl 8) else 1
                segs.clear()
                link.write(ch, Rxfer.segAck(Rxfer.ACK_READY))
            }
            Rxfer.SGL_CMD -> {
                inbox.addLast(type to v.copyOfRange(4, v.size))
                link.write(ch, Rxfer.sglAck(Rxfer.ACK_SUCCESS))
            }
            Rxfer.SEG_ACK, Rxfer.SGL_ACK -> {
                val lost = (4 until v.size - 1 step 2).map { (v[it].toInt() and 0xFF) or ((v[it + 1].toInt() and 0xFF) shl 8) }
                acks.addLast(Ack(mode, type, lost))
            }
            Rxfer.MNG_CMD -> link.write(ch, Rxfer.ctrl(Rxfer.MNG_ACK) + v.copyOfRange(3, v.size))
        }
    }
}
