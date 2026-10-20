package dev.lorenzo.s200.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
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
import dev.lorenzo.s200.BindKey
import dev.lorenzo.s200.MiToken
import dev.lorenzo.s200.Reading
import dev.lorenzo.s200.ScaleMac
import dev.lorenzo.s200.cloud.CloudScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
}

private fun Context.batteryUnrestricted() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

@SuppressLint("BatteryLife")
private fun Context.openBatterySettings() {
    val action = if (batteryUnrestricted()) Settings.ACTION_APPLICATION_DETAILS_SETTINGS else Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    startActivity(Intent(action, Uri.parse("package:$packageName")))
}

private enum class RuntimePermission(val permission: String, val icon: ImageVector, val title: String, val purpose: String) {
    Scan(Manifest.permission.BLUETOOTH_SCAN, Icons.Outlined.Search, "Bluetooth scan", "Find the scale's broadcasts"),
    Connect(Manifest.permission.BLUETOOTH_CONNECT, Icons.Outlined.Refresh, "Bluetooth connect", "Re-arm after Bluetooth restarts; stop the scale blinking"),
    Notify(Manifest.permission.POST_NOTIFICATIONS, Icons.Outlined.Notifications, "Notifications", "Show each new weigh-in"),
}

@Composable
private fun BridgeTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

private data class UiState(
    val scan: ArmResult? = null,
    val granted: Set<RuntimePermission> = emptySet(),
    val tokenSet: Boolean = false,
    val ack: AckStatus? = null,
    val hc: Boolean? = null,
    val battery: Boolean = false,
    val unused: UnusedApp = UnusedApp.Unknown,
    val history: List<Reading> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeScreen(act: ComponentActivity) {
    val cfg = remember { Config(act) }
    var state by remember { mutableStateOf(UiState()) }
    var tick by remember { mutableIntStateOf(0) }
    val refresh = { tick++ }

    LaunchedEffect(tick) {
        val scan = Scanner.rearm(act)
        val hc = if (HealthConnectClient.getSdkStatus(act) == HealthConnectClient.SDK_AVAILABLE)
            runCatching { HealthConnectClient.getOrCreate(act).permissionController.getGrantedPermissions().containsAll(HC_PERMS) }.getOrNull()
        else null
        state = UiState(
            scan = scan,
            granted = RuntimePermission.entries.filterTo(mutableSetOf()) { act.granted(it.permission) },
            tokenSet = cfg.token != null,
            ack = cfg.ack,
            hc = hc,
            battery = act.batteryUnrestricted(),
            unused = UnusedApp.query(act),
            history = cfg.history,
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    val unusedLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { refresh() }
    val snackbar = remember { SnackbarHostState() }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) { msg?.let { snackbar.showSnackbar(it); msg = null } }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("Xiaomi BLE Scale Bridge") }, scrollBehavior = scroll) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { StatusCard(state) }
            item {
                SetupCard(cfg) { msg = if (cfg.configured) "Saved" else "Check key (32 hex) and MAC"; refresh() }
            }
            item {
                SectionCard("Permissions", Icons.Outlined.Lock) {
                    RuntimePermission.entries.forEach { p ->
                        PermRow(p.icon, p.title, p.purpose, p in state.granted, "Grant") { permLauncher.launch(p.permission) }
                    }
                    PermRow(Icons.Outlined.Favorite, "Health Connect", "Write weight records",
                        state.hc == true, if (state.hc == null) "Unavailable" else "Grant", enabled = state.hc != null) {
                        hcLauncher.launch(HC_PERMS)
                    }
                    PermRow(Icons.Outlined.Settings, "Battery unrestricted", "Reliable background delivery", state.battery, "Open") {
                        act.openBatterySettings()
                    }
                    PermRow(Icons.Outlined.Lock, "Unused-app exemption",
                        "Recommended · hibernation force-stops the app and kills the background scan · ${state.unused.label}",
                        state.unused.exempt, "Open", enabled = state.unused.canManage) {
                        runCatching { unusedLauncher.launch(UnusedApp.manageIntent(act)) }.onFailure { msg = "Can't open the setting: ${it.message}" }
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
    val lastReading = s.history.firstOrNull()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last weight", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                lastReading?.let { "%.2f kg".format(it.kg) } ?: "—",
                style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(lastReading?.let { fmtTime(it.epochSec) } ?: "No readings yet",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Scan: ${s.scan?.label ?: "-"}", s.scan?.armed == true)
                if (s.tokenSet) StatusChip(s.ack?.label() ?: "Scale: not synced yet", s.ack is AckStatus.Synced)
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, ok: Boolean) {
    AssistChip(
        onClick = {}, label = { Text(text) },
        leadingIcon = { Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Warning, null, Modifier.size(18.dp)) },
    )
}

@Composable
private fun SetupCard(cfg: Config, onSaved: () -> Unit) {
    var key by remember { mutableStateOf(cfg.keyHex) }
    var mac by remember { mutableStateOf(cfg.macText) }
    var profile by remember { mutableStateOf(cfg.profileSlot.toString()) }
    var token by remember { mutableStateOf(cfg.tokenHex) }
    val keyOk = BindKey.parse(key) != null
    val tokenOk = token.isEmpty() || MiToken.parse(token) != null
    val macOk = ScaleMac.parse(mac) != null
    val apply = { c: CloudScale ->
        key = c.bindKey.hex; mac = c.mac.address; token = c.token?.hex.orEmpty()
        cfg.keyHex = key; cfg.macText = mac; cfg.tokenHex = token; onSaved()
    }
    SectionCard("Scale setup", Icons.Outlined.Edit) {
        XiaomiSignInSection(apply)
        HorizontalDivider()
        Text("Or enter the values by hand", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SecretField(key, { key = it }, "Bind key", "32 hex chars from Xiaomi cloud", isError = key.isNotEmpty() && !keyOk)
        OutlinedTextField(
            mac, { mac = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Scale MAC") },
            placeholder = { Text("AA:BB:CC:DD:EE:FF") }, isError = mac.isNotEmpty() && !macOk, singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        )
        SecretField(token, { token = it }, "BLE token (optional)",
            "24 hex chars from Xiaomi cloud · lets the app clear the scale's sync icon", isError = !tokenOk)
        OutlinedTextField(
            profile, { profile = it }, Modifier.fillMaxWidth(), label = { Text("Profile slot") },
            supportingText = { Text("-1 = accept any user on the scale") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button(
            onClick = {
                cfg.keyHex = key; cfg.macText = mac.uppercase(); cfg.tokenHex = token.lowercase(); cfg.profileSlot = profile.toIntOrNull() ?: -1; onSaved()
            },
            enabled = keyOk && macOk && tokenOk, modifier = Modifier.align(Alignment.End),
        ) { Text("Save & arm") }
    }
}

@Composable
private fun ColumnScope.XiaomiSignInSection(onPicked: (CloudScale) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf<XiaomiLogin>(XiaomiLogin.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    val openBrowser = { url: String -> CustomTabsIntent.Builder().build().launchUrl(ctx, Uri.parse(url)) }
    fun pick(c: CloudScale) { login = XiaomiLogin.Idle; onPicked(c) }
    fun start() {
        job?.cancel()
        job = scope.launch {
            val result = xiaomiSignIn { login = it; if (it is XiaomiLogin.AwaitingBrowser) openBrowser(it.loginUrl) }
            runCatching { ctx.startActivity(Intent(ctx, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
            login = result
        }
    }
    when (val l = login) {
        XiaomiLogin.Idle, is XiaomiLogin.Failed -> {
            Text("Sign in with the Xiaomi account that has the scale in Xiaomi Home. The app reads the bind key, BLE token, and MAC from every Xiaomi region, then forgets the sign-in.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (l is XiaomiLogin.Failed) Text(l.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Button(::start, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.AccountCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(if (l is XiaomiLogin.Failed) "Try again" else "Sign in with Xiaomi")
            }
        }
        is XiaomiLogin.AwaitingBrowser -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Finish signing in on the Xiaomi page. Come back here when it's done.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ job?.cancel(); login = XiaomiLogin.Idle }) { Text("Cancel") }
                FilledTonalButton({ openBrowser(l.loginUrl) }) { Text("Reopen page") }
            }
        }
        XiaomiLogin.Fetching -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Signed in. Looking for your scale in every Xiaomi region.", style = MaterialTheme.typography.bodyMedium)
        }
        is XiaomiLogin.Picked -> LaunchedEffect(l) { pick(l.scale) }
        is XiaomiLogin.Choose -> {
            Text("This account has ${l.scales.size} scales. Pick the one to bridge.", style = MaterialTheme.typography.titleSmall)
            l.scales.forEach { c ->
                ListItem(
                    headlineContent = { Text(c.name) },
                    overlineContent = { Text(c.model.displayName) },
                    supportingContent = { Text("${c.mac.address} · region ${c.region.code}", fontFamily = FontFamily.Monospace) },
                    leadingContent = { Icon(Icons.Outlined.Star, null) },
                    modifier = Modifier.clickable { pick(c) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            TextButton({ login = XiaomiLogin.Idle }, Modifier.align(Alignment.End)) { Text("Cancel") }
        }
    }
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, hint: String, isError: Boolean) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, { onChange(it.trim()) }, Modifier.fillMaxWidth(), label = { Text(label) },
        supportingText = { Text(hint) }, isError = isError, singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton({ shown = !shown }) { Text(if (shown) "Hide" else "Show") } },
    )
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
private fun HistoryCard(rows: List<Reading>) {
    SectionCard("Recent readings", Icons.Outlined.DateRange) {
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

private val FMT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private fun fmtTime(ts: Long) = FMT.format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))
