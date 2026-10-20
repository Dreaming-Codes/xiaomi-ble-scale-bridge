package dev.lorenzo.s200.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lorenzo.s200.AckSession
import dev.lorenzo.s200.Gatt
import dev.lorenzo.s200.Link
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object ScaleAck {
    private const val WORK = "scale-ack"

    fun enqueue(ctx: Context) {
        if (Config(ctx).token == null) return
        val req = OneTimeWorkRequestBuilder<AckWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }
}

class AckWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val cfg = Config(ctx)
        val token = cfg.token ?: return Result.success()
        val mac = cfg.mac ?: return Result.success()
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            cfg.ackStatus = "needs Bluetooth connect permission"
            return Result.success()
        }
        val rng = SecureRandom()
        var link: GattLink? = null
        cfg.ackStatus = runCatching {
            withTimeout(SESSION_MS) {
                val (session, offline) = runInterruptible(Dispatchers.IO) {
                    val l = GattLink.open(ctx, mac.address, STEP_MS).also { link = it }
                    val s = AckSession.login(l, token, { n -> ByteArray(n).also(rng::nextBytes) }, STEP_MS)
                    s to s.requestOffline()
                }
                val records = offline
                val confirmable = Bridge.storeOfflineReturningConfirmable(ctx, records)
                runInterruptible(Dispatchers.IO) { session.confirmOffline(confirmable) }
                "$SYNCED ${stamp()}" + if (records.isNotEmpty()) " · ${records.size} stored readings imported" else ""
            }
        }.getOrElse { "Scale sync failed ${stamp()}: ${it.javaClass.simpleName} ${it.message ?: ""}".trim() }
        runCatching { link?.close() }
        Log.i(TAG, cfg.ackStatus)
        return Result.success()
    }

    private fun stamp() = DateTimeFormatter.ofPattern("HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))

    companion object {
        const val SYNCED = "Scale synced"
        private const val TAG = "S200Ack"
        private const val SESSION_MS = 45_000L
        private const val STEP_MS = 5_000L
    }
}

@SuppressLint("MissingPermission")
class GattLink private constructor(private val timeoutMs: Long) : Link, AutoCloseable {
    private val state = LinkedBlockingQueue<Int>()
    private val ops = LinkedBlockingQueue<Int>()
    private val inbound = ConcurrentHashMap<Int, LinkedBlockingQueue<ByteArray>>()
    private val chars = mutableMapOf<Int, BluetoothGattCharacteristic>()
    private lateinit var gatt: BluetoothGatt

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            state.offer(if (status == BluetoothGatt.GATT_SUCCESS) newState else -status)
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { ops.offer(status) }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { ops.offer(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { ops.offer(status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { ops.offer(status) }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            queue(uuid16(c.uuid)).offer(value)
        }
    }

    private fun queue(ch: Int) = inbound.getOrPut(ch) { LinkedBlockingQueue() }

    private fun awaitOp(what: String): Int = ops.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw IllegalStateException("$what timed out")

    private fun connect(ctx: Context, address: String) {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }
            ?: throw IllegalStateException("bluetooth off")
        gatt = adapter.getRemoteDevice(address).connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        val s = state.poll(CONNECT_MS, TimeUnit.MILLISECONDS)
        if (s != BluetoothProfile.STATE_CONNECTED) throw IllegalStateException("connect failed ($s)")
        ops.clear()
        if (gatt.requestMtu(247)) awaitOp("mtu")
        ops.clear()
        if (!gatt.discoverServices()) throw IllegalStateException("discovery refused")
        if (awaitOp("discovery") != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("discovery failed")
        val svc = gatt.getService(FE95) ?: throw IllegalStateException("no FE95 service")
        for (ch in listOf(Gatt.UPNP, Gatt.AVDTP, Gatt.TX, Gatt.RX)) {
            val c = svc.getCharacteristic(uuid(ch)) ?: throw IllegalStateException("no characteristic %04x".format(ch))
            chars[ch] = c
            gatt.setCharacteristicNotification(c, true)
            val cccd = c.getDescriptor(CCCD) ?: continue
            val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            ops.clear()
            if (gatt.writeDescriptor(cccd, value) != BluetoothStatusCodes.SUCCESS) throw IllegalStateException("cccd %04x refused".format(ch))
            if (awaitOp("cccd") != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("cccd %04x failed".format(ch))
        }
    }

    override fun write(ch: Int, value: ByteArray) {
        val c = chars[ch] ?: throw IllegalStateException("unknown characteristic %04x".format(ch))
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        repeat(BUSY_RETRIES) {
            ops.clear()
            when (val rc = gatt.writeCharacteristic(c, value, type)) {
                BluetoothStatusCodes.SUCCESS -> {
                    if (type == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) awaitOp("write %04x".format(ch))
                    else ops.poll(NO_RSP_WRITE_MS, TimeUnit.MILLISECONDS)
                    return
                }
                BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY -> Thread.sleep(20)
                else -> throw IllegalStateException("write %04x refused ($rc)".format(ch))
            }
        }
        throw IllegalStateException("write %04x busy".format(ch))
    }

    override fun read(ch: Int, timeoutMs: Long): ByteArray? = queue(ch).poll(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        if (!::gatt.isInitialized) return
        runCatching { gatt.disconnect() }
        gatt.close()
    }

    companion object {
        private const val CONNECT_MS = 15_000L
        private const val BUSY_RETRIES = 50
        private const val NO_RSP_WRITE_MS = 500L
        private val FE95: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private fun uuid(short: Int) = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))
        private fun uuid16(u: UUID) = (u.mostSignificantBits ushr 32).toInt() and 0xFFFF

        fun open(ctx: Context, address: String, timeoutMs: Long): GattLink {
            val l = GattLink(timeoutMs)
            try { l.connect(ctx, address) } catch (t: Throwable) { l.close(); throw t }
            return l
        }
    }
}
