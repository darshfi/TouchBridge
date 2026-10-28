package dev.touchbridge.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task

/** Owns one foreground attempt. Never retains an Activity or logs endpoint metadata/codes. */
@SuppressLint("MissingPermission") // Every entry is gated by NearbyPermissions in the foreground Activity.
class AndroidNearbyTransport(context: Context) : NearbyTransport {
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
    private var closed = false
    private lateinit var listener: NearbyTransport.Listener
    private data class Delivery(val sent: () -> Unit, val failed: () -> Unit)
    private val deliveries = mutableMapOf<Long, Delivery>()

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) = onMain {
            if (!closed) listener.initiated(endpointId, info.endpointName, info.authenticationDigits, info.isIncomingConnection)
        }
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) = onMain {
            if (!closed) listener.result(endpointId, resolution.status.isSuccess)
        }
        override fun onDisconnected(endpointId: String) = onMain { if (!closed) listener.disconnected(endpointId) }
    }
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) = onMain {
            if (closed) return@onMain
            if (payload.type == Payload.Type.BYTES) listener.bytes(endpointId, payload.asBytes() ?: byteArrayOf())
            else { runCatching { client.cancelPayload(payload.id) }; listener.bytes(endpointId, byteArrayOf()) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = onMain {
            if (closed) return@onMain
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> deliveries.remove(update.payloadId)?.sent?.invoke()
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> deliveries.remove(update.payloadId)?.failed?.invoke()
            }
        }
    }

    override fun advertise(name: String, listener: NearbyTransport.Listener, ready: () -> Unit, failed: () -> Unit) {
        this.listener = listener
        start({ client.startAdvertising(name, NearbyProtocol.SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()) }, ready, failed)
    }

    override fun discover(listener: NearbyTransport.Listener, ready: () -> Unit, failed: () -> Unit) {
        this.listener = listener
        val discovery = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) = onMain {
                if (!closed && info.serviceId == NearbyProtocol.SERVICE_ID) listener.found(endpointId, info.endpointName)
            }
            override fun onEndpointLost(endpointId: String) = onMain { if (!closed) listener.lost(endpointId) }
        }
        start({ client.startDiscovery(NearbyProtocol.SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build()) }, ready, failed)
    }

    private fun start(operation: () -> Task<Void>, ready: () -> Unit, failed: () -> Unit) {
        // Serialize starts across attempts. A late completion from a cancelled start must be
        // cleaned up before a new start, otherwise it could stop the new attempt's radios.
        StartQueue.enqueue {
            if (closed) { StartQueue.finished(); return@enqueue }
            try {
                operation().addOnCompleteListener { task ->
                    try {
                        if (closed) runCatching { stopSdk() }
                        else if (task.isSuccessful) ready() else failed()
                    } finally { StartQueue.finished() }
                }
            } catch (_: Exception) {
                if (!closed) failed()
                StartQueue.finished()
            }
        }
    }

    override fun request(name: String, endpoint: String, failed: () -> Unit) {
        runCatching { client.requestConnection(name, endpoint, lifecycle).addOnFailureListener { if (!closed) failed() } }
            .onFailure { if (!closed) failed() }
    }
    override fun accept(endpoint: String, failed: () -> Unit) {
        runCatching { client.acceptConnection(endpoint, payloads).addOnFailureListener { if (!closed) failed() } }
            .onFailure { if (!closed) failed() }
    }
    override fun reject(endpoint: String) { if (!closed) runCatching { client.rejectConnection(endpoint) } }

    override fun send(endpoint: String, bytes: ByteArray, sent: () -> Unit, failed: () -> Unit) {
        if (closed) return
        val payload = Payload.fromBytes(bytes)
        deliveries[payload.id] = Delivery(sent, failed)
        runCatching {
            client.sendPayload(endpoint, payload).addOnFailureListener {
                if (!closed) deliveries.remove(payload.id)?.failed?.invoke()
            }
        }.onFailure { if (!closed) deliveries.remove(payload.id)?.failed?.invoke() }
    }
    override fun stopSearching() { runCatching { client.stopAdvertising() }; runCatching { client.stopDiscovery() } }
    private fun stopSdk() { stopSearching(); client.stopAllEndpoints() }
    override fun close() {
        if (closed) return
        closed = true
        deliveries.clear()
        runCatching { stopSdk() }
    }

    private object StartQueue {
        private val queue = ArrayDeque<() -> Unit>()
        private var busy = false
        fun enqueue(block: () -> Unit) { queue.addLast(block); drain() }
        fun finished() { busy = false; drain() }
        private fun drain() {
            if (!busy && queue.isNotEmpty()) { busy = true; queue.removeFirst().invoke() }
        }
    }

    companion object {
        fun unavailableReason(context: Context): String? {
            if (NearbyPermissions.missing(context).isNotEmpty()) return "Nearby permissions are required. Grant them, then retry."
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) {
                return "Google Play services is unavailable or needs an update. NFC Lab remains available."
            }
            val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (bluetooth?.isEnabled != true) return "Turn on Bluetooth in system settings, then retry."
            if (context.getSystemService(WifiManager::class.java)?.isWifiEnabled != true) return "Turn on Wi-Fi in system settings, then retry. Internet is not required."
            if (Build.VERSION.SDK_INT <= 32) {
                val location = context.getSystemService(LocationManager::class.java)
                val enabled = if (Build.VERSION.SDK_INT >= 28) location?.isLocationEnabled == true
                    else location?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || location?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
                if (!enabled) return "Enable Location in system settings for nearby discovery on this Android version, then retry."
            }
            return null
        }
    }
}
