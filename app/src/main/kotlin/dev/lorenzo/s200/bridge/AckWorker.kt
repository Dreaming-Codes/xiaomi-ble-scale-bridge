package dev.lorenzo.s200.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lorenzo.s200.AckSession
import dev.lorenzo.s200.MiToken
import dev.lorenzo.s200.ScaleMac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface AckStatus {
    object NeedsPermission : AckStatus
    data class Synced(val atEpochSec: Long, val imported: Int) : AckStatus
    data class Failed(val atEpochSec: Long, val error: String) : AckStatus

    fun label(zone: ZoneId = ZoneId.systemDefault()): String = when (this) {
        NeedsPermission -> "needs Bluetooth connect permission"
        is Synced -> "Scale synced ${hhmm(atEpochSec, zone)}" + if (imported > 0) " · $imported stored readings imported" else ""
        is Failed -> "Scale sync failed ${hhmm(atEpochSec, zone)}: $error"
    }

    fun encode(): String = when (this) {
        NeedsPermission -> "permission"
        is Synced -> "synced|$atEpochSec|$imported"
        is Failed -> "failed|$atEpochSec|$error"
    }

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")
        private fun hhmm(epochSec: Long, zone: ZoneId) = HHMM.format(Instant.ofEpochSecond(epochSec).atZone(zone))

        fun decode(s: String): AckStatus? {
            val f = s.split("|", limit = 3)
            return when (f[0]) {
                "permission" -> NeedsPermission
                "synced" -> f.getOrNull(1)?.toLongOrNull()?.let { at -> f.getOrNull(2)?.toIntOrNull()?.let { Synced(at, it) } }
                "failed" -> f.getOrNull(1)?.toLongOrNull()?.let { at -> f.getOrNull(2)?.let { Failed(at, it) } }
                else -> null
            }
        }
    }
}

class AckWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val cfg = Config(ctx)
        val token = cfg.token ?: return Result.success()
        val mac = cfg.mac ?: return Result.success()
        val status = if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) sync(ctx, mac, token)
            else AckStatus.NeedsPermission
        cfg.ack = status
        Log.i(TAG, status.label())
        return Result.success()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private suspend fun sync(ctx: Context, mac: ScaleMac, token: MiToken): AckStatus {
        val rng = SecureRandom()
        var link: GattLink? = null
        return try {
            withTimeout(SESSION_MS) {
                val (session, records) = runInterruptible(Dispatchers.IO) {
                    val l = GattLink.open(ctx, mac.address, STEP_MS).also { link = it }
                    val s = AckSession.login(l, token, { n -> ByteArray(n).also(rng::nextBytes) }, STEP_MS)
                    s to s.requestOffline()
                }
                val confirm = Bridge.importOffline(ctx, records)
                runInterruptible(Dispatchers.IO) { session.confirmOffline(confirm) }
                AckStatus.Synced(now(), records.size)
            }
        } catch (t: Throwable) {
            AckStatus.Failed(now(), "${t.javaClass.simpleName} ${t.message ?: ""}".trim())
        } finally {
            runCatching { link?.close() }
        }
    }

    private fun now() = Instant.now().epochSecond

    companion object {
        private const val TAG = "S200Ack"
        private const val WORK = "scale-ack"
        private const val SESSION_MS = 45_000L
        private const val STEP_MS = 5_000L

        fun enqueue(ctx: Context) {
            if (Config(ctx).token == null) return
            val req = OneTimeWorkRequestBuilder<AckWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
        }
    }
}
