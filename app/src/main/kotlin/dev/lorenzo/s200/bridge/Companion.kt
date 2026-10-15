package dev.lorenzo.s200.bridge

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.companion.*
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.*

/** Optional fast path: CDM's system-server BLE presence scan binds our service when the scale advertises. */
object Companion {
    fun associate(act: Activity, mac: String, onResult: (String) -> Unit) {
        val cdm = act.getSystemService(CompanionDeviceManager::class.java) ?: return onResult("no CDM")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(mac).build())
            .build()
        val req = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        cdm.associate(req, act.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(sender: IntentSender) {
                act.startIntentSenderForResult(sender, 42, null, 0, 0, 0)
            }
            override fun onAssociationCreated(info: AssociationInfo) {
                Config(act).associationId = info.id
                onResult("associated id=${info.id}; " + observe(act))
            }
            override fun onFailure(error: CharSequence?) { onResult("association failed: $error") }
        })
    }

    @SuppressLint("MissingPermission")
    fun observe(ctx: Context): String {
        val cdm = ctx.getSystemService(CompanionDeviceManager::class.java) ?: return "no CDM"
        val id = Config(ctx).associationId
        if (id < 0 || cdm.myAssociations.none { it.id == id }) return "not associated"
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION") cdm.startObservingDevicePresence(Config(ctx).mac)
            }
            "observing presence"
        } catch (e: Exception) { "observe failed: ${e.message}" }
    }
}

/** Bound by the system while the scale is in BLE range; runs a short targeted scan to grab the encrypted adv. */
class ScaleCompanionService : CompanionDeviceService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var cb: ScanCallback? = null

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> burstScan()
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> stopScan()
        }
    }
    @Deprecated("API < 36 path")
    override fun onDeviceAppeared(info: AssociationInfo) { if (Build.VERSION.SDK_INT < 36) burstScan() }
    @Deprecated("API < 36 path")
    override fun onDeviceDisappeared(info: AssociationInfo) { if (Build.VERSION.SDK_INT < 36) stopScan() }

    @SuppressLint("MissingPermission")
    private fun burstScan() {
        if (cb != null) return
        val cfg = Config(this); if (!cfg.configured) return
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val c = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val data = r.scanRecord?.getServiceData(Scanner.FE95) ?: return
                val mac = r.device.address
                scope.launch {
                    runCatching { Bridge.handle(this@ScaleCompanionService, mac, data) }
                        .onFailure { Log.w("S200Companion", "write failed, retry queued", it); ScanReceiver.enqueueRetry(applicationContext, mac, data) }
                }
            }
        }
        cb = c
        try {
            scanner.startScan(listOf(Scanner.filter(cfg.mac)),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), c)
            main.postDelayed({ stopScan() }, 60_000) // cap the burst; scale only advertises for seconds
        } catch (e: SecurityException) { cb = null }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val c = cb ?: return; cb = null
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) }
    }

    override fun onDestroy() { stopScan(); scope.cancel(); super.onDestroy() }
}
