package dev.touchbridge.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

class TouchBridgeHceService : HostApduService() {
    private val engine = ApduEngine(NfcSessionRepository.sessions) {
        NfcSessionRepository.recordRead()
        Log.i("TouchBridgeHce", "Session APDU served")
    }

    // Bounded in-memory work only: no disk, networking, coroutine waits, or random generation.
    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray = engine.process(commandApdu)
    override fun onDeactivated(reason: Int) { engine.reset() }
}
