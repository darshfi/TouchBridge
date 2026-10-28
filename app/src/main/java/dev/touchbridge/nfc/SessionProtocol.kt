package dev.touchbridge.nfc

import java.nio.ByteBuffer
import java.security.SecureRandom

/** IDs are public correlation metadata, never proof of peer identity. */
data class PeerSession(val id: String, val remainingMillis: Long, val message: String)

object SessionProtocol {
    const val VERSION = 1
    const val LIFETIME_MS = 60_000L
    const val HELLO = "Hello from TouchBridge!"
    const val AID_HEX = "F0544F55434842524944474501"
    val aid: ByteArray get() = AID_HEX.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val select: ByteArray get() = byteArrayOf(0, 0xA4.toByte(), 4, 0, aid.size.toByte()) + aid + byteArrayOf(0)
    val getSession: ByteArray get() = byteArrayOf(0x80.toByte(), 0x10, 0, 0, 0)

    fun encode(id: ByteArray, remainingMillis: Long): ByteArray {
        require(id.size == 16 && id.any { it != 0.toByte() })
        require(remainingMillis in 1..LIFETIME_MS)
        return ByteBuffer.allocate(21 + HELLO.toByteArray(Charsets.UTF_8).size)
            .put(VERSION.toByte()).put(id).putInt(remainingMillis.toInt())
            .put(HELLO.toByteArray(Charsets.UTF_8)).array()
    }

    fun decode(body: ByteArray): PeerSession {
        require(body.size == 21 + HELLO.toByteArray(Charsets.UTF_8).size) { "Invalid session length" }
        val buffer = ByteBuffer.wrap(body)
        require(buffer.get().toInt() == VERSION) { "Unsupported protocol version" }
        val id = ByteArray(16).also { buffer.get(it) }
        require(id.any { it != 0.toByte() }) { "Invalid session identifier" }
        val remaining = buffer.int.toLong()
        require(remaining in 1..LIFETIME_MS) { "Expired or invalid session" }
        val message = ByteArray(buffer.remaining()).also { buffer.get(it) }.toString(Charsets.UTF_8)
        require(message == HELLO) { "Invalid demonstration message" }
        return PeerSession(id.joinToString("") { "%02x".format(it) }, remaining, message)
    }

    fun response(body: ByteArray = byteArrayOf(), status: Int = 0x9000) =
        body + byteArrayOf((status shr 8).toByte(), status.toByte())

    fun requireSuccess(response: ByteArray): ByteArray {
        require(response.size in 2..256) { "Invalid APDU response length" }
        val status = ((response[response.size - 2].toInt() and 255) shl 8) or
            (response.last().toInt() and 255)
        require(status == 0x9000) {
            when (status) {
                0x6985 -> "Peer session idle or expired. Start Emulate again."
                0x6986 -> "Peer requires SELECT before GET_SESSION."
                0x6A82 -> "TouchBridge AID not found. Check foreground role and wallet routing."
                else -> "Peer rejected APDU (%04X)".format(status)
            }
        }
        return response.copyOf(response.size - 2)
    }
}

/** In-memory only. Monotonic time prevents wall-clock changes extending a session. */
class SessionRegistry(private val now: () -> Long, private val random: SecureRandom = SecureRandom()) {
    private var id: ByteArray? = null
    private var expiry = 0L
    var generation = 0L
        private set

    @Synchronized fun start() {
        id = ByteArray(16).also { random.nextBytes(it) }
        expiry = now() + SessionProtocol.LIFETIME_MS
        generation++
    }
    @Synchronized fun stop() { id = null; expiry = 0; generation++ }
    @Synchronized fun remainingMillis(): Long = if (id == null) 0 else (expiry - now()).coerceAtLeast(0)
    @Synchronized fun payload(): ByteArray? {
        val current = id ?: return null
        val remaining = remainingMillis()
        return if (remaining > 0) SessionProtocol.encode(current, remaining) else null
    }
}

/** Strict short APDU dialect; no arbitrary data, extended lengths, or logical channels. */
class ApduEngine(private val sessions: SessionRegistry, private val onRead: () -> Unit = {}) {
    private var selectedGeneration: Long? = null
    @Synchronized fun reset() { selectedGeneration = null }
    @Synchronized fun process(command: ByteArray?): ByteArray {
        fun error(status: Int) = SessionProtocol.response(status = status)
        if (command == null || command.size !in 4..261) { reset(); return error(0x6700) }
        val cla = command[0].toInt() and 255
        val ins = command[1].toInt() and 255
        if (ins == 0xA4 && cla == 0) {
            reset()
            if (command.size < 5) return error(0x6700)
            if (command[2] != 4.toByte() || command[3] != 0.toByte()) return error(0x6A86)
            val length = command[4].toInt() and 255
            if (length == 0 || command.size !in (5 + length)..(6 + length)) return error(0x6700)
            if (command.size == 6 + length && command.last() != 0.toByte()) return error(0x6A86)
            if (!command.copyOfRange(5, 5 + length).contentEquals(SessionProtocol.aid)) return error(0x6A82)
            if (sessions.payload() == null) return error(0x6985)
            selectedGeneration = sessions.generation
            return SessionProtocol.response()
        }
        if (cla != 0x80) return error(0x6E00)
        if (ins != 0x10) return error(0x6D00)
        if (command.size != 5) return error(0x6700)
        if (!command.contentEquals(SessionProtocol.getSession)) return error(0x6A86)
        if (selectedGeneration != sessions.generation) return error(0x6986)
        val payload = sessions.payload() ?: return error(0x6985)
        onRead()
        return SessionProtocol.response(payload)
    }
}

fun interface ApduTransport { fun transceive(command: ByteArray): ByteArray }

class NfcHandshake {
    fun exchange(transport: ApduTransport): PeerSession {
        require(SessionProtocol.requireSuccess(transport.transceive(SessionProtocol.select)).isEmpty()) {
            "Unexpected SELECT response data"
        }
        return SessionProtocol.decode(SessionProtocol.requireSuccess(transport.transceive(SessionProtocol.getSession)))
    }
}
