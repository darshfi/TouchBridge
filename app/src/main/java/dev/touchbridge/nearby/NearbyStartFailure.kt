package dev.touchbridge.nearby

/** Only SDK status metadata is retained. Never include an exception's message or stack trace. */
data class NearbyStartFailure(val advertising: Boolean, val code: Int? = null, val status: String = "SDK_START_FAILED") {
    fun description(): String {
        val operation = if (advertising) "Advertising" else "Discovery"
        val safeStatus = status.filter { it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == ' ' }.take(80)
        val diagnostic = "$operation failed (${code?.let { "Nearby $it · " } ?: ""}$safeStatus)."
        val help = when (code) {
            8029, 8037, 8038, 8039 -> "Nearby reports a missing device permission. Check TouchBridge's Nearby devices permission."
            8034, 8036 -> "Nearby reports a missing Location permission. Record this code and Android version even if Settings says permissions are granted."
            8030, 8031, 8032, 8033 -> "Nearby reports a missing manifest permission that cannot be granted in Settings. Please report this code."
            8001, 8002, 8008, 8009, 8050 -> "Nearby reports an existing or conflicting operation. Stop both sessions, reopen TouchBridge and retry."
            8007 -> "Nearby could not use Bluetooth/Wi-Fi. Check both are on, then retry."
            else -> "Please share this error and the Android API shown in diagnostics. NFC Lab remains available."
        }
        return "$diagnostic\n$help"
    }
}
