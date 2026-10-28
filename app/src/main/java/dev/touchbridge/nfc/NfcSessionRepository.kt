package dev.touchbridge.nfc

import android.os.SystemClock

/** Same-process bridge; HCE never creates a session or persists pairing metadata. */
object NfcSessionRepository {
    val sessions = SessionRegistry(SystemClock::elapsedRealtime)
}
