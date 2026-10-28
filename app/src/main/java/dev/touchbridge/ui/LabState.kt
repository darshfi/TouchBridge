package dev.touchbridge.ui

import dev.touchbridge.nfc.EmulationSessionStatus

enum class Role { IDLE, EMULATE, SCAN }
data class LabState(
    val role: Role = Role.IDLE,
    val title: String = "Ready to connect",
    val detail: String = "Choose a role on each phone to test a real NFC exchange.",
    val secondsLeft: Long = 0,
    val busy: Boolean = false,
    val success: Boolean = false,
)

/** Completed presentation stays visible after TTL expiry; the protocol still enforces TTL. */
fun LabState.withEmulationStatus(status: EmulationSessionStatus, expectedGeneration: Long?, remainingMillis: Long): LabState {
    if (role != Role.EMULATE || !status.active || status.generation != expectedGeneration) return this
    return when {
        status.served || success -> copy(
            title = "NFC session sent",
            detail = "Hello from TouchBridge! was returned to the reader. Check the other phone for receipt confirmation. Peer identity is not verified.",
            secondsLeft = 0,
            success = true,
        )
        remainingMillis <= 0 -> LabState(title = "Session expired", detail = "Start Emulate again for a fresh 60-second session.")
        else -> copy(secondsLeft = (remainingMillis + 999) / 1000)
    }
}
