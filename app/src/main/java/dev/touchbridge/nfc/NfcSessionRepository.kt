package dev.touchbridge.nfc

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Same-process bridge; HCE never creates a session or persists pairing metadata. */
object NfcSessionRepository {
    val sessions = SessionRegistry(SystemClock::elapsedRealtime)
    private val _readCount = MutableStateFlow(0L)
    val readCount = _readCount.asStateFlow()
    fun recordRead() { _readCount.value += 1 }
}
