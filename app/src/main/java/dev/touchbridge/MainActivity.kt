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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.touchbridge.nfc.TouchBridgeHceService
import dev.touchbridge.ui.LabScreen
import dev.touchbridge.ui.LabViewModel
import dev.touchbridge.ui.Role
import dev.touchbridge.nearby.NearbyPermissions
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: LabViewModel by viewModels()
    private val adapter get() = getSystemService(NfcManager::class.java)?.defaultAdapter
    private var appliedRole: Role? = null
    private var pendingNearbyRole: Role? = null
    private var pickerToken = -1L
    private var incomingFileId: Long? = null
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        model.pickedFile(uri?.toString(), pickerToken)
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        model.pickedDestination(uri?.toString(), pickerToken, incomingFileId)
        incomingFileId = null
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (NearbyPermissions.missing(this).isEmpty()) startPendingNearbyRole()
        else { pendingNearbyRole = null; model.permissionsDenied() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LabScreen(model, ::chooseNearbyRole, ::chooseFile, ::chooseDestination) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                startPendingNearbyRole()
                model.state.collect { if (it.role != appliedRole) applyRole(it.role) }
            }
        }
    }

    private fun chooseFile() {
        pickerToken = model.beginFilePicker() ?: return
        runCatching { openFile.launch(arrayOf("*/*")) }.onFailure { model.pickedFile(null, pickerToken) }
    }
    private fun chooseDestination() {
        val offer = model.files.state.value.offer ?: return
        pickerToken = model.beginFilePicker() ?: return
        incomingFileId = offer.id
        runCatching { saveFile.launch(offer.name) }.onFailure { model.pickedDestination(null, pickerToken, offer.id) }
    }

    private fun chooseNearbyRole(role: Role) {
        model.stop()
        pendingNearbyRole = role
        val missing = NearbyPermissions.missing(this)
        if (missing.isEmpty()) startPendingNearbyRole()
        else permissions.launch(missing.toTypedArray())
    }

    private fun startPendingNearbyRole() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || NearbyPermissions.missing(this).isNotEmpty()) return
        val role = pendingNearbyRole ?: return
        pendingNearbyRole = null
        model.choose(role, useNearby = true)
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
        // Only the explicitly launched system document picker may temporarily keep Nearby alive.
        if (!model.pickerPending || isChangingConfigurations) model.stop()
        adapter?.let { nfc ->
            runCatching { nfc.disableReaderMode(this) }
            if (packageManager.hasSystemFeature("android.hardware.nfc.hce")) {
                runCatching { CardEmulation.getInstance(nfc).unsetPreferredService(this) }
            }
        }
        appliedRole = null
        super.onPause()
    }
    override fun onDestroy() {
        if (isChangingConfigurations) model.stop()
        super.onDestroy()
    }
}
