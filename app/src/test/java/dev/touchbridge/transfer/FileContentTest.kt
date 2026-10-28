package dev.touchbridge.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest

class FileContentTest {
    private fun hash(bytes: ByteArray) = FileContent.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    @Test fun copiesAndVerifiesMultipleBuffersAndEmptyFile() = runTest {
        for (bytes in listOf(byteArrayOf(), ByteArray(200_000) { (it % 251).toByte() })) {
            val output = ByteArrayOutputStream()
            FileContent.copyVerified(ByteArrayInputStream(bytes), output, bytes.size.toLong(), hash(bytes))
            assertArrayEquals(bytes, output.toByteArray())
        }
    }
    @Test fun rejectsCorruptionTruncationAndExcessData() = runTest {
        val bytes = byteArrayOf(1, 2, 3)
        for ((size, digest) in listOf(3L to hash(byteArrayOf(1, 2, 4)), 4L to hash(bytes), 2L to hash(bytes))) {
            val result = runCatching { FileContent.copyVerified(ByteArrayInputStream(bytes), ByteArrayOutputStream(), size, digest) }
            assertTrue(result.isFailure)
        }
    }
    @Test fun cancellationStopsBeforeAnotherBufferIsWritten() = runTest {
        val job = Job()
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Buffered reads only")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads++; job.cancel(); return 1
            }
        }
        val result = runCatching { withContext(job) { FileContent.copyVerified(input, ByteArrayOutputStream(), 10, "a".repeat(64)) } }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, reads)
    }
}
