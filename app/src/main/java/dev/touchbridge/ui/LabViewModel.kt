package dev.touchbridge.ui

import android.nfc.Tag
import android.app.Application
import android.nfc.TagLostException
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.touchbridge.nfc.NfcReader
import dev.touchbridge.nfc.NfcSessionRepository
import dev.touchbridge.nearby.AndroidNearbyTransport
import dev.touchbridge.nearby.NearbyCoordinator
import dev.touchbridge.nearby.NearbyPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import dev.touchbridge.transfer.*

class LabViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(LabState())
    val state = _state.asStateFlow()
    private val reader = NfcReader()
    private val reading = AtomicBoolean(false)
    private var epoch = 0L
    private var lastTap = 0L
    private var emulationGeneration: Long? = null
    private var wireless = false
    private var lastNearbyPhase: NearbyPhase? = null
    private val fileStorage = AndroidFileStorage(application)
    val nearby = NearbyCoordinator(viewModelScope, SystemClock::elapsedRealtime,
        { AndroidNearbyTransport(application, fileStorage) }, { NfcSessionRepository.sessions.status.value.served })
    val files = FileTransferCoordinator(viewModelScope, SystemClock::elapsedRealtime, fileStorage, object : FileTransferLink {
        override fun control(frame: FileProtocol.Frame, failed: () -> Unit) = nearby.sendFileControl(frame, failed)
        override fun sendFile(id: Long, failed: () -> Unit) = nearby.sendFile(id, failed)
        override fun cancelFile(id: Long) = nearby.cancelFile(id)
    })
    private var pickerTimeout: kotlinx.coroutines.Job? = null
    var pickerPending = false
        private set
    private var pickerSession = 0L

    init {
        nearby.files = files
        viewModelScope.launch {
            NfcSessionRepository.sessions.status.collect { updateEmulationStatus() }
        }
        viewModelScope.launch {
            nearby.state.collect { connection ->
                if (connection.phase != lastNearbyPhase) {
                    lastNearbyPhase = connection.phase
                    Log.i("TouchBridgeNearby", "State: ${connection.phase}")
                }
                if (wireless && connection.phase == NearbyPhase.ADVERTISING && _state.value.role == Role.IDLE) {
                    val generation = emulationGeneration
                    if (generation != null && NfcSessionRepository.sessions.activate(generation)) {
                        _state.value = LabState(Role.EMULATE, "Ready to tap", "Nearby is advertising. Tap the other phone in Scan + connect mode.",
                            (NfcSessionRepository.sessions.remainingMillis() + 999) / 1000)
                    }
                }
                if (wireless && connection.phase == NearbyPhase.ERROR) {
                    wireless = false
                    emulationGeneration = null
                    NfcSessionRepository.sessions.stop()
                    reader.close()
                    _state.value = LabState(title = "Nearby setup or connection failed", detail = "Read the Nearby error above. Start a fresh session after resolving it.")
                }
                if (wireless && _state.value.role != Role.IDLE && connection.phase in setOf(NearbyPhase.CONNECTED, NearbyPhase.ERROR)) {
                    // Preserve the Nearby result while releasing NFC routing and invalidating its token.
                    emulationGeneration = null
                    NfcSessionRepository.sessions.stop()
                    reader.close()
                    _state.value = _state.value.copy(role = Role.IDLE)
                }
            }
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

    fun choose(role: Role, useNearby: Boolean = false) {
        stop()
        if (useNearby) {
            val error = runCatching { AndroidNearbyTransport.unavailableReason(getApplication()) }
                .getOrElse { "Nearby access is unavailable. Check permissions and system settings, then retry." }
            if (error != null) { nearby.unavailable(error); return }
        }
        wireless = useNearby
        if (role == Role.EMULATE) {
            NfcSessionRepository.sessions.start(serveImmediately = !useNearby)
            emulationGeneration = NfcSessionRepository.sessions.generation
        }
        if (role == Role.SCAN) reader.activate()
        _state.value = when (role) {
            Role.EMULATE -> if (useNearby) LabState(title = "Starting Nearby…", detail = "Wait for Ready to tap before touching the phones.")
                else LabState(role, "Hold phones together", "On the other phone, choose Scan. Keep both screens unlocked.", 60)
            Role.SCAN -> LabState(role, "Hold phones together", "On the other phone, choose Emulate. Align the NFC antennas on the backs of the phones.")
            Role.IDLE -> LabState()
        }
        if (useNearby && role == Role.EMULATE) {
            val session = NfcSessionRepository.sessions.currentSession() ?: return
            nearby.start(session.id, session.remainingMillis, advertise = true)
        }
    }

    fun stop(title: String = "Ready to connect", detail: String = "Choose a role on each phone to test a real NFC exchange.") {
        pickerTimeout?.cancel()
        pickerPending = false
        epoch++
        wireless = false
        nearby.stop()
        emulationGeneration = null
        NfcSessionRepository.sessions.stop()
        reader.close()
        _state.value = LabState(title = title, detail = detail)
    }

    fun beginFilePicker(): Long? {
        if (nearby.state.value.phase != NearbyPhase.CONNECTED || pickerPending) return null
        pickerPending = true
        pickerSession = epoch
        pickerTimeout = viewModelScope.launch { delay(120_000); if (pickerPending) stop("Session ended", "The file picker timed out. Reconnect to try again.") }
        return pickerSession
    }
    fun pickedFile(source: String?, token: Long) {
        val valid = finishPicker(token)
        if (valid && source != null) files.offer(source)
    }
    fun pickedDestination(destination: String?, token: Long, id: Long?) {
        val valid = finishPicker(token) && id == files.state.value.offer?.id
        if (destination != null) {
            if (valid) files.accept(destination)
            else viewModelScope.launch { fileStorage.deleteDestination(destination) }
        } else if (valid) files.reject()
    }
    private fun finishPicker(token: Long): Boolean {
        pickerTimeout?.cancel(); pickerPending = false
        return token == epoch && nearby.state.value.phase == NearbyPhase.CONNECTED
    }

    fun permissionsDenied() = nearby.unavailable("Nearby permissions were not granted. You can retry or enable them in App settings. NFC Lab still works without them.")

    fun roleFailure(role: Role) {
        if (role == Role.EMULATE) stop("Could not start card emulation", "Check NFC and foreground service routing in system settings, then choose Emulate again.")
        else stop("Could not start NFC reader", "Enable NFC in Settings, return to TouchBridge, then choose Scan again.")
    }

    /** Reader callbacks may arrive on binder threads; serialize state changes on Main. */
    fun onTag(tag: Tag) {
        viewModelScope.launch {
            val now = SystemClock.elapsedRealtime()
            if (wireless && nearby.state.value.phase != NearbyPhase.IDLE) return@launch
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
                    if (wireless) {
                        // Conservative receipt deadline: never extend the emulator's remaining TTL.
                        val remaining = peer.remainingMillis - (SystemClock.elapsedRealtime() - started)
                        nearby.start(peer.id, remaining, advertise = false)
                    }
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
