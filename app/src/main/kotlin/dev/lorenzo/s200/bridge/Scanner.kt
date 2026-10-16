package dev.lorenzo.s200.bridge

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.util.Log
import dev.lorenzo.s200.ScaleMac
import androidx.work.*
import java.util.concurrent.TimeUnit

object Scanner {
    private const val TAG = "S200Scanner"
    val FE95: ParcelUuid = ParcelUuid.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    private val debounce = ArmDebounce()

    fun filter(mac: ScaleMac): ScanFilter = ScanFilter.Builder()
        .setServiceData(FE95, byteArrayOf(0x48, 0x59), byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
        .setDeviceAddress(mac.address)
        .build()

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ScanReceiver::class.java).setAction("dev.lorenzo.s200.SCAN"),
        SCAN_PI_FLAGS,
    )

    /** Debounced because Android blocks apps that start more than 5 scans in 30 s. */
    fun arm(ctx: Context): String {
        val cfg = Config(ctx)
        val mac = cfg.mac
        if (mac == null || cfg.key == null) return "not configured"
        if (ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return "no BLUETOOTH_SCAN"
        if (debounce.recent()) return "armed (recent)"
        val scanner = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return "bluetooth off"
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        return try {
            scanner.stopScan(pi(ctx))
            val rc = scanner.startScan(listOf(filter(mac)), settings, pi(ctx))
            debounce.mark()
            "armed (rc=$rc)".also { Log.i(TAG, it) }
        } catch (e: SecurityException) { "security: ${e.message}" }
    }

    fun scheduleWatchdog(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "watchdog", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchdogWorker>(1, TimeUnit.DAYS).build(),
        )
    }
}

class WatchdogWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result { Log.i("S200Watchdog", Scanner.arm(applicationContext) + "; " + Companion.observe(applicationContext)); return Result.success() }
}
