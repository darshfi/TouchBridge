package dev.touchbridge.nearby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class NearbyPhase { IDLE, STARTING, ADVERTISING, DISCOVERING, REQUESTING, VERIFYING, MATCHING, CONNECTED, ERROR }
data class NearbyState(
    val phase: NearbyPhase = NearbyPhase.IDLE,
    val detail: String = "Choose Advertise on one phone and Scan on the other.",
    val digits: String = "",
    val approved: Boolean = false,
    val sending: Boolean = false,
    val sent: Boolean = false,
    val received: String = "",
)

/** Main-thread coordinator. No Android dependencies; transports and monotonic clocks are replaceable. */
class NearbyCoordinator(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val transportFactory: () -> NearbyTransport,
    private val nfcWasRead: () -> Boolean,
) {
    private val _state = MutableStateFlow(NearbyState())
    val state = _state.asStateFlow()
    private var transport: NearbyTransport? = null
    private var attempt = 0L
    private var deadline = 0L
    private var stageDeadline = 0L
    private var session = ""
    private var advertiser = false
    private var endpoint: String? = null
    private var timeout: Job? = null
    private var pendingHello = false
    private var earlyMatch = false
    private var sendTimeout: Job? = null

    fun stop() { cleanup(); _state.value = NearbyState() }
    fun unavailable(message: String) { cleanup(); _state.value = NearbyState(NearbyPhase.ERROR, message) }

    private fun cleanup() {
        attempt++
        timeout?.cancel()
        timeout = null
        sendTimeout?.cancel()
        sendTimeout = null
        transport?.close()
        transport = null
        endpoint = null
        session = ""
        pendingHello = false
        earlyMatch = false
    }

    fun start(sessionId: String, remainingMillis: Long, advertise: Boolean) {
        stop()
        if (!NearbyProtocol.validSession(sessionId) || remainingMillis !in 1..60_000) {
            unavailable("The NFC session expired or is invalid. Start again and retap.")
            return
        }
        session = sessionId
        advertiser = advertise
        deadline = now() + remainingMillis
        stageDeadline = deadline
        val token = attempt
        val port = try { transportFactory() } catch (_: Exception) {
            unavailable("Google Play services could not create a Nearby connection. Check its availability and retry.")
            return
        }
        transport = port
        _state.value = NearbyState(NearbyPhase.STARTING, "Starting Nearby…")
        fun event(block: () -> Unit) { scope.launch { if (attempt == token && transport === port) block() } }
        val listener = object : NearbyTransport.Listener {
            override fun found(endpoint: String, name: String) = event {
                if (advertiser || _state.value.phase !in setOf(NearbyPhase.STARTING, NearbyPhase.DISCOVERING) || !validDeadline() ||
                    !NearbyProtocol.matches(name, session, advertiser = true)) return@event
                this@NearbyCoordinator.endpoint = endpoint
                stageDeadline = minOf(deadline, now() + 30_000)
                _state.value = NearbyState(NearbyPhase.REQUESTING, "Found the tapped phone. Requesting a connection…")
                port.stopSearching()
                port.request(NearbyProtocol.endpointName(session, advertiser = false), endpoint) {
                    event { fail("Connection request failed. Check Bluetooth/Wi-Fi and retap to retry.") }
                }
            }
            override fun lost(endpoint: String) = event {
                if (this@NearbyCoordinator.endpoint == endpoint && _state.value.phase == NearbyPhase.REQUESTING) {
                    fail("The tapped phone went out of range. Start again and retap.")
                }
            }
            override fun initiated(endpoint: String, name: String, digits: String, incoming: Boolean) = event {
                val allowedPhase = if (advertiser) _state.value.phase in setOf(NearbyPhase.STARTING, NearbyPhase.ADVERTISING)
                    else _state.value.phase == NearbyPhase.REQUESTING && this@NearbyCoordinator.endpoint == endpoint
                if (!allowedPhase || incoming != advertiser || !validDeadline() ||
                    (advertiser && !nfcWasRead()) || !NearbyProtocol.matches(name, session, !advertiser) ||
                    !Regex("[0-9]{4}").matches(digits)) {
                    port.reject(endpoint)
                    return@event
                }
                this@NearbyCoordinator.endpoint = endpoint
                stageDeadline = minOf(deadline, now() + 30_000)
                _state.value = NearbyState(NearbyPhase.VERIFYING,
                    "Compare this code on both phones. Accept only if the codes match.", digits)
            }
            override fun result(endpoint: String, success: Boolean) = event {
                if (this@NearbyCoordinator.endpoint != endpoint) return@event
                if (success && _state.value.phase in setOf(NearbyPhase.MATCHING, NearbyPhase.CONNECTED)) return@event
                if (!success || !_state.value.approved || _state.value.phase != NearbyPhase.VERIFYING || !validDeadline()) {
                    fail("Connection was rejected, expired, or interrupted. Start again to retry.")
                    return@event
                }
                port.stopSearching()
                _state.value = _state.value.copy(phase = NearbyPhase.MATCHING, digits = "",
                    detail = "Both phones approved. Checking the NFC session over the encrypted link…")
                port.send(endpoint, NearbyProtocol.encode(NearbyProtocol.Kind.MATCH, session), {}, {
                    event { fail("Session verification could not be sent. Start again.") }
                })
                if (earlyMatch && transport === port) completeMatch()
            }
            override fun disconnected(endpoint: String) = event {
                if (this@NearbyCoordinator.endpoint == endpoint) fail("The other phone disconnected. Start again and retap to reconnect.")
            }
            override fun bytes(endpoint: String, bytes: ByteArray) = event {
                if (this@NearbyCoordinator.endpoint != endpoint || !_state.value.approved ||
                    _state.value.phase !in setOf(NearbyPhase.VERIFYING, NearbyPhase.MATCHING, NearbyPhase.CONNECTED)) return@event
                val frame = runCatching { NearbyProtocol.decode(bytes) }.getOrNull()
                if (frame == null || frame.session != session) {
                    fail("The peer sent an invalid or mismatched session message. Connection closed.")
                    return@event
                }
                when (frame.kind) {
                    NearbyProtocol.Kind.MATCH -> {
                        if (_state.value.phase == NearbyPhase.CONNECTED) return@event
                        if (!validDeadline()) { fail("The tap session expired before verification. Retap to retry."); return@event }
                        if (_state.value.phase == NearbyPhase.VERIFYING) earlyMatch = true
                        else completeMatch()
                    }
                    NearbyProtocol.Kind.HELLO -> {
                        // BYTES payloads can arrive out of order: hold one bounded hello until MATCH arrives.
                        if (_state.value.phase != NearbyPhase.CONNECTED) pendingHello = true
                        else _state.value = _state.value.copy(received = NearbyProtocol.HELLO)
                    }
                }
            }
        }
        val ready = { event {
            if (_state.value.phase == NearbyPhase.STARTING) _state.value = NearbyState(
                if (advertiser) NearbyPhase.ADVERTISING else NearbyPhase.DISCOVERING,
                if (advertiser) "Advertising. Tap with the other phone in Scan mode." else "Looking for the exact session read over NFC…")
        } }
        val failed: (NearbyStartFailure) -> Unit = { failure -> event { fail(failure.description()) } }
        if (advertiser) port.advertise(NearbyProtocol.endpointName(session, true), listener, ready, failed)
        else port.discover(listener, ready, failed)
        timeout = scope.launch {
            while (true) {
                delay(250)
                if (attempt != token) break
                if (!validDeadline()) { fail("Pairing timed out. Start a fresh session and tap again."); break }
            }
        }
    }

    private fun validDeadline() = now() < deadline && now() < stageDeadline
    private fun fail(message: String) = unavailable(message)

    private fun completeMatch() {
        _state.value = _state.value.copy(phase = NearbyPhase.CONNECTED, digits = "",
            detail = "Connected and approved on both phones. You can move the phones apart.",
            received = if (pendingHello) NearbyProtocol.HELLO else "")
        pendingHello = false
        earlyMatch = false
        timeout?.cancel()
    }

    fun approve() {
        val peer = endpoint ?: return
        if (_state.value.phase != NearbyPhase.VERIFYING || _state.value.approved) return
        if (!validDeadline()) { fail("The session expired before approval. Retap to retry."); return }
        _state.value = _state.value.copy(approved = true, detail = "You approved. Waiting for the other phone to accept…")
        val token = attempt
        transport?.accept(peer) { scope.launch { if (attempt == token) fail("Could not accept the connection. Start again.") } }
    }

    fun reject() {
        endpoint?.let { transport?.reject(it) }
        unavailable("Connection rejected. No message was shared.")
    }

    fun sendHello() {
        val peer = endpoint ?: return
        if (_state.value.phase != NearbyPhase.CONNECTED || !_state.value.approved || _state.value.sending) return
        val token = attempt
        _state.value = _state.value.copy(sending = true, sent = false)
        sendTimeout?.cancel()
        sendTimeout = scope.launch {
            delay(10_000)
            if (attempt == token && _state.value.sending) fail("Message delivery timed out. Reconnect and try again.")
        }
        transport?.send(peer, NearbyProtocol.encode(NearbyProtocol.Kind.HELLO, session), {
            scope.launch { if (attempt == token) {
                sendTimeout?.cancel()
                _state.value = _state.value.copy(sending = false, sent = true)
            } }
        }, { scope.launch { if (attempt == token) fail("Message delivery failed. Start again and retap.") } })
    }
}
