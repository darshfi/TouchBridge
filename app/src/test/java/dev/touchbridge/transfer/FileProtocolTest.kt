package dev.touchbridge.transfer

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class FileProtocolTest {
    private val session = "0123456789abcdef0123456789abcdef"
    private val offer = FileOffer(-42, "photo.jpg", 5_000_000_000, "image/jpeg", "a".repeat(64))
    @Test fun roundTripsAllFramesAndLargeSizes() {
        FileProtocol.Kind.entries.forEach { kind ->
            val frame = FileProtocol.Frame(kind, session, offer.id, if (kind == FileProtocol.Kind.OFFER) offer else null)
            assertEquals(frame, FileProtocol.decode(FileProtocol.encode(frame)))
        }
    }
    @Test fun stripsPathsAndControlCharactersFromDisplayNames() {
        assertEquals("secret.txt", FileProtocol.safeName("../../secret.txt"))
        assertEquals("photo.jpg", FileProtocol.safeName("C:\\Users\\photo.jpg"))
        assertEquals("evilname", FileProtocol.safeName("evil\nname"))
        assertEquals("received-file", FileProtocol.safeName(".."))
        assertEquals(120, FileProtocol.safeName("a".repeat(200)).length)
    }
    @Test fun rejectsInvalidMetadataAndTrailingBytes() {
        listOf(offer.copy(name = "../bad"), offer.copy(size = -1), offer.copy(mime = "*/*"),
            offer.copy(sha256 = "not a hash"), offer.copy(name = "a\u0000b")).forEach { bad ->
            assertTrue(runCatching { FileProtocol.encode(FileProtocol.Frame(FileProtocol.Kind.OFFER, session, bad.id, bad)) }.isFailure)
        }
        val bytes = FileProtocol.encode(FileProtocol.Frame(FileProtocol.Kind.OFFER, session, offer.id, offer))
        assertTrue(runCatching { FileProtocol.decode(bytes + 0) }.isFailure)
        for (size in 0 until bytes.size) assertTrue(runCatching { FileProtocol.decode(bytes.copyOf(size)) }.isFailure)
    }
    @Test fun boundedRandomMalformedFramesNeverDecodeUnvalidatedMetadata() {
        val random = Random(732)
        repeat(2000) {
            val bytes = random.nextBytes(random.nextInt(0, 1400))
            val decoded = runCatching { FileProtocol.decode(bytes) }.getOrNull()
            decoded?.offer?.let(FileProtocol::validate)
        }
        assertFalse(FileProtocol.recognizes("TB1|HELLO".toByteArray()))
    }
}
