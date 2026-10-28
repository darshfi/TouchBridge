package dev.touchbridge.ui

import android.nfc.Tag
import android.nfc.TagLostException
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.touchbridge.nfc.NfcReader
import dev.touchbridge.nfc.NfcSessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class LabViewModel : ViewModel() {
    private val _state = MutableStateFlow(LabState())
    val state = _state.asStateFlow()
    private val reader = NfcReader()
    private val reading = AtomicBoolean(false)
    private var epoch = 0L
    private var lastTap = 0L
    private var emulationGeneration: Long? = null

    init {
        viewModelScope.launch {
            NfcSessionRepository.sessions.status.collect { updateEmulationStatus() }
        }
        viewModelScope.launch {
            while (true) {
                delay(250)
                if (_state.value.role == Role.EMULATE && !_state.value.success) updateEmulationStatus()
            }
        }
    }

    private fun updateEmulationStatus() {
        val sessions = NfcSessionRepository.sessions
        val updated = _state.value.withEmulationStatus(sessions.status.value, emulationGeneration, sessions.remainingMillis())
        if (_state.value.role == Role.EMULATE && updated.role == Role.IDLE) stop(updated.title, updated.detail)
        else _state.value = updated
    }

    fun choose(role: Role) {
        stop()
        if (role == Role.EMULATE) {
            NfcSessionRepository.sessions.start()
            emulationGeneration = NfcSessionRepository.sessions.generation
        }
        if (role == Role.SCAN) reader.activate()
        _state.value = when (role) {
            Role.EMULATE -> LabState(role, "Hold phones together", "On the other phone, choose Scan. Keep both screens unlocked.", 60)
            Role.SCAN -> LabState(role, "Hold phones together", "On the other phone, choose Emulate. Align the NFC antennas on the backs of the phones.")
            Role.IDLE -> LabState()
        }
    }

    fun stop(title: String = "Ready to connect", detail: String = "Choose a role on each phone to test a real NFC exchange.") {
        epoch++
        emulationGeneration = null
        NfcSessionRepository.sessions.stop()
        reader.close()
        _state.value = LabState(title = title, detail = detail)
    }

    fun roleFailure(role: Role) {
        if (role == Role.EMULATE) stop("Could not start card emulation", "Check NFC and foreground service routing in system settings, then choose Emulate again.")
        else stop("Could not start NFC reader", "Enable NFC in Settings, return to TouchBridge, then choose Scan again.")
    }

    /** Reader callbacks may arrive on binder threads; serialize state changes on Main. */
    fun onTag(tag: Tag) {
        viewModelScope.launch {
            val now = SystemClock.elapsedRealtime()
            if (_state.value.role != Role.SCAN || now - lastTap < 1200 || !reading.compareAndSet(false, true)) return@launch
            lastTap = now
            val attempt = epoch
            _state.value = _state.value.copy(busy = true, success = false, title = "Reading session…", detail = "Keep the phones touching briefly.")
            val started = SystemClock.elapsedRealtime()
            try {
                val peer = withContext(Dispatchers.IO) { reader.read(tag) }
                if (epoch == attempt && _state.value.role == Role.SCAN) {
                    _state.value = _state.value.copy(busy = false, success = true,
                        title = "NFC handshake successful",
                        detail = "${peer.message}\nProtocol 1 · session …${peer.id.takeLast(4)} · ${SystemClock.elapsedRealtime() - started} ms\nValid for up to ${peer.remainingMillis / 1000}s at receipt. Peer identity is not verified.")
                    Log.i("TouchBridgeReader", "NFC handshake successful")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (epoch == attempt) {
                    val message = when (error) {
                        is TagLostException -> "Phones moved apart. Align antennas and tap again."
                        is IOException -> "NFC exchange interrupted or timed out. Check the other phone is in Emulate mode and tap again."
                        is IllegalArgumentException -> error.message ?: "Invalid NFC session. Tap again."
                        else -> "Could not read this peer. Check NFC and role selection, then retry."
                    }
                    _state.value = _state.value.copy(busy = false, title = "Tap did not complete", detail = message)
                    Log.w("TouchBridgeReader", "NFC exchange failed: ${error.javaClass.simpleName}")
                }
            } finally { reading.set(false) }
        }
    }

    override fun onCleared() { stop() }
}
