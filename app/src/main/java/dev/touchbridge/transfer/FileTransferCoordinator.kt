package dev.touchbridge.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class FilePhase { IDLE, PREPARING, OFFERED, INCOMING, TRANSFERRING, SAVING, WAITING_RECEIPT, SUCCESS, CANCELED, ERROR }
data class FileTransferState(
    val phase: FilePhase = FilePhase.IDLE,
    val offer: FileOffer? = null,
    val outgoing: Boolean = false,
    val bytes: Long = 0,
    val bytesPerSecond: Long = 0,
    val detail: String = "Choose a file, photo, or video to share.",
) {
    val busy get() = phase in setOf(FilePhase.PREPARING, FilePhase.OFFERED, FilePhase.INCOMING,
        FilePhase.TRANSFERRING, FilePhase.SAVING, FilePhase.WAITING_RECEIPT)
}

interface FileTransferStorage {
    suspend fun prepare(source: String): FileOffer
    suspend fun save(id: Long, destination: String, offer: FileOffer)
    fun release(id: Long)
    fun claim(id: Long) {}
    suspend fun deleteDestination(destination: String)
}
interface FileTransferLink {
    fun control(frame: FileProtocol.Frame, failed: () -> Unit)
    fun sendFile(id: Long, failed: () -> Unit)
    fun cancelFile(id: Long)
}
enum class FileUpdate { PROGRESS, SUCCESS, FAILURE, CANCELED }

/** One explicitly approved transfer at a time; main-thread callbacks and replaceable IO. */
class FileTransferCoordinator(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val storage: FileTransferStorage,
    private val link: FileTransferLink,
) {
    private val _state = MutableStateFlow(FileTransferState())
    val state = _state.asStateFlow()
    private var session = ""
    private var generation = 0L
    private var work: Job? = null
    private var timer: Job? = null
    private var destination: String? = null
    private var started = 0L
    private var lastProgress = 0L
    private var receivedFile = false
    private var fileComplete = false
    private var savedReceipt = false

    fun connected(id: String) { reset(); session = id }
    fun reset() {
        if (_state.value.busy) finish(FilePhase.CANCELED, "Connection ended. Transfer canceled.")
        generation++; work?.cancel(); timer?.cancel(); session = ""
        _state.value = FileTransferState()
    }
    fun offer(source: String) {
        if (session.isEmpty() || _state.value.busy) return
        val token = ++generation
        _state.value = FileTransferState(FilePhase.PREPARING, outgoing = true, detail = "Preparing a temporary copy and checking the file…")
        work = scope.launch {
            try {
                val offer = storage.prepare(source)
                if (token != generation || session.isEmpty()) { storage.release(offer.id); return@launch }
                _state.value = FileTransferState(FilePhase.OFFERED, offer, true, detail = "Waiting for the other phone to accept and choose a save location…")
                receivedFile = false; fileComplete = false; savedReceipt = false
                arm(120_000)
                control(FileProtocol.Kind.OFFER, offer)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) finish(FilePhase.ERROR, "Could not read or prepare the file. Check available storage and try another file.") }
        }
    }
    fun onControl(frame: FileProtocol.Frame) {
        if (session.isEmpty() || frame.session != session) return
        val current = _state.value
        if (frame.kind == FileProtocol.Kind.OFFER) {
            val offer = frame.offer ?: return
            if (current.busy) {
                link.control(FileProtocol.Frame(FileProtocol.Kind.REJECT, session, frame.id)) {}
                return
            }
            generation++
            receivedFile = false; fileComplete = false; savedReceipt = false
            _state.value = FileTransferState(FilePhase.INCOMING, offer, detail = "The other phone wants to send this file. Accept and choose where to save it.")
            arm(120_000)
            return
        }
        if (frame.id != current.offer?.id || !current.busy) return
        when (frame.kind) {
            FileProtocol.Kind.ACCEPT -> if (current.outgoing && current.phase == FilePhase.OFFERED) {
                startProgress()
                val token = generation
                link.sendFile(frame.id) { if (token == generation && _state.value.busy) finish(FilePhase.ERROR, "File could not be sent.") }
            }
            FileProtocol.Kind.REJECT -> finish(FilePhase.CANCELED, "The other phone declined the file or is busy.", notify = false)
            FileProtocol.Kind.CANCEL -> finish(FilePhase.CANCELED, "The other phone canceled the transfer.", notify = false)
            FileProtocol.Kind.SAVED -> if (current.outgoing && current.phase in setOf(FilePhase.TRANSFERRING, FilePhase.WAITING_RECEIPT)) {
                savedReceipt = true
                if (fileComplete) finish(FilePhase.SUCCESS, "The other phone verified and saved the file.", notify = false)
            }
            FileProtocol.Kind.OFFER -> Unit
        }
    }
    fun accept(destination: String) {
        if (_state.value.phase != FilePhase.INCOMING) {
            scope.launch { storage.deleteDestination(destination) }; return
        }
        this.destination = destination
        startProgress()
        control(FileProtocol.Kind.ACCEPT)
    }
    fun reject() { if (_state.value.phase == FilePhase.INCOMING) finish(FilePhase.CANCELED, "File declined.", kind = FileProtocol.Kind.REJECT) }
    fun cancel() { if (_state.value.busy) finish(FilePhase.CANCELED, "Transfer canceled.") }
    fun onFile(id: Long) {
        val current = _state.value
        if (current.offer?.id == id && receivedFile && !current.outgoing) return
        if (current.outgoing || current.offer?.id != id || current.phase != FilePhase.TRANSFERRING || receivedFile) {
            link.cancelFile(id); storage.release(id); return
        }
        storage.claim(id)
        receivedFile = true
        if (fileComplete) save()
    }
    fun onUpdate(id: Long, bytes: Long, total: Long, update: FileUpdate) {
        val current = _state.value
        if (current.offer?.id != id || current.phase != FilePhase.TRANSFERRING) return
        if (bytes < 0 || bytes > current.offer.size || total != current.offer.size) {
            finish(FilePhase.ERROR, "File size did not match the offer."); return
        }
        if (bytes > current.bytes) lastProgress = now()
        _state.value = current.copy(bytes = maxOf(current.bytes, bytes), bytesPerSecond = (bytes.toDouble() * 1000 / maxOf(1, now() - started)).toLong())
        when (update) {
            FileUpdate.SUCCESS -> {
                if (bytes != current.offer.size) { finish(FilePhase.ERROR, "File transfer was incomplete."); return }
                fileComplete = true
                if (current.outgoing) {
                    if (savedReceipt) finish(FilePhase.SUCCESS, "The other phone verified and saved the file.", notify = false)
                    else { _state.value = _state.value.copy(phase = FilePhase.WAITING_RECEIPT, detail = "Delivered. Waiting for the other phone to verify and save…"); arm(120_000) }
                } else if (receivedFile) save()
            }
            FileUpdate.FAILURE -> finish(FilePhase.ERROR, "File transfer failed. Check storage and the connection.")
            FileUpdate.CANCELED -> finish(FilePhase.CANCELED, "File transfer canceled.")
            else -> Unit
        }
    }
    private fun startProgress() {
        started = now(); lastProgress = started
        _state.value = _state.value.copy(phase = FilePhase.TRANSFERRING, detail = if (_state.value.outgoing) "Sending file…" else "Receiving file…")
        timer?.cancel()
        timer = scope.launch {
            while (true) { delay(1000); if (now() - lastProgress >= 60_000) { finish(FilePhase.ERROR, "File transfer stalled. Try again."); break } }
        }
    }
    private fun save() {
        val offer = _state.value.offer ?: return
        val target = destination ?: return
        val token = generation
        _state.value = _state.value.copy(phase = FilePhase.SAVING, detail = "Verifying and saving the received file…")
        arm(120_000)
        work = scope.launch {
            try {
                storage.save(offer.id, target, offer)
                if (token == generation) {
                    destination = null // Keep the completed document when clearing session state.
                    control(FileProtocol.Kind.SAVED)
                    if (_state.value.phase == FilePhase.SAVING) finish(FilePhase.SUCCESS, "File verified and saved to your chosen location.", notify = false)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) finish(FilePhase.ERROR, "Could not verify or save the file. Check storage and try again.") }
        }
    }
    private fun control(kind: FileProtocol.Kind, offer: FileOffer? = null) {
        val id = _state.value.offer?.id ?: return
        val token = generation
        link.control(FileProtocol.Frame(kind, session, id, offer)) {
            if (token == generation && _state.value.busy) finish(FilePhase.ERROR, "File control message could not be delivered.", notify = false)
        }
    }
    private fun arm(millis: Long) {
        timer?.cancel()
        timer = scope.launch { delay(millis); finish(FilePhase.ERROR, "File transfer timed out. Try again.") }
    }
    private fun finish(phase: FilePhase, detail: String, notify: Boolean = true, kind: FileProtocol.Kind = FileProtocol.Kind.CANCEL) {
        val offer = _state.value.offer
        if (notify && offer != null && session.isNotEmpty()) link.control(FileProtocol.Frame(kind, session, offer.id)) {}
        generation++; timer?.cancel()
        val previousWork = work
        work = null
        previousWork?.cancel()
        if (phase != FilePhase.SUCCESS && offer != null) link.cancelFile(offer.id)
        val target = destination
        // Wait until copying has stopped before closing handles or deleting a partial document.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                previousWork?.join()
                offer?.let { storage.release(it.id) }
                if (target != null) storage.deleteDestination(target)
            }
        }
        destination = null
        _state.value = _state.value.copy(phase = phase, detail = detail)
    }
}
