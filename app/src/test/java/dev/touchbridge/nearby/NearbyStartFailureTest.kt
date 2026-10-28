package dev.touchbridge.nearby

import org.junit.Assert.*
import org.junit.Test

class NearbyStartFailureTest {
    @Test fun distinguishesStartupOperationsAndPreservesUnknownCodes() {
        assertTrue(NearbyStartFailure(true, 12345, "UNKNOWN_STATUS").description().contains("Advertising failed (Nearby 12345"))
        assertTrue(NearbyStartFailure(false).description().startsWith("Discovery failed"))
    }
    @Test fun permissionErrorDoesNotPromiseSettingsCanGrantManifestPermission() {
        assertTrue(NearbyStartFailure(true, 8032).description().contains("cannot be granted in Settings"))
        assertTrue(NearbyStartFailure(true, 8038).description().contains("Nearby devices permission"))
    }
}
