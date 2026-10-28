package dev.touchbridge.ui

import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.touchbridge.diagnostics.Capabilities
import dev.touchbridge.nearby.NearbyPhase
import dev.touchbridge.nearby.NearbyState
import dev.touchbridge.BuildConfig
import dev.touchbridge.nearby.AndroidNearbyTransport
import dev.touchbridge.transfer.FilePhase

@Composable
fun LabScreen(model: LabViewModel, chooseNearbyRole: (Role) -> Unit, chooseFile: () -> Unit, chooseDestination: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val nearby by model.nearby.state.collectAsStateWithLifecycle()
    val file by model.files.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var capabilities by remember { mutableStateOf(Capabilities.detect(context)) }
    var nearbyDiagnostics by remember { mutableStateOf(AndroidNearbyTransport.diagnostics(context)) }
    var page by rememberSaveable { mutableStateOf("connect") }
    val about = page == "about"
    val connect = page == "connect"
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        capabilities = Capabilities.detect(context)
        nearbyDiagnostics = AndroidNearbyTransport.diagnostics(context)
    }
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF80D5C5))
        else lightColorScheme(primary = Color(0xFF006C60))) {
        if (connect && nearby.phase == NearbyPhase.VERIFYING) {
            AlertDialog(
                onDismissRequest = model.nearby::reject,
                title = { Text("Compare connection codes") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(nearby.digits, style = MaterialTheme.typography.displayMedium)
                    Text(nearby.detail)
                } },
                confirmButton = {
                    if (!nearby.approved) TextButton(onClick = model.nearby::approve) { Text("Codes match · accept") }
                    else Text("Waiting for approval")
                },
                dismissButton = { TextButton(onClick = model.nearby::reject) { Text("Reject") } },
            )
        }
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("TouchBridge", style = MaterialTheme.typography.headlineLarge)
                Text("A small touch. A new connection.", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = connect, onClick = { model.stop(); page = "connect" }, label = { Text("Connect") })
                    FilterChip(selected = page == "lab", onClick = { model.stop(); page = "lab" }, label = { Text("NFC Lab") })
                    FilterChip(selected = about, onClick = { model.stop(); page = "about" }, label = { Text("About") })
                }
                if (about) {
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge)
                    Text("Built for two unlocked phones", style = MaterialTheme.typography.titleLarge)
                    Text("This build tests genuine ISO-DEP/APDU communication. Choose Emulate on one phone and Scan on the other. Swap roles to test both NFC implementations.")
                    Text("NFC exchanges a public, random session ID and a greeting. Connect uses this ID to find the tapped phone over Nearby. Compare codes and accept on both phones before sending a message. Internet is not required.")
                    Text("Keep both phones unlocked with TouchBridge open. The system file picker can temporarily keep the connection alive; other app switches and rotation end the session. No tap-to-launch, locked-screen transfer, Android Beam, or saved Wi-Fi password access.")
                    Text("NFC and Nearby hello passed developer tests in both roles on Pixel 9a and Galaxy S21 Ultra. File sharing awaits device testing.", color = MaterialTheme.colorScheme.primary)
                } else {
                    if (connect) {
                        Text("Tap, compare, connect", style = MaterialTheme.typography.titleLarge)
                        Text("Enable Bluetooth and Wi-Fi on both phones. Nearby needs permission to find devices; older Android versions also require Location, and Android 17 requires local-network access. Location is not stored or used for tracking.")
                        NearbyCard(nearby, model.nearby::sendHello)
                        if (nearby.phase == NearbyPhase.CONNECTED) {
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Share a file", style = MaterialTheme.typography.titleLarge)
                                    Text(file.detail)
                                    file.offer?.let { offer ->
                                        Text(offer.name, style = MaterialTheme.typography.titleMedium)
                                        Text(formatBytes(offer.size))
                                        if (file.phase in setOf(FilePhase.TRANSFERRING, FilePhase.WAITING_RECEIPT, FilePhase.SUCCESS)) {
                                            LinearProgressIndicator(progress = { if (offer.size == 0L) 1f else (file.bytes.toDouble() / offer.size).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                            Text("${formatBytes(file.bytes)} / ${formatBytes(offer.size)}" + if (file.bytesPerSecond > 0) " · ${formatBytes(file.bytesPerSecond)}/s" else "")
                                        }
                                    }
                                    if (file.phase in setOf(FilePhase.PREPARING, FilePhase.SAVING)) LinearProgressIndicator(Modifier.fillMaxWidth())
                                    if (file.phase == FilePhase.INCOMING) {
                                        Button(onClick = chooseDestination) { Text("Accept · choose save location") }
                                        TextButton(onClick = model.files::reject) { Text("Decline file") }
                                    } else if (file.busy) TextButton(onClick = model.files::cancel) { Text("Cancel transfer") }
                                    Button(onClick = chooseFile, enabled = !file.busy) { Text("Choose file, photo, or video") }
                                    Text("One file at a time. The receiver approves each file. Keep both apps open until saving finishes.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("Bluetooth & Wi-Fi") }
                            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri())) }) { Text("App permissions") }
                        }
                    }
                    if (!connect || state.role != Role.IDLE || state.success) ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.title, style = MaterialTheme.typography.headlineSmall)
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.detail)
                            if (state.role == Role.EMULATE && !state.success) Text("Session expires in ${state.secondsLeft}s", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (!capabilities.nfc || !capabilities.enabled) {
                        Text(if (!capabilities.nfc) "NFC is unavailable on this device. You can still inspect About and diagnostics."
                            else "NFC is turned off. Enable it in Settings to continue.")
                        if (capabilities.nfc) OutlinedButton(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                        }) { Text("Open NFC settings") }
                    }
                    val canStart = !connect || nearby.phase in setOf(NearbyPhase.IDLE, NearbyPhase.ERROR)
                    Button(onClick = { if (connect) chooseNearbyRole(Role.EMULATE) else model.choose(Role.EMULATE) }, modifier = Modifier.fillMaxWidth(),
                        enabled = capabilities.enabled && capabilities.hce && !state.busy && canStart) { Text(if (connect) "Advertise + emulate" else "Emulate · share NFC session") }
                    OutlinedButton(onClick = { if (connect) chooseNearbyRole(Role.SCAN) else model.choose(Role.SCAN) }, modifier = Modifier.fillMaxWidth(),
                        enabled = capabilities.enabled && !state.busy && canStart) { Text(if (connect) "Scan + connect" else "Scan · read the other phone") }
                    if (!capabilities.hce) Text("This device does not report Host Card Emulation support.")
                    if (state.role != Role.IDLE || (connect && nearby.phase != NearbyPhase.IDLE)) TextButton(onClick = { model.stop() }) { Text("Stop session") }
                    Text("Hold the backs of the phones together and slowly adjust their positions. Antenna placement varies; remove thick or magnetic cases if needed.", style = MaterialTheme.typography.bodyMedium)
                    Text(if (connect) "Approve on both phones, then share a file or send a hello." else "NFC Lab · wireless connection disabled", style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider()
                TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide diagnostics" else "Show diagnostics") }
                if (diagnostics) {
                    Text("TouchBridge ${BuildConfig.VERSION_NAME}\n$nearbyDiagnostics")
                    Text("Android API ${capabilities.api}\nNFC: ${capabilities.nfc} · enabled: ${capabilities.enabled}\nHCE: ${capabilities.hce}\nRole: ${state.role}\nProtocol: 1 · ISO-DEP / NFC-A\nNearby: ${nearby.phase}\nLocal approval: ${nearby.approved}")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes.toDouble() / 1024)
    else -> "$bytes bytes"
}

@Composable
private fun NearbyCard(state: NearbyState, send: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (state.phase == NearbyPhase.CONNECTED) "Connected to the tapped phone" else "Nearby connection",
                style = MaterialTheme.typography.titleLarge)
            Text(state.detail)
            if (state.phase in setOf(NearbyPhase.STARTING, NearbyPhase.DISCOVERING, NearbyPhase.REQUESTING, NearbyPhase.MATCHING) || state.sending) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.phase == NearbyPhase.VERIFYING) {
                Text(state.digits, style = MaterialTheme.typography.displayMedium)
            }
            if (state.phase == NearbyPhase.CONNECTED) {
                Button(onClick = send, enabled = !state.sending && !state.sent) { Text(if (state.sent) "Hello sent" else "Send hello") }
                if (state.sent) Text("Nearby reports the message was delivered.")
                if (state.received.isNotEmpty()) Text("Received: ${state.received}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
