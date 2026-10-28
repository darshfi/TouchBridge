package dev.touchbridge.nearby

/** All callbacks may be asynchronous. Coordinator rejects callbacks from obsolete attempts. */
interface NearbyTransport {
    interface Listener {
        fun found(endpoint: String, name: String)
        fun lost(endpoint: String)
        fun initiated(endpoint: String, name: String, digits: String, incoming: Boolean)
        fun result(endpoint: String, success: Boolean)
        fun disconnected(endpoint: String)
        fun bytes(endpoint: String, bytes: ByteArray)
        fun file(endpoint: String, id: Long) {}
        fun fileUpdate(endpoint: String, id: Long, bytes: Long, total: Long, update: dev.touchbridge.transfer.FileUpdate) {}
    }
    fun advertise(name: String, listener: Listener, ready: () -> Unit, failed: (NearbyStartFailure) -> Unit)
    fun discover(listener: Listener, ready: () -> Unit, failed: (NearbyStartFailure) -> Unit)
    fun request(name: String, endpoint: String, failed: () -> Unit)
    fun accept(endpoint: String, failed: () -> Unit)
    fun reject(endpoint: String)
    fun send(endpoint: String, bytes: ByteArray, sent: () -> Unit, failed: () -> Unit)
    fun stopSearching()
    fun sendFile(endpoint: String, id: Long, failed: () -> Unit) { failed() }
    fun cancelFile(id: Long) {}
    fun close()
}
