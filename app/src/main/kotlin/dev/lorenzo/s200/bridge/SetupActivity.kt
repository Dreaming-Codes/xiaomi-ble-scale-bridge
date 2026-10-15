package dev.lorenzo.s200.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class SetupActivity : ComponentActivity() {
    private val hcPerms = setOf(HealthPermission.getWritePermission(WeightRecord::class))
    private lateinit var status: TextView
    private val btPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val hcPerm = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val cfg = Config(this)
        fun field(hint: String, v: String, num: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(v); if (num) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val key = field("Bind key (32 hex)", cfg.keyHex)
        val mac = field("Scale MAC AA:BB:CC:DD:EE:FF", cfg.mac)
        val prof = field("Profile slot (-1 = any)", cfg.profile.toString(), true)
        status = TextView(this)
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48)
            addView(key); addView(mac); addView(prof)
            addView(btn("Save & arm scan") {
                cfg.keyHex = key.text.toString().trim(); cfg.mac = mac.text.toString().trim().uppercase()
                cfg.profile = prof.text.toString().toIntOrNull() ?: -1
                refresh()
            })
            addView(btn("Grant Bluetooth scan") { btPerm.launch(Manifest.permission.BLUETOOTH_SCAN) })
            addView(btn("Grant Health Connect weight write") { hcPerm.launch(hcPerms) })
            addView(btn("Associate scale (Companion, optional; step on scale first)") {
                Companion.associate(this@SetupActivity, Config(this@SetupActivity).mac) { status.append("\n$it") }
            })
            addView(btn("Battery: Unrestricted") { askUnrestricted() })
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    @SuppressLint("BatteryLife")
    private fun askUnrestricted() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun refresh() {
        val cfg = Config(this)
        val armed = Scanner.arm(this) // opening the app always re-arms
        val comp = Companion.observe(this)
        Scanner.scheduleWatchdog(this)
        lifecycleScope.launch {
            val hc = if (HealthConnectClient.getSdkStatus(this@SetupActivity) == HealthConnectClient.SDK_AVAILABLE)
                HealthConnectClient.getOrCreate(this@SetupActivity).permissionController.getGrantedPermissions().containsAll(hcPerms)
            else null
            val batt = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            status.text = "Configured: ${cfg.configured}\nScan: $armed\nHC write: ${hc ?: "HC unavailable"}\n" +
                "Companion: $comp\nBattery unrestricted: $batt\nLast: ${cfg.lastStatus}"
        }
    }
}
