package dev.touchbridge.transfer

import dev.touchbridge.nearby.NearbyProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class FileOffer(val id: Long, val name: String, val size: Long, val mime: String, val sha256: String)

/** Bounded, versioned control frames. File bytes always use Nearby FILE payloads. */
object FileProtocol {
    enum class Kind { OFFER, ACCEPT, REJECT, CANCEL, SAVED }
    data class Frame(val kind: Kind, val session: String, val id: Long, val offer: FileOffer? = null)
    private val prefix = byteArrayOf(84, 70, 49) // TF1
    fun recognizes(bytes: ByteArray) = bytes.size >= 3 && bytes.take(3).toByteArray().contentEquals(prefix)
    fun safeName(name: String): String = name.replace('\\', '/').substringAfterLast('/')
        .filter { !it.isISOControl() && it !in "<>:\"|?*" }.trim().take(120)
        .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "received-file"
    fun validate(offer: FileOffer) {
        require(offer.name == safeName(offer.name))
        require(offer.size >= 0)
        require(Regex("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+").matches(offer.mime) && offer.mime.length <= 100)
        require(Regex("[0-9a-f]{64}").matches(offer.sha256))
    }
    fun encode(frame: Frame): ByteArray {
        require(NearbyProtocol.validSession(frame.session))
        require((frame.kind == Kind.OFFER) == (frame.offer != null))
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.write(prefix); out.writeByte(frame.kind.ordinal); out.writeUTF(frame.session); out.writeLong(frame.id)
                frame.offer?.let { offer ->
                    validate(offer); require(offer.id == frame.id)
                    out.writeUTF(offer.name); out.writeLong(offer.size); out.writeUTF(offer.mime); out.writeUTF(offer.sha256)
                }
            }
        }.toByteArray().also { require(it.size <= 1024) }
    }
    fun decode(bytes: ByteArray): Frame {
        require(bytes.size in 1..1024 && recognizes(bytes))
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.skipBytes(3)
            val kind = Kind.entries.getOrNull(input.readUnsignedByte()) ?: error("Unsupported file protocol")
            val session = input.readUTF(); require(NearbyProtocol.validSession(session))
            val id = input.readLong()
            val offer = if (kind == Kind.OFFER) FileOffer(id, input.readUTF(), input.readLong(), input.readUTF(), input.readUTF()) else null
            offer?.let(::validate)
            require(input.available() == 0)
            return Frame(kind, session, id, offer)
        }
    }
}
