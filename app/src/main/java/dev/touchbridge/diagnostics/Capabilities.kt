package dev.touchbridge.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcManager
import android.os.Build

data class Capabilities(val nfc: Boolean, val hce: Boolean, val enabled: Boolean, val api: Int) {
    companion object {
        fun detect(context: Context): Capabilities {
            val pm = context.packageManager
            val adapter = context.getSystemService(NfcManager::class.java)?.defaultAdapter
            return Capabilities(pm.hasSystemFeature(PackageManager.FEATURE_NFC),
                pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION),
                adapter?.isEnabled == true, Build.VERSION.SDK_INT)
        }
    }
}
