package dev.touchbridge

import android.content.ComponentName
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.touchbridge.nfc.TouchBridgeHceService
import dev.touchbridge.ui.LabScreen
import dev.touchbridge.ui.LabViewModel
import dev.touchbridge.ui.Role
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: LabViewModel by viewModels()
    private val adapter get() = getSystemService(NfcManager::class.java)?.defaultAdapter
    private var appliedRole: Role? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LabScreen(model) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { if (it.role != appliedRole) applyRole(it.role) }
            }
        }
    }

    private fun applyRole(role: Role) {
        val nfc = adapter ?: return
        runCatching {
            nfc.disableReaderMode(this)
            if (packageManager.hasSystemFeature("android.hardware.nfc.hce")) {
                val emulation = CardEmulation.getInstance(nfc)
                emulation.unsetPreferredService(this)
                if (role == Role.EMULATE) {
                    check(emulation.setPreferredService(this, ComponentName(this, TouchBridgeHceService::class.java)))
                }
            }
            if (role == Role.SCAN) {
                nfc.enableReaderMode(this, model::onTag,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) })
            }
            appliedRole = role
        }.onFailure { appliedRole = null; model.roleFailure(role) }
    }

    override fun onPause() {
        // Cancels even on rotation: resuming always requires explicit user intent.
        model.stop()
        adapter?.let { nfc ->
            runCatching { nfc.disableReaderMode(this) }
            if (packageManager.hasSystemFeature("android.hardware.nfc.hce")) {
                runCatching { CardEmulation.getInstance(nfc).unsetPreferredService(this) }
            }
        }
        appliedRole = null
        super.onPause()
    }
}
