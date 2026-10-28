package dev.touchbridge.ui

import android.content.Intent
import android.provider.Settings
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

@Composable
fun LabScreen(model: LabViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var capabilities by remember { mutableStateOf(Capabilities.detect(context)) }
    var about by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { capabilities = Capabilities.detect(context) }
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF80D5C5))
        else lightColorScheme(primary = Color(0xFF006C60))) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("TouchBridge", style = MaterialTheme.typography.headlineLarge)
                Text("A small touch. A new connection.", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !about, onClick = { about = false }, label = { Text("NFC Lab") })
                    FilterChip(selected = about, onClick = { model.stop(); about = true }, label = { Text("About") })
                }
                if (about) {
                    Text("Built for two unlocked phones", style = MaterialTheme.typography.titleLarge)
                    Text("This build tests genuine ISO-DEP/APDU communication. Choose Emulate on one phone and Scan on the other. Swap roles to test both NFC implementations.")
                    Text("NFC exchanges a public, random session ID and a greeting. It does not authenticate a device. Nearby authentication and file transfers are the next milestone.")
                    Text("Foreground only. Leaving this app or rotating cancels the active role. No tap-to-launch, locked-screen transfer, Android Beam, or saved Wi-Fi password access.")
                    Text("Hardware validation pending on Pixel 9a and Galaxy S21 Ultra.", color = MaterialTheme.colorScheme.primary)
                } else {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(state.title, style = MaterialTheme.typography.headlineSmall)
                            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.detail)
                            if (state.role == Role.EMULATE) Text("Session expires in ${state.secondsLeft}s", style = MaterialTheme.typography.labelLarge)
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
                    Button(onClick = { model.choose(Role.EMULATE) }, modifier = Modifier.fillMaxWidth(),
                        enabled = capabilities.enabled && capabilities.hce && !state.busy) { Text("Emulate · share NFC session") }
                    OutlinedButton(onClick = { model.choose(Role.SCAN) }, modifier = Modifier.fillMaxWidth(),
                        enabled = capabilities.enabled && !state.busy) { Text("Scan · read the other phone") }
                    if (!capabilities.hce) Text("This device does not report Host Card Emulation support.")
                    if (state.role != Role.IDLE) TextButton(onClick = { model.stop() }) { Text("Stop session") }
                    Text("Hold the backs of the phones together and slowly adjust their positions. Antenna placement varies; remove thick or magnetic cases if needed.", style = MaterialTheme.typography.bodyMedium)
                    Text("NFC Lab only · wireless sharing comes next", style = MaterialTheme.typography.labelLarge)
                }
                HorizontalDivider()
                TextButton(onClick = { diagnostics = !diagnostics }) { Text(if (diagnostics) "Hide diagnostics" else "Show diagnostics") }
                if (diagnostics) {
                    Text("Android API ${capabilities.api}\nNFC: ${capabilities.nfc} · enabled: ${capabilities.enabled}\nHCE: ${capabilities.hce}\nRole: ${state.role}\nProtocol: 1 · ISO-DEP / NFC-A\nNearby: not implemented in this build\nAuthentication: not established")
                }
            }
        }
    }
}
