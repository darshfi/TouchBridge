package dev.touchbridge.ui

import dev.touchbridge.nfc.ApduEngine
import dev.touchbridge.nfc.ApduTransport
import dev.touchbridge.nfc.EmulationSessionStatus
import dev.touchbridge.nfc.NfcHandshake
import dev.touchbridge.nfc.SessionProtocol
import dev.touchbridge.nfc.SessionRegistry
import org.junit.Assert.*
import org.junit.Test

class EmulationStatusTest {
    private var now = 1000L
    private val sessions = SessionRegistry({ now })
    private fun waiting() = LabState(role = Role.EMULATE, title = "Hold phones together", secondsLeft = 60)

    @Test fun servedResponseIsRetainedForLateUiObservers() {
        sessions.start()
        val engine = ApduEngine(sessions)
        NfcHandshake().exchange(ApduTransport(engine::process))
        // Subscribe/read only after the APDUs have completed: no transient event is required.
        val updated = waiting().withEmulationStatus(sessions.status.value, sessions.generation, sessions.remainingMillis())
        assertTrue(updated.success)
        assertEquals("NFC session sent", updated.title)
        assertEquals(0L, updated.secondsLeft)
    }

    @Test fun deadlineStillRejectsTokensWithoutErasingSentResult() {
        sessions.start()
        val engine = ApduEngine(sessions)
        NfcHandshake().exchange(ApduTransport(engine::process))
        val sent = waiting().withEmulationStatus(sessions.status.value, sessions.generation, sessions.remainingMillis())
        now += 61000
        assertNull(sessions.payload())
        val updated = sent.withEmulationStatus(sessions.status.value, sessions.generation, sessions.remainingMillis())
        assertEquals(sent, updated)
        assertTrue(updated.success)
        assertThrows(IllegalArgumentException::class.java) {
            SessionProtocol.requireSuccess(engine.process(SessionProtocol.getSession))
        }
    }

    @Test fun selectingWithoutReadingDoesNotShowSuccessAndStillExpires() {
        sessions.start()
        ApduEngine(sessions).process(SessionProtocol.select)
        now += 1000
        val pending = waiting().withEmulationStatus(sessions.status.value, sessions.generation, sessions.remainingMillis())
        assertFalse(pending.success)
        assertEquals(59L, pending.secondsLeft)
        now += 59000
        val expired = pending.withEmulationStatus(sessions.status.value, sessions.generation, sessions.remainingMillis())
        assertEquals(Role.IDLE, expired.role)
        assertEquals("Session expired", expired.title)
    }

    @Test fun oldSessionCannotCompleteNewRoleSelection() {
        sessions.start()
        NfcHandshake().exchange(ApduTransport(ApduEngine(sessions)::process))
        val oldStatus = sessions.status.value
        sessions.stop()
        sessions.start()
        assertEquals(waiting(), waiting().withEmulationStatus(oldStatus, sessions.generation, 60000))
        assertFalse(sessions.status.value.served)
        assertFalse(sessions.markServed(oldStatus.generation))
        assertFalse(sessions.status.value.served)
    }

    @Test fun cancelledOrReaderUiCannotBeResurrectedByLateResponse() {
        val served = EmulationSessionStatus(1, active = true, served = true)
        val idle = LabState()
        val reader = LabState(role = Role.SCAN)
        assertEquals(idle, idle.withEmulationStatus(served, 1, 60000))
        assertEquals(reader, reader.withEmulationStatus(served, 1, 60000))
        sessions.start()
        val generation = sessions.generation
        sessions.stop()
        assertFalse(sessions.markServed(generation))
        assertFalse(sessions.status.value.served)
    }

    @Test fun invalidApduCannotCompleteSession() {
        sessions.start()
        val engine = ApduEngine(sessions)
        engine.process(SessionProtocol.getSession) // missing SELECT
        assertFalse(sessions.status.value.served)
        engine.process(SessionProtocol.select)
        engine.process(SessionProtocol.getSession.also { it[3] = 1 })
        assertFalse(sessions.status.value.served)
    }

    @Test fun retryDoesNotResetCompletionOrExtendSessionDeadline() {
        sessions.start()
        val engine = ApduEngine(sessions)
        NfcHandshake().exchange(ApduTransport(engine::process))
        val status = sessions.status.value
        now += 2000
        NfcHandshake().exchange(ApduTransport(engine::process))
        assertEquals(status, sessions.status.value)
        assertEquals(58000L, sessions.remainingMillis())
    }
}
