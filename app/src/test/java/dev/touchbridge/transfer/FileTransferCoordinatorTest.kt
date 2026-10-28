package dev.touchbridge.transfer

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferCoordinatorTest {
    private val session = "0123456789abcdef0123456789abcdef"
    private val offer = FileOffer(123, "photo.jpg", 100, "image/jpeg", "a".repeat(64))
    private inner class Store : FileTransferStorage {
        var saved = false
        var failSave = false
        var holdSave = false
        var holdPrepare = false
        val released = mutableListOf<Long>()
        val deleted = mutableListOf<String>()
        override suspend fun prepare(source: String): FileOffer { if (holdPrepare) awaitCancellation(); return offer }
        override suspend fun save(id: Long, destination: String, offer: FileOffer) {
            if (holdSave) awaitCancellation()
            check(!failSave); saved = true
        }
        override fun release(id: Long) { released.add(id) }
        override suspend fun deleteDestination(destination: String) { deleted.add(destination) }
    }
    private inner class Link : FileTransferLink {
        val frames = mutableListOf<FileProtocol.Frame>()
        val sent = mutableListOf<Long>()
        val canceled = mutableListOf<Long>()
        val failures = mutableListOf<() -> Unit>()
        override fun control(frame: FileProtocol.Frame, failed: () -> Unit) { frames.add(frame); failures.add(failed) }
        override fun sendFile(id: Long, failed: () -> Unit) { sent.add(id); failures.add(failed) }
        override fun cancelFile(id: Long) { canceled.add(id) }
    }
    private inner class Fixture(scope: TestScope) {
        val store = Store()
        val link = Link()
        val coordinator = FileTransferCoordinator(scope.backgroundScope, { scope.testScheduler.currentTime }, store, link)
        val state get() = coordinator.state.value
        init { coordinator.connected(session) }
        fun frame(kind: FileProtocol.Kind, id: Long = offer.id) = FileProtocol.Frame(kind, session, id, if (kind == FileProtocol.Kind.OFFER) offer else null)
        fun incoming() { coordinator.onControl(frame(FileProtocol.Kind.OFFER)) }
        fun accept() { incoming(); coordinator.accept("content://saved") }
    }
    @Test fun noFileSentBeforeExplicitAcceptanceAndNoSuccessBeforeReceipt() = runTest {
        val f = Fixture(this)
        f.coordinator.offer("content://source"); runCurrent()
        assertEquals(FilePhase.OFFERED, f.state.phase); assertTrue(f.link.sent.isEmpty())
        f.coordinator.onControl(f.frame(FileProtocol.Kind.ACCEPT))
        assertEquals(listOf(123L), f.link.sent)
        f.coordinator.onUpdate(123, 100, 100, FileUpdate.SUCCESS)
        assertEquals(FilePhase.WAITING_RECEIPT, f.state.phase)
        f.coordinator.onControl(f.frame(FileProtocol.Kind.SAVED)); runCurrent()
        assertEquals(FilePhase.SUCCESS, f.state.phase)
        assertTrue(f.store.released.contains(123))
    }
    @Test fun receiveRequiresConsentAndSaveBeforeAcknowledgement() = runTest {
        val f = Fixture(this); f.incoming()
        assertTrue(f.link.frames.isEmpty()); assertFalse(f.store.saved)
        f.coordinator.accept("content://saved")
        assertEquals(FileProtocol.Kind.ACCEPT, f.link.frames.last().kind)
        f.coordinator.onFile(123)
        f.coordinator.onUpdate(123, 50, 100, FileUpdate.PROGRESS)
        assertEquals(50L, f.state.bytes)
        assertFalse(f.store.saved)
        f.coordinator.onUpdate(123, 100, 100, FileUpdate.SUCCESS); runCurrent()
        assertTrue(f.store.saved); assertEquals(FilePhase.SUCCESS, f.state.phase)
        assertEquals(FileProtocol.Kind.SAVED, f.link.frames.last().kind)
        assertTrue(f.store.deleted.isEmpty())
    }
    @Test fun sdkSuccessBeforeFileCallbackStillSavesOnlyAfterBothArrive() = runTest {
        val f = Fixture(this); f.accept()
        f.coordinator.onUpdate(123, 100, 100, FileUpdate.SUCCESS); runCurrent()
        assertFalse(f.store.saved)
        f.coordinator.onFile(123); runCurrent()
        assertEquals(FilePhase.SUCCESS, f.state.phase)
    }
    @Test fun receiverReceiptBeforeSenderCompletionIsBuffered() = runTest {
        val f = Fixture(this); f.coordinator.offer("source"); runCurrent()
        f.coordinator.onControl(f.frame(FileProtocol.Kind.ACCEPT))
        f.coordinator.onControl(f.frame(FileProtocol.Kind.SAVED))
        assertEquals(FilePhase.TRANSFERRING, f.state.phase)
        f.coordinator.onUpdate(123, 100, 100, FileUpdate.SUCCESS); runCurrent()
        assertEquals(FilePhase.SUCCESS, f.state.phase)
    }
    @Test fun unapprovedAndWrongFilesAreCanceledAndDuplicateDoesNotDestroyAcceptedFile() = runTest {
        val f = Fixture(this); f.incoming(); f.coordinator.onFile(123)
        assertTrue(f.link.canceled.contains(123)); assertFalse(f.store.saved)
        f.coordinator.accept("target"); f.coordinator.onFile(456)
        assertTrue(f.link.canceled.contains(456))
        f.coordinator.onFile(123); f.coordinator.onFile(123)
        assertEquals(1, f.link.canceled.count { it == 123L })
    }
    @Test fun rejectsConcurrentOffersAndIgnoresWrongSessionAndId() = runTest {
        val f = Fixture(this); f.accept()
        f.coordinator.onControl(f.frame(FileProtocol.Kind.OFFER))
        assertEquals(FileProtocol.Kind.REJECT, f.link.frames.last().kind)
        f.coordinator.onControl(f.frame(FileProtocol.Kind.CANCEL, 999))
        f.coordinator.onControl(f.frame(FileProtocol.Kind.CANCEL).copy(session = "f".repeat(32)))
        assertEquals(FilePhase.TRANSFERRING, f.state.phase)
    }
    @Test fun rejectionNeverSendsFile() = runTest {
        val f = Fixture(this); f.coordinator.offer("source"); runCurrent()
        f.coordinator.onControl(f.frame(FileProtocol.Kind.REJECT)); runCurrent()
        assertEquals(FilePhase.CANCELED, f.state.phase); assertTrue(f.link.sent.isEmpty())
    }
    @Test fun savingFailureDeletesPartialDestinationAndNeverAcknowledgesSuccess() = runTest {
        val f = Fixture(this); f.store.failSave = true; f.accept()
        f.coordinator.onFile(123); f.coordinator.onUpdate(123, 100, 100, FileUpdate.SUCCESS); runCurrent()
        assertEquals(FilePhase.ERROR, f.state.phase)
        assertEquals(listOf("content://saved"), f.store.deleted)
        assertFalse(f.link.frames.any { it.kind == FileProtocol.Kind.SAVED })
    }
    @Test fun cancelDuringSaveAndResetCleanUpAndIgnoreLateCompletion() = runTest {
        val f = Fixture(this); f.store.holdSave = true; f.accept()
        f.coordinator.onFile(123); f.coordinator.onUpdate(123, 100, 100, FileUpdate.SUCCESS); runCurrent()
        assertEquals(FilePhase.SAVING, f.state.phase)
        f.coordinator.cancel(); runCurrent()
        assertEquals(FilePhase.CANCELED, f.state.phase)
        assertEquals(listOf("content://saved"), f.store.deleted)
        f.coordinator.reset()
        f.coordinator.onUpdate(123, 100, 100, FileUpdate.SUCCESS); runCurrent()
        assertEquals(FilePhase.IDLE, f.state.phase)
    }
    @Test fun staleDestinationAfterCanceledOfferIsDeleted() = runTest {
        val f = Fixture(this); f.incoming(); f.coordinator.cancel()
        f.coordinator.accept("content://late"); runCurrent()
        assertEquals(listOf("content://late"), f.store.deleted)
    }
    @Test fun consentAndTransferStallsHaveTimeoutsButProgressExtendsTransfer() = runTest {
        val f = Fixture(this); f.incoming()
        advanceTimeBy(120001); runCurrent(); assertEquals(FilePhase.ERROR, f.state.phase)
        f.accept(); advanceTimeBy(59000); runCurrent()
        f.coordinator.onUpdate(123, 50, 100, FileUpdate.PROGRESS)
        advanceTimeBy(59000); runCurrent(); assertEquals(FilePhase.TRANSFERRING, f.state.phase)
        advanceTimeBy(1001); runCurrent(); assertEquals(FilePhase.ERROR, f.state.phase)
    }
    @Test fun sizeMismatchOrIncompleteSuccessFails() = runTest {
        for ((bytes, total) in listOf(101L to 100L, 100L to 101L, 99L to 100L)) {
            val f = Fixture(this); f.accept()
            f.coordinator.onUpdate(123, bytes, total, FileUpdate.SUCCESS); runCurrent()
            assertEquals(FilePhase.ERROR, f.state.phase)
        }
    }
    @Test fun oldSendFailureCannotBreakNewTransfer() = runTest {
        val f = Fixture(this); f.coordinator.offer("source"); runCurrent()
        val failure = f.link.failures.last()
        f.coordinator.cancel(); runCurrent(); f.coordinator.offer("source"); runCurrent()
        failure(); runCurrent(); assertEquals(FilePhase.OFFERED, f.state.phase)
    }
    @Test fun cancelDuringPreparationNeverOffersFile() = runTest {
        val f = Fixture(this); f.store.holdPrepare = true
        f.coordinator.offer("source"); runCurrent(); f.coordinator.cancel(); runCurrent()
        assertEquals(FilePhase.CANCELED, f.state.phase); assertTrue(f.link.frames.isEmpty())
    }
    @Test fun zeroByteFileStillRequiresVerificationAndReceipt() = runTest {
        val f = Fixture(this)
        f.coordinator.onControl(f.frame(FileProtocol.Kind.OFFER).copy(offer = offer.copy(size = 0)))
        f.coordinator.accept("empty"); f.coordinator.onFile(123)
        f.coordinator.onUpdate(123, 0, 0, FileUpdate.SUCCESS); runCurrent()
        assertTrue(f.store.saved); assertEquals(FilePhase.SUCCESS, f.state.phase)
    }
    @Test fun twoPeersRequireConsentThenSaveAndCanReverseDirection() = runTest {
        val leftStore = Store()
        val rightStore = Store()
        lateinit var left: FileTransferCoordinator
        lateinit var right: FileTransferCoordinator
        fun link(peer: () -> FileTransferCoordinator) = object : FileTransferLink {
            override fun control(frame: FileProtocol.Frame, failed: () -> Unit) { backgroundScope.launch { peer().onControl(frame) } }
            override fun sendFile(id: Long, failed: () -> Unit) {
                backgroundScope.launch {
                    peer().onFile(id)
                    // Simulate SDK terminal callbacks with the receiver finishing first.
                    peer().onUpdate(id, 100, 100, FileUpdate.SUCCESS)
                    if (peer() === right) left.onUpdate(id, 100, 100, FileUpdate.SUCCESS)
                    else right.onUpdate(id, 100, 100, FileUpdate.SUCCESS)
                }
            }
            override fun cancelFile(id: Long) = Unit
        }
        left = FileTransferCoordinator(backgroundScope, { testScheduler.currentTime }, leftStore, link { right })
        right = FileTransferCoordinator(backgroundScope, { testScheduler.currentTime }, rightStore, link { left })
        left.connected(session); right.connected(session)
        left.offer("left-file"); runCurrent()
        assertEquals(FilePhase.INCOMING, right.state.value.phase)
        assertFalse(rightStore.saved)
        right.accept("right-destination"); runCurrent()
        assertTrue(rightStore.saved)
        assertEquals(FilePhase.SUCCESS, left.state.value.phase)
        assertEquals(FilePhase.SUCCESS, right.state.value.phase)
        right.offer("right-file"); runCurrent()
        left.accept("left-destination"); runCurrent()
        assertTrue(leftStore.saved)
        assertEquals(FilePhase.SUCCESS, left.state.value.phase)
        assertEquals(FilePhase.SUCCESS, right.state.value.phase)
    }
}
