package dev.touchbridge.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Bounded-memory copying; callers own streams and run this on an IO dispatcher. */
object FileContent {
    suspend fun copyVerified(input: InputStream, output: OutputStream, expectedSize: Long, expectedHash: String) {
        require(expectedSize >= 0)
        val digest = MessageDigest.getInstance("SHA-256")
        val context = currentCoroutineContext()
        var size = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            context.ensureActive()
            val count = input.read(buffer)
            if (count == -1) break
            context.ensureActive()
            check(size <= expectedSize - count) { "File exceeds offered size" }
            output.write(buffer, 0, count); digest.update(buffer, 0, count); size += count
        }
        output.flush()
        check(size == expectedSize && hex(digest.digest()) == expectedHash) { "File verification failed" }
    }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
