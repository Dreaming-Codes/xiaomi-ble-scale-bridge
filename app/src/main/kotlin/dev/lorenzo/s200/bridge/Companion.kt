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
import androidx.annotation.RequiresApi
import dev.lorenzo.s200.ScaleMac
import kotlinx.coroutines.*

object Companion {
    fun associate(act: Activity, mac: ScaleMac, onResult: (String) -> Unit) {
        val cdm = act.getSystemService(CompanionDeviceManager::class.java) ?: return onResult("no CDM")
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setDeviceAddress(mac.address).build())
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
        val cfg = Config(ctx)
        val id = cfg.associationId
        if (id < 0 || cdm.myAssociations.none { it.id == id }) return "not associated"
        return try {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
            } else {
                @Suppress("DEPRECATION") cdm.startObservingDevicePresence(cfg.macText)
            }
            "observing presence"
        } catch (e: Exception) { "observe failed: ${e.message}" }
    }
}

class ScaleCompanionService : CompanionDeviceService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private var cb: ScanCallback? = null

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        react(presenceReaction(event.event))
    }
    @Deprecated("API < 36 path")
    override fun onDeviceAppeared(info: AssociationInfo) { if (Build.VERSION.SDK_INT < 36) react(PresenceReaction.REARM_AND_BURST) }
    @Deprecated("API < 36 path")
    override fun onDeviceDisappeared(info: AssociationInfo) { if (Build.VERSION.SDK_INT < 36) react(PresenceReaction.STOP) }

    private fun react(r: PresenceReaction) {
        when (r) {
            PresenceReaction.REARM_AND_BURST -> { Log.i("S200Companion", "appeared: " + Scanner.arm(this)); burstScan() }
            PresenceReaction.STOP -> stopScan()
            PresenceReaction.IGNORE -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun burstScan() {
        if (cb != null) return
        val cfg = Config(this)
        val mac = cfg.mac ?: return
        if (cfg.key == null) return
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
        val c = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val data = r.scanRecord?.getServiceData(Scanner.FE95) ?: return
                val mac = r.device.address
                scope.launch {
                    runCatching { Bridge.handle(this@ScaleCompanionService, mac, data) }
                        .onFailure { Log.w("S200Companion", "write failed, retry queued", it); enqueueRetry(applicationContext, mac, data) }
                }
            }
        }
        cb = c
        try {
            scanner.startScan(listOf(Scanner.filter(mac)),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), c)
            main.postDelayed({ stopScan() }, BURST_SCAN_MS)
        } catch (e: SecurityException) { cb = null }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        val c = cb ?: return; cb = null
        runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(c) }
    }

    private companion object {
        const val BURST_SCAN_MS = 60_000L
    }

    override fun onDestroy() { stopScan(); scope.cancel(); super.onDestroy() }
}
