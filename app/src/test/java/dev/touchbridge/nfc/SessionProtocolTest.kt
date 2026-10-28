package dev.touchbridge.nfc

import org.junit.Assert.*
import org.junit.Test

class SessionProtocolTest {
    @Test fun preparedWirelessSessionCannotBeReadUntilAdvertisingIsReady() {
        val sessions = SessionRegistry({ 1000L })
        sessions.start(serveImmediately = false)
        val engine = ApduEngine(sessions)
        assertNotNull(sessions.currentSession())
        assertNull(sessions.payload())
        assertFalse(sessions.markServed(sessions.generation))
        assertThrows(IllegalArgumentException::class.java) { SessionProtocol.requireSuccess(engine.process(SessionProtocol.select)) }
        assertTrue(sessions.activate(sessions.generation))
        assertEquals(SessionProtocol.HELLO, NfcHandshake().exchange(ApduTransport(engine::process)).message)
    }
    @Test fun lateAdvertisingReadyCannotActivateStoppedOrExpiredSession() {
        var time = 0L
        val sessions = SessionRegistry({ time })
        sessions.start(serveImmediately = false)
        val previous = sessions.generation
        sessions.stop()
        sessions.start(serveImmediately = false)
        assertFalse(sessions.activate(previous))
        assertNull(sessions.payload())
        time = 60000
        assertFalse(sessions.activate(sessions.generation))
        assertNull(sessions.currentSession())
        assertNull(sessions.payload())
    }
    private var now = 1000L
    private fun registry() = SessionRegistry({ now })
    private val id = ByteArray(16) { (it + 1).toByte() }
    private fun status(response: ByteArray) = ((response[response.size - 2].toInt() and 255) shl 8) or (response.last().toInt() and 255)

    @Test fun selectMatchesDocumentedWireBytes() {
        val expected = "00 A4 04 00 0D F0 54 4F 55 43 48 42 52 49 44 47 45 01 00"
            .split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertArrayEquals(expected, SessionProtocol.select)
        assertEquals(44, SessionProtocol.encode(id, 60000).size)
    }

    @Test fun encodingRoundTrip() {
        val decoded = SessionProtocol.decode(SessionProtocol.encode(id, 12345))
        assertEquals("0102030405060708090a0b0c0d0e0f10", decoded.id)
        assertEquals(12345L, decoded.remainingMillis)
        assertEquals(SessionProtocol.HELLO, decoded.message)
    }

    @Test fun completeExchangeUsesSelectThenGet() {
        val sessions = registry().apply { start() }
        val engine = ApduEngine(sessions)
        var calls = 0
        val peer = NfcHandshake().exchange(ApduTransport { calls++; engine.process(it) })
        assertEquals(2, calls)
        assertEquals(SessionProtocol.HELLO, peer.message)
        assertEquals(60000L, peer.remainingMillis)
    }

    @Test fun idleAndExpiredSessionsAreRejected() {
        val sessions = registry()
        val engine = ApduEngine(sessions)
        assertEquals(0x6985, status(engine.process(SessionProtocol.select)))
        sessions.start()
        assertEquals(0x9000, status(engine.process(SessionProtocol.select)))
        now += 60000
        assertEquals(0x6985, status(engine.process(SessionProtocol.getSession)))
        assertNull(sessions.payload())
    }

    @Test fun stopAndRestartInvalidateOldSelectionAndIdentifier() {
        val sessions = registry().apply { start() }
        val first = SessionProtocol.decode(sessions.payload()!!).id
        val engine = ApduEngine(sessions)
        engine.process(SessionProtocol.select)
        sessions.stop()
        assertEquals(0x6986, status(engine.process(SessionProtocol.getSession)))
        sessions.start()
        assertNotEquals(first, SessionProtocol.decode(sessions.payload()!!).id)
        assertEquals(0x6986, status(engine.process(SessionProtocol.getSession)))
        assertEquals(0x9000, status(engine.process(SessionProtocol.select)))
    }

    @Test fun deactivationRequiresNewSelection() {
        val engine = ApduEngine(registry().apply { start() })
        engine.process(SessionProtocol.select)
        engine.reset()
        assertEquals(0x6986, status(engine.process(SessionProtocol.getSession)))
        assertEquals(SessionProtocol.HELLO, NfcHandshake().exchange(ApduTransport(engine::process)).message)
    }

    @Test fun optionalSelectLeAndStrictParameters() {
        val engine = ApduEngine(registry().apply { start() })
        assertEquals(0x9000, status(engine.process(SessionProtocol.select.dropLast(1).toByteArray())))
        assertEquals(0x6A86, status(engine.process(SessionProtocol.select.also { it[it.lastIndex] = 1 })))
        assertEquals(0x6A86, status(engine.process(SessionProtocol.select.also { it[2] = 0 })))
    }

    @Test fun encoderRejectsInvalidSessionInputs() {
        for (remaining in listOf(-1L, 0L, 60001L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { SessionProtocol.encode(id, remaining) }
        }
        assertThrows(IllegalArgumentException::class.java) { SessionProtocol.encode(ByteArray(16), 1) }
        assertThrows(IllegalArgumentException::class.java) { SessionProtocol.encode(ByteArray(15), 1) }
    }

    @Test fun readingNeverExtendsDeadline() {
        val sessions = registry().apply { start() }
        now += 59999
        val engine = ApduEngine(sessions)
        assertEquals(1L, NfcHandshake().exchange(ApduTransport(engine::process)).remainingMillis)
        now++
        assertEquals(0x6985, status(engine.process(SessionProtocol.getSession)))
    }

    @Test fun validatesFramingClassInstructionAndParameters() {
        val engine = ApduEngine(registry().apply { start() })
        assertEquals(0x6700, status(engine.process(null)))
        assertEquals(0x6700, status(engine.process(ByteArray(262))))
        assertEquals(0x6700, status(engine.process(SessionProtocol.select.copyOf(6))))
        assertEquals(0x6A82, status(engine.process(SessionProtocol.select.also { it[5] = 1 })))
        assertEquals(0x6E00, status(engine.process(byteArrayOf(1, 0x10, 0, 0, 0))))
        assertEquals(0x6D00, status(engine.process(byteArrayOf(0x80.toByte(), 0x11, 0, 0, 0))))
        assertEquals(0x6A86, status(engine.process(byteArrayOf(0x80.toByte(), 0x10, 1, 0, 0))))
        assertEquals(0x6700, status(engine.process(SessionProtocol.getSession + byteArrayOf(0))))
    }

    @Test fun wrongSelectClearsPreviousSelection() {
        val engine = ApduEngine(registry().apply { start() })
        engine.process(SessionProtocol.select)
        engine.process(SessionProtocol.select.also { it[5] = 1 })
        assertEquals(0x6986, status(engine.process(SessionProtocol.getSession)))
    }

    @Test fun rejectsMalformedResponsesAndStatusWords() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x90.toByte()), ByteArray(257), byteArrayOf(0x69, 0x85.toByte()))) {
            assertThrows(IllegalArgumentException::class.java) { SessionProtocol.requireSuccess(bytes) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            NfcHandshake().exchange(ApduTransport { SessionProtocol.response(byteArrayOf(1)) })
        }
    }

    @Test fun validatesEverySessionField() {
        val good = SessionProtocol.encode(id, 1)
        val invalid = listOf(good.copyOf(good.size - 1), good + byteArrayOf(0), good.clone().also { it[0] = 2 },
            good.clone().also { java.util.Arrays.fill(it, 1, 17, 0.toByte()) },
            good.clone().also { java.util.Arrays.fill(it, 17, 21, 0.toByte()) },
            good.clone().also { it[17] = 0x7f }, good.clone().also { it[it.lastIndex] = 0 })
        invalid.forEach { body -> assertThrows(IllegalArgumentException::class.java) { SessionProtocol.decode(body) } }
    }

    @Test fun randomMalformedCommandsNeverCrashTheService() {
        val random = java.util.Random(1)
        val engine = ApduEngine(registry().apply { start() })
        repeat(10000) {
            val command = ByteArray(random.nextInt(300)).also { random.nextBytes(it) }
            assertTrue(engine.process(command).size in 2..256)
        }
    }
}
