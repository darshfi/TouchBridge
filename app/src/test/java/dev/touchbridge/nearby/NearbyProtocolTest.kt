package dev.touchbridge.nearby

import org.junit.Assert.*
import org.junit.Test

class NearbyProtocolTest {
    private val id = "0123456789abcdef0123456789abcdef"
    @Test fun messagesRoundTripAndNamesDistinguishRoles() {
        for (kind in NearbyProtocol.Kind.entries) assertEquals(NearbyProtocol.Frame(kind, id), NearbyProtocol.decode(NearbyProtocol.encode(kind, id)))
        assertTrue(NearbyProtocol.matches(NearbyProtocol.endpointName(id, true), id, true))
        assertFalse(NearbyProtocol.matches(NearbyProtocol.endpointName(id, false), id, true))
    }
    @Test fun invalidExternalMessagesAreRejected() {
        val invalid = listOf("", "TB2|MATCH|$id", "TB1|FILE|$id", "TB1|MATCH|$id|extra", "TB1|MATCH|${"0".repeat(32)}", "TB1|MATCH|${id.uppercase()}")
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { NearbyProtocol.decode(it.toByteArray()) } }
        assertThrows(IllegalArgumentException::class.java) { NearbyProtocol.decode(ByteArray(129)) }
        assertThrows(Exception::class.java) { NearbyProtocol.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
    }
    @Test fun requestsOnlyPermissionsSupportedByRuntimeApi() {
        val older = NearbyPermissions.required(28)
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), older)
        assertTrue(NearbyPermissions.required(32).contains("android.permission.ACCESS_FINE_LOCATION"))
        assertFalse(NearbyPermissions.required(32).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertFalse(NearbyPermissions.required(33).any { it.endsWith("LOCATION") })
        assertTrue(NearbyPermissions.required(33).contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertFalse(NearbyPermissions.required(36).contains("android.permission.ACCESS_LOCAL_NETWORK"))
        assertTrue(NearbyPermissions.required(37).contains("android.permission.ACCESS_LOCAL_NETWORK"))
    }
}
