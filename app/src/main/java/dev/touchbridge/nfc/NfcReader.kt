package dev.touchbridge.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.io.Closeable
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

class NfcReader : Closeable {
    private val active = AtomicReference<IsoDep?>(null)
    private val enabled = AtomicBoolean(false)

    fun activate() { enabled.set(true) }

    /** Called on Dispatchers.IO. Timeout applies to each transceive. */
    fun read(tag: Tag): PeerSession {
        val channel = IsoDep.get(tag) ?: throw IllegalArgumentException("This tag does not support ISO-DEP. Use the other phone in Emulate mode.")
        check(active.compareAndSet(null, channel)) { "A tap is already in progress" }
        try {
            check(enabled.get()) { "Reader cancelled" }
            channel.connect()
            check(enabled.get()) { "Reader cancelled" }
            channel.timeout = 3000
            return NfcHandshake().exchange(ApduTransport(channel::transceive))
        } finally {
            active.compareAndSet(channel, null)
            runCatching { channel.close() }
        }
    }

    override fun close() {
        enabled.set(false)
        active.getAndSet(null)?.let { runCatching { it.close() } }
    }
}
