package dev.lorenzo.s200.bridge

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.lorenzo.s200.ScaleMac
import java.util.concurrent.TimeUnit

sealed class ArmResult(val label: String, val armed: Boolean = false) {
    object NotConfigured : ArmResult("not configured")
    object NoPermission : ArmResult("no BLUETOOTH_SCAN")
    object BluetoothOff : ArmResult("bluetooth off")
    object Recent : ArmResult("armed (recent)", armed = true)
    class Started(rc: Int) : ArmResult("armed (rc=$rc)", armed = true)
    class Denied(e: SecurityException) : ArmResult("security: ${e.message}")
}

object Scanner {
    private const val TAG = "S200Scanner"
    val FE95: ParcelUuid = ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    private val debounce = ArmDebounce()

    private fun filter(mac: ScaleMac): ScanFilter = ScanFilter.Builder()
        .setServiceData(FE95, byteArrayOf(0x48, 0x59), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        .setDeviceAddress(mac.address)
        .build()

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ScanReceiver::class.java).setAction("dev.lorenzo.s200.SCAN"),
        SCAN_PI_FLAGS,
    )

    fun rearm(ctx: Context): ArmResult {
        val result = arm(ctx)
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<WatchdogWorker>(WATCHDOG_MINUTES, TimeUnit.MINUTES).build(),
        )
        return result
    }

    fun arm(ctx: Context): ArmResult {
        val cfg = Config(ctx)
        val mac = cfg.mac
        if (mac == null || cfg.key == null) return ArmResult.NotConfigured
        if (!ctx.granted(Manifest.permission.BLUETOOTH_SCAN)) return ArmResult.NoPermission
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return ArmResult.BluetoothOff
        if (debounce.recent()) return ArmResult.Recent
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        return try {
            scanner.stopScan(pi(ctx))
            val rc = scanner.startScan(listOf(filter(mac)), settings, pi(ctx))
            debounce.mark()
            ArmResult.Started(rc).also { Log.i(TAG, it.label) }
        } catch (e: SecurityException) { ArmResult.Denied(e) }
    }
}

class WatchdogWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result { Log.i("S200Watchdog", Scanner.arm(applicationContext).label); return Result.success() }
}
