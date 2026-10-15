package dev.lorenzo.s200.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val HC_PERMS = setOf(HealthPermission.getWritePermission(WeightRecord::class))

class SetupActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(s)
        setContent { BridgeTheme { BridgeScreen(this) } }
    }

    @SuppressLint("BatteryLife")
    fun askUnrestricted() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        else startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}

@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

private data class UiState(
    val configured: Boolean = false, val scan: String = "-", val presence: String = "-",
    val bt: Boolean = false, val hc: Boolean? = null, val battery: Boolean = false, val associated: Boolean = false,
    val last: String = "-", val history: List<String> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeScreen(act: SetupActivity) {
    val cfg = remember { Config(act) }
    var state by remember { mutableStateOf(UiState()) }
    var tick by remember { mutableIntStateOf(0) }
    val refresh = { tick++ }

    LaunchedEffect(tick) {
        val scan = Scanner.arm(act)
        val presence = Companion.observe(act)
        Scanner.scheduleWatchdog(act)
        val hc = if (HealthConnectClient.getSdkStatus(act) == HealthConnectClient.SDK_AVAILABLE)
            runCatching { HealthConnectClient.getOrCreate(act).permissionController.getGrantedPermissions().containsAll(HC_PERMS) }.getOrNull()
        else null
        state = UiState(
            configured = cfg.configured, scan = scan, presence = presence,
            bt = act.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED,
            hc = hc, battery = act.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(act.packageName),
            associated = presence == "observing presence", last = cfg.lastStatus, history = cfg.history,
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }
    val snackbar = remember { SnackbarHostState() }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); msg = null } }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("S200 Bridge") }, scrollBehavior = scroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { StatusCard(state) }
            item {
                SetupCard(cfg) { msg = if (Config(act).configured) "Saved" else "Check key (32 hex) and MAC"; refresh() }
            }
            item {
                SectionCard("Permissions", Icons.Outlined.Security) {
                    PermRow(Icons.Outlined.Bluetooth, "Bluetooth scan", "Find the scale's broadcasts", state.bt, "Grant") {
                        btLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
                    }
                    PermRow(Icons.Outlined.MonitorWeight, "Health Connect", "Write weight records",
                        state.hc == true, if (state.hc == null) "Unavailable" else "Grant", enabled = state.hc != null) {
                        hcLauncher.launch(HC_PERMS)
                    }
                    PermRow(Icons.Outlined.BatteryChargingFull, "Battery unrestricted", "Reliable background delivery", state.battery, "Open") {
                        act.askUnrestricted()
                    }
                    PermRow(Icons.Outlined.Link, "Companion association", "Optional · step on the scale first",
                        state.associated, "Associate", enabled = state.configured) {
                        Companion.associate(act, cfg.mac) { msg = it; refresh() }
                    }
                }
            }
            item { HistoryCard(state.history) }
        }
    }
}

@Composable
private fun SectionCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun StatusCard(s: UiState) {
    val lastReading = s.history.firstOrNull()?.let(::parseReading)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last weight", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                lastReading?.let { "%.2f kg".format(it.second) } ?: "—",
                style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(lastReading?.let { fmtTime(it.first) } ?: "No readings yet",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Scan", s.scan.startsWith("armed"), s.scan)
                StatusChip("Presence", s.presence == "observing presence", s.presence)
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, ok: Boolean, detail: String) {
    AssistChip(
        onClick = {}, label = { Text("$label: $detail") },
        leadingIcon = { Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, Modifier.size(18.dp)) },
    )
}

@Composable
private fun SetupCard(cfg: Config, onSaved: () -> Unit) {
    var key by remember { mutableStateOf(cfg.keyHex) }
    var mac by remember { mutableStateOf(cfg.mac) }
    var profile by remember { mutableStateOf(cfg.profile.toString()) }
    var showKey by remember { mutableStateOf(false) }
    val keyOk = key.matches(Regex("[0-9a-fA-F]{32}"))
    val macOk = mac.uppercase().matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}"))
    SectionCard("Scale setup", Icons.Outlined.Tune) {
        OutlinedTextField(
            key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Bind key") },
            supportingText = { Text("32 hex chars from Xiaomi cloud") }, isError = key.isNotEmpty() && !keyOk, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Toggle") } },
        )
        OutlinedTextField(
            mac, { mac = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Scale MAC") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") }, isError = mac.isNotEmpty() && !macOk, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        )
        OutlinedTextField(
            profile, { profile = it }, Modifier.fillMaxWidth(), label = { Text("Profile slot") },
            supportingText = { Text("-1 = accept any user on the scale") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button(
            onClick = {
                cfg.keyHex = key; cfg.mac = mac.uppercase(); cfg.profile = profile.toIntOrNull() ?: -1; onSaved()
            },
            enabled = keyOk && macOk, modifier = Modifier.align(Alignment.End),
        ) { Text("Save & arm") }
    }
}

@Composable
private fun PermRow(icon: ImageVector, title: String, sub: String, granted: Boolean, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(sub) },
        leadingContent = { Icon(icon, null) },
        trailingContent = {
            if (granted) FilterChip(true, {}, label = { Text("Done") }, leadingIcon = { Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)) })
            else FilledTonalButton(onClick, enabled = enabled) { Text(action) }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun HistoryCard(history: List<String>) {
    SectionCard("Recent readings", Icons.Outlined.History) {
        val rows = history.mapNotNull(::parseReading)
        if (rows.isEmpty()) Text("Step on the scale. Readings appear here once written to Health Connect.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEachIndexed { i, (ts, kg, prof) ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fmtTime(ts), style = MaterialTheme.typography.bodyLarge)
                    Text("Profile $prof", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("%.2f kg".format(kg), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun parseReading(s: String): Triple<Long, Double, Int>? =
    s.split("|").takeIf { it.size == 3 }?.let { (a, b, c) -> runCatching { Triple(a.toLong(), b.toDouble(), c.toInt()) }.getOrNull() }

private val FMT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private fun fmtTime(ts: Long) = FMT.format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))
