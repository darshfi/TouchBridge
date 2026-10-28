package dev.touchbridge.nearby

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyCoordinatorTest {
    private val session = "0123456789abcdef0123456789abcdef"
    private val other = "abcdef0123456789abcdef0123456789"

    private class FakeTransport : NearbyTransport {
        lateinit var listener: NearbyTransport.Listener
        val requests = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val frames = mutableListOf<NearbyProtocol.Frame>()
        val deliveryFailures = mutableListOf<() -> Unit>()
        var accepts = 0
        var closed = false
        var holdDelivery = false
        override fun advertise(name: String, listener: NearbyTransport.Listener, ready: () -> Unit, failed: () -> Unit) {
            this.listener = listener; ready()
        }
        override fun discover(listener: NearbyTransport.Listener, ready: () -> Unit, failed: () -> Unit) {
            this.listener = listener; ready()
        }
        override fun request(name: String, endpoint: String, failed: () -> Unit) { requests.add(endpoint) }
        override fun accept(endpoint: String, failed: () -> Unit) { accepts++ }
        override fun reject(endpoint: String) { rejected.add(endpoint) }
        override fun send(endpoint: String, bytes: ByteArray, sent: () -> Unit, failed: () -> Unit) {
            frames.add(NearbyProtocol.decode(bytes)); deliveryFailures.add(failed)
            if (!holdDelivery) sent()
        }
        override fun stopSearching() = Unit
        override fun close() { closed = true }
    }
    private inner class Fixture(scope: TestScope) {
        val ports = mutableListOf<FakeTransport>()
        var tapped = true
        val coordinator = NearbyCoordinator(scope.backgroundScope, { scope.testScheduler.currentTime },
            { FakeTransport().also { ports.add(it) } }, { tapped })
        val port get() = ports.last()
    }
    private fun TestScope.verify(f: Fixture) {
        f.coordinator.start(session, 60000, advertise = false)
        runCurrent()
        f.port.listener.found("peer", NearbyProtocol.endpointName(session, true))
        runCurrent()
        f.port.listener.initiated("peer", NearbyProtocol.endpointName(session, true), "1234", false)
        runCurrent()
    }
    private fun TestScope.connect(f: Fixture) {
        verify(f)
        f.coordinator.approve()
        f.port.listener.result("peer", true)
        runCurrent()
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.MATCH, session))
        runCurrent()
        assertEquals(NearbyPhase.CONNECTED, f.coordinator.state.value.phase)
    }

    @Test fun ignoresUnrelatedAndSpoofedPrefixEndpoints() = runTest {
        val f = Fixture(this)
        f.coordinator.start(session, 60000, false); runCurrent()
        f.port.listener.found("wrong", NearbyProtocol.endpointName(other, true))
        f.port.listener.found("prefix", NearbyProtocol.endpointName(session, true) + "extra")
        runCurrent()
        assertTrue(f.port.requests.isEmpty())
        f.port.listener.found("right", NearbyProtocol.endpointName(session, true)); runCurrent()
        assertEquals(listOf("right"), f.port.requests)
    }

    @Test fun advertiserRejectsRequestsBeforePhysicalNfcRead() = runTest {
        val f = Fixture(this); f.tapped = false
        f.coordinator.start(session, 60000, true); runCurrent()
        f.port.listener.initiated("peer", NearbyProtocol.endpointName(session, false), "1234", true); runCurrent()
        assertEquals(listOf("peer"), f.port.rejected)
        assertEquals(NearbyPhase.ADVERTISING, f.coordinator.state.value.phase)
        assertEquals(0, f.port.accepts)
    }

    @Test fun advertiserAcceptsOnlyMatchedReaderWithValidCode() = runTest {
        val f = Fixture(this)
        f.coordinator.start(session, 60000, true); runCurrent()
        f.port.listener.initiated("wrong", NearbyProtocol.endpointName(other, false), "1234", true)
        f.port.listener.initiated("empty", NearbyProtocol.endpointName(session, false), "", true)
        f.port.listener.initiated("direction", NearbyProtocol.endpointName(session, false), "1234", false)
        runCurrent()
        assertEquals(3, f.port.rejected.size)
        f.port.listener.initiated("peer", NearbyProtocol.endpointName(session, false), "1234", true); runCurrent()
        assertEquals(NearbyPhase.VERIFYING, f.coordinator.state.value.phase)
        assertEquals("1234", f.coordinator.state.value.digits)
        assertEquals(0, f.port.accepts)
    }

    @Test fun noAutoAcceptOrUserMessageBeforeBothApprovalsAndSessionMatch() = runTest {
        val f = Fixture(this); verify(f)
        assertEquals(0, f.port.accepts)
        f.coordinator.sendHello(); runCurrent()
        assertTrue(f.port.frames.isEmpty())
        f.coordinator.approve(); runCurrent()
        assertEquals(1, f.port.accepts)
        assertEquals(NearbyPhase.VERIFYING, f.coordinator.state.value.phase)
        f.port.listener.result("peer", true); runCurrent()
        assertEquals(NearbyPhase.MATCHING, f.coordinator.state.value.phase)
        f.coordinator.sendHello(); runCurrent()
        assertEquals(listOf(NearbyProtocol.Kind.MATCH), f.port.frames.map { it.kind })
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.MATCH, session)); runCurrent()
        f.coordinator.sendHello(); runCurrent()
        assertEquals(listOf(NearbyProtocol.Kind.MATCH, NearbyProtocol.Kind.HELLO), f.port.frames.map { it.kind })
        assertTrue(f.coordinator.state.value.sent)
    }

    @Test fun connectionSuccessWithoutLocalConsentIsClosed() = runTest {
        val f = Fixture(this); verify(f)
        f.port.listener.result("peer", true); runCurrent()
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
        assertTrue(f.port.closed)
    }

    @Test fun expiredSessionCannotBeApproved() = runTest {
        val f = Fixture(this); verify(f)
        advanceTimeBy(60001); runCurrent()
        f.coordinator.approve()
        assertEquals(0, f.port.accepts)
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
        assertTrue(f.coordinator.state.value.digits.isEmpty())
    }

    @Test fun pendingConnectionHasBoundedTimeout() = runTest {
        val f = Fixture(this); verify(f)
        advanceTimeBy(30001); runCurrent()
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
        assertTrue(f.port.closed)
    }

    @Test fun rejectsInvalidSessionInputsBeforeStartingRadio() = runTest {
        val f = Fixture(this)
        for (ttl in listOf(0L, -1L, 60001L)) f.coordinator.start(session, ttl, true)
        f.coordinator.start("not-a-session", 60000, false)
        assertTrue(f.ports.isEmpty())
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
    }

    @Test fun mismatchOverEncryptedChannelClosesConnection() = runTest {
        val f = Fixture(this); verify(f)
        f.coordinator.approve(); f.port.listener.result("peer", true); runCurrent()
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.MATCH, other)); runCurrent()
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
        assertTrue(f.port.closed)
    }

    @Test fun outOfOrderHelloWaitsForSessionValidation() = runTest {
        val f = Fixture(this); verify(f)
        f.coordinator.approve(); f.port.listener.result("peer", true); runCurrent()
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.HELLO, session)); runCurrent()
        assertEquals("", f.coordinator.state.value.received)
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.MATCH, session)); runCurrent()
        assertEquals(NearbyProtocol.HELLO, f.coordinator.state.value.received)
    }

    @Test fun payloadCallbackBeforeConnectionResultDoesNotLoseMatch() = runTest {
        val f = Fixture(this); verify(f)
        f.coordinator.approve()
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.MATCH, session))
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.HELLO, session)); runCurrent()
        assertEquals(NearbyPhase.VERIFYING, f.coordinator.state.value.phase)
        f.port.listener.result("peer", true); runCurrent()
        assertEquals(NearbyPhase.CONNECTED, f.coordinator.state.value.phase)
        assertEquals(NearbyProtocol.HELLO, f.coordinator.state.value.received)
    }

    @Test fun oldCallbacksCannotResurrectCancelledOrNewSession() = runTest {
        val f = Fixture(this); verify(f)
        val old = f.port
        f.coordinator.stop()
        old.listener.result("peer", true); runCurrent()
        assertEquals(NearbyPhase.IDLE, f.coordinator.state.value.phase)
        f.coordinator.start(other, 60000, false); runCurrent()
        old.listener.found("old", NearbyProtocol.endpointName(other, true))
        old.listener.initiated("old", NearbyProtocol.endpointName(other, true), "1234", false)
        runCurrent()
        assertTrue(f.port.requests.isEmpty())
        assertTrue(old.closed)
    }

    @Test fun rejectionClearsCodeAndClosesWithoutHello() = runTest {
        val f = Fixture(this); verify(f)
        f.coordinator.reject(); runCurrent()
        assertEquals(listOf("peer"), f.port.rejected)
        assertTrue(f.port.closed)
        assertTrue(f.coordinator.state.value.digits.isEmpty())
        assertTrue(f.port.frames.isEmpty())
    }

    @Test fun remoteRejectionAndDisconnectHaveVisibleErrors() = runTest {
        val f = Fixture(this); verify(f)
        f.coordinator.approve(); f.port.listener.result("peer", false); runCurrent()
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
        connect(f)
        f.port.listener.disconnected("peer"); runCurrent()
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
        assertTrue(f.port.closed)
    }

    @Test fun unrelatedPayloadsCannotAffectConnectedPeer() = runTest {
        val f = Fixture(this); connect(f)
        f.port.listener.bytes("stranger", byteArrayOf()); runCurrent()
        assertEquals(NearbyPhase.CONNECTED, f.coordinator.state.value.phase)
        assertTrue(f.coordinator.state.value.received.isEmpty())
    }

    @Test fun malformedPayloadClosesTheApprovedConnection() = runTest {
        val f = Fixture(this); connect(f)
        f.port.listener.bytes("peer", ByteArray(129)); runCurrent()
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
    }

    @Test fun successfulPairingSurvivesNfcTtlForForegroundMessages() = runTest {
        val f = Fixture(this); connect(f)
        advanceTimeBy(65000); runCurrent()
        assertEquals(NearbyPhase.CONNECTED, f.coordinator.state.value.phase)
        f.coordinator.sendHello(); runCurrent()
        assertTrue(f.coordinator.state.value.sent)
    }

    @Test fun stalledDeliveryTimesOutAndOldFailuresCannotBreakRetry() = runTest {
        val f = Fixture(this); connect(f)
        f.port.holdDelivery = true
        f.coordinator.sendHello(); runCurrent()
        val oldFailure = f.port.deliveryFailures.last()
        advanceTimeBy(10001); runCurrent()
        assertEquals(NearbyPhase.ERROR, f.coordinator.state.value.phase)
        f.coordinator.start(other, 60000, true); runCurrent()
        oldFailure(); runCurrent()
        assertEquals(NearbyPhase.ADVERTISING, f.coordinator.state.value.phase)
    }

    @Test fun duplicateMatchDoesNotEraseReceivedHello() = runTest {
        val f = Fixture(this); connect(f)
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.HELLO, session)); runCurrent()
        f.port.listener.bytes("peer", NearbyProtocol.encode(NearbyProtocol.Kind.MATCH, session)); runCurrent()
        f.port.listener.result("peer", true); runCurrent()
        assertEquals(NearbyPhase.CONNECTED, f.coordinator.state.value.phase)
        assertEquals(NearbyProtocol.HELLO, f.coordinator.state.value.received)
    }

    @Test fun twoPhonesRequireBothApprovalsThenExchangeHelloInBothDirections() = runTest {
        val a = FakeTransport()
        val r = FakeTransport()
        var aApproved = false
        var rApproved = false
        fun reportBothAccepted() {
            if (aApproved && rApproved) {
                a.listener.result("reader", true)
                r.listener.result("advertiser", true)
            }
        }
        val aPort = object : NearbyTransport by a {
            override fun accept(endpoint: String, failed: () -> Unit) { a.accept(endpoint, failed); aApproved = true; reportBothAccepted() }
            override fun send(endpoint: String, bytes: ByteArray, sent: () -> Unit, failed: () -> Unit) {
                a.send(endpoint, bytes, sent, failed)
                r.listener.bytes("advertiser", bytes)
            }
        }
        val rPort = object : NearbyTransport by r {
            override fun request(name: String, endpoint: String, failed: () -> Unit) {
                r.request(name, endpoint, failed)
                a.listener.initiated("reader", name, "5678", true)
                r.listener.initiated("advertiser", NearbyProtocol.endpointName(session, true), "5678", false)
            }
            override fun accept(endpoint: String, failed: () -> Unit) { r.accept(endpoint, failed); rApproved = true; reportBothAccepted() }
            override fun send(endpoint: String, bytes: ByteArray, sent: () -> Unit, failed: () -> Unit) {
                r.send(endpoint, bytes, sent, failed)
                a.listener.bytes("reader", bytes)
            }
        }
        val advertiser = NearbyCoordinator(backgroundScope, { testScheduler.currentTime }, { aPort }, { true })
        val reader = NearbyCoordinator(backgroundScope, { testScheduler.currentTime }, { rPort }, { true })
        advertiser.start(session, 60000, true)
        reader.start(session, 59000, false)
        runCurrent()
        r.listener.found("advertiser", NearbyProtocol.endpointName(session, true)); runCurrent()
        assertEquals(advertiser.state.value.digits, reader.state.value.digits)
        advertiser.approve(); runCurrent()
        assertTrue(a.frames.isEmpty() && r.frames.isEmpty())
        assertEquals(NearbyPhase.VERIFYING, advertiser.state.value.phase)
        reader.approve(); runCurrent()
        assertEquals(NearbyPhase.CONNECTED, advertiser.state.value.phase)
        assertEquals(NearbyPhase.CONNECTED, reader.state.value.phase)
        assertTrue(advertiser.state.value.received.isEmpty() && reader.state.value.received.isEmpty())
        advertiser.sendHello(); reader.sendHello(); runCurrent()
        assertEquals(NearbyProtocol.HELLO, advertiser.state.value.received)
        assertEquals(NearbyProtocol.HELLO, reader.state.value.received)
        assertTrue(advertiser.state.value.sent && reader.state.value.sent)
    }
}
