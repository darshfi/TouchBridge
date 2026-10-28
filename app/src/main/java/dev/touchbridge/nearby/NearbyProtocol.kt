package dev.touchbridge.nearby

import java.nio.charset.CodingErrorAction
import java.nio.ByteBuffer

/** Public NFC IDs match endpoints; Nearby's user-compared digits authenticate the link. */
object NearbyProtocol {
    const val SERVICE_ID = "dev.touchbridge.nearby.v1"
    const val HELLO = "Hello from the other phone"
    private val idPattern = Regex("[0-9a-f]{32}")
    fun validSession(id: String) = idPattern.matches(id) && id.any { it != '0' }
    fun endpointName(session: String, advertiser: Boolean): String {
        require(validSession(session))
        return "TB1:${if (advertiser) "A" else "R"}:$session"
    }
    fun matches(name: String, session: String, advertiser: Boolean) =
        validSession(session) && name == endpointName(session, advertiser)

    enum class Kind { MATCH, HELLO }
    data class Frame(val kind: Kind, val session: String)
    fun encode(kind: Kind, session: String): ByteArray {
        require(validSession(session))
        return "TB1|${kind.name}|$session".toByteArray(Charsets.UTF_8)
    }
    fun decode(bytes: ByteArray): Frame {
        require(bytes.size in 1..128) { "Invalid message size" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val fields = text.split('|')
        require(fields.size == 3 && fields[0] == "TB1" && validSession(fields[2])) { "Invalid message" }
        return Frame(Kind.valueOf(fields[1]), fields[2])
    }
}
