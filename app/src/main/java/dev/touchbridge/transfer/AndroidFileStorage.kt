package dev.touchbridge.transfer

import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.google.android.gms.nearby.connection.Payload
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** App-private outgoing snapshots; incoming SDK URIs are never treated as filesystem paths. */
class AndroidFileStorage(context: Context) : FileTransferStorage {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.cacheDir, "outgoing-transfers").apply { mkdirs() }
    private data class Outgoing(val file: File, val payload: Payload)
    private val outgoing = ConcurrentHashMap<Long, Outgoing>()
    private val incoming = ConcurrentHashMap<Long, Payload>()
    private val claimed = ConcurrentHashMap.newKeySet<Long>()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { directory.listFiles()?.forEach { it.delete() } } // Remove snapshots left by process death.

    fun outgoingPayload(id: Long): Payload? = outgoing[id]?.payload
    fun receive(payload: Payload) { incoming.putIfAbsent(payload.id, payload) }
    override fun claim(id: Long) { claimed.add(id) }
    fun releaseUnclaimed(id: Long) { if (id !in claimed && incoming.containsKey(id)) release(id) }

    override suspend fun prepare(source: String): FileOffer {
        var staged: File? = null
        var registered: Long? = null
        try {
            return withContext(Dispatchers.IO) {
                val uri = source.toUri()
                require(uri.scheme == "content")
                var name = "shared-file"
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
                val file = File.createTempFile("send-", ".tmp", directory)
                staged = file
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                resolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (directory.usableSpace < count + 4L * 1024 * 1024) throw IOException("Insufficient staging space")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            size += count
                        }
                    }
                } ?: throw IOException("Source unavailable")
                currentCoroutineContext().ensureActive()
                val mime = resolver.getType(uri)?.takeIf {
                    it.length <= 100 && Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+").matches(it)
                } ?: "application/octet-stream"
                val payload = Payload.fromFile(file)
                outgoing[payload.id] = Outgoing(file, payload)
                registered = payload.id
                FileOffer(payload.id, FileProtocol.safeName(name), size, mime, FileContent.hex(digest.digest()))
            }
        } catch (error: Exception) {
            // withContext has prompt cancellation: its result may be discarded after IO finished.
            registered?.let(::release)
            staged?.delete()
            throw error
        }
    }

    override suspend fun save(id: Long, destination: String, offer: FileOffer) = withContext(Dispatchers.IO) {
        val payload = incoming[id] ?: throw IOException("Missing file")
        val uri = payload.asFile()?.asUri() ?: throw IOException("Missing received URI")
        resolver.openInputStream(uri)?.use { input ->
            resolver.openOutputStream(destination.toUri(), "wt")?.use { output ->
                FileContent.copyVerified(input, output, offer.size, offer.sha256)
            } ?: throw IOException("Destination unavailable")
        } ?: throw IOException("Received file unavailable")
    }

    override fun release(id: Long) {
        claimed.remove(id)
        val sent = outgoing.remove(id)
        val received = incoming.remove(id)
        cleanup.launch {
            sent?.let { item -> runCatching { item.payload.close() }; item.file.delete() }
            received?.let { payload -> discardIncoming(payload) }
        }
    }
    private fun discardIncoming(payload: Payload) {
        val uri = payload.asFile()?.asUri()
        if (uri != null) runCatching { resolver.delete(uri, null, null) }
        runCatching { payload.close() }
    }
    override suspend fun deleteDestination(destination: String): Unit = withContext(Dispatchers.IO) {
        val uri = destination.toUri()
        runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrElse { runCatching { resolver.delete(uri, null, null) }; false }
        Unit
    }
}
