package dev.lorenzo.s200.bridge

import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Duration

open class RearmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!shouldRearm(intent.action)) return
        Log.i("S200Rearm", "${intent.action}: ${Scanner.rearm(ctx).label}")
    }
}

class AclRearmReceiver : RearmReceiver()

class ScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val err = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) { Log.w(TAG, "scan error $err"); return }
        val results = intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java) ?: return
        val frames = results.mapNotNull { r -> r.scanRecord?.getServiceData(Scanner.FE95)?.let { r.device.address to it } }
        if (frames.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) { frames.forEach { (mac, data) -> Bridge.handle(ctx, mac, data) } }
            } catch (t: Throwable) {
                Log.w(TAG, "direct write failed, enqueuing retry", t)
                frames.forEach { (mac, data) -> RetryWorker.enqueue(ctx, mac, data) }
            } finally { pending.finish() }
        }
    }

    private companion object { const val TAG = "S200Scan" }
}

class RetryWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val mac = inputData.getString("mac") ?: return Result.failure()
        val data = inputData.getByteArray("data") ?: return Result.failure()
        return try { Bridge.handle(applicationContext, mac, data); Result.success() }
        catch (t: Throwable) { if (runAttemptCount < 5) Result.retry() else Result.failure() }
    }

    companion object {
        fun enqueue(ctx: Context, mac: String, data: ByteArray) {
            val req = OneTimeWorkRequestBuilder<RetryWorker>()
                .setInputData(workDataOf("mac" to mac, "data" to data))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(1))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("write-${data.contentHashCode()}", ExistingWorkPolicy.KEEP, req)
        }
    }
}
