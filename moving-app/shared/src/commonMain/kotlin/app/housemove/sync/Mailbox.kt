package app.housemove.sync

import app.housemove.data.AppJson
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.data.PlatformCrypto
import app.housemove.data.decodeUtf8
import app.housemove.data.inputStreamOf
import app.housemove.data.outputStreamOf
import app.housemove.data.toUtf8
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/*
 * The internet mailbox (design/encryption.md, "The mailbox" and "Mailbox as built"): phones of a move that are not on the
 * same Wi-Fi leave each other sealed messages in a small program on the owner's always-on laptop, which Tailscale Funnel
 * puts at a fixed https address. The laptop stores and forwards sealed bytes and nothing else: it never holds a key that
 * opens them. Here: the mailbox's code, the envelopes, and the program's API. The engine is MailboxSync.
 */

/**
 * The code the mailbox program shows once, as a QR code, at its first start: `HMBOX:1:<house token>:<address>`. The house
 * token (64 hex digits) is what registering a mailbox there takes; the address is the laptop's Tailscale Funnel address.
 */
internal object MailboxCode {
    const val PREFIX = "HMBOX:1:"

    fun encode(access: MailboxAccess): String = PREFIX + access.token + ":" + access.url

    /** The access in a scanned or pasted code (as the QR holds it, or as iOS hands over an `hmbox:` link), or null. */
    fun parse(text: String, at: Long): MailboxAccess? {
        val t = text.trim()
        if (!t.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = t.substring(PREFIX.length)
        val colon = rest.indexOf(':')
        if (colon < 0) return null
        val token = rest.substring(0, colon).lowercase()
        val url = cleanUrl(rest.substring(colon + 1)) ?: return null
        if (!isToken(token)) return null
        return MailboxAccess(url, token, at)
    }

    /** Is [t] a house token or a read secret as the mailbox makes them: 64 lower-case hex digits (256 bits). */
    fun isToken(t: String): Boolean = t.length == 64 && t.all { it in '0'..'9' || it in 'a'..'f' }

    /** Is [id] a mailbox ID: 32 lower-case hex digits (128 bits). */
    fun isMailboxId(id: String): Boolean = id.length == 32 && id.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * [url] as the mailbox's address is kept: an https address (in tests also http://127.0.0.1), with no user, query or
     * fragment, and no slash at the end. Null when it is not one.
     */
    fun cleanUrl(url: String): String? {
        val u = url.trim().trimEnd('/')
        if (u.any { it.isWhitespace() || it == '?' || it == '#' || it == '@' || it == '\\' }) return null
        val host = when {
            u.startsWith("https://", ignoreCase = true) -> u.substring(8)
            Http.loopbackForTests && u.startsWith("http://127.0.0.1:") -> u.substring(7)
            else -> return null
        }
        val name = host.substringBefore('/').substringBefore(':')
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return if (u.startsWith("https://", ignoreCase = true)) "https://" + host else "http://" + host
    }
}

/**
 * A sealed message between two phones of a move, as the mailbox holds it (design/encryption.md, "Mailbox as built"):
 *
 * ```
 * "HMB1" | enc (65) | head length (4, big-endian) | Seal(head) | Seal(body)
 * ```
 *
 * One HPKE context of mode_auth ([Hpke]): the sender's static key with the recipient's pinned one, info = "HouseMove
 * mailbox 1" | move ID | recipient's phone ID (each part as a four-byte length and its bytes). Its first message is the
 * head ([Head], JSON: who sent it, its number, the newest of the recipient's messages the sender has opened, and the
 * mailbox's access), its second the body: a zip of data.json and photos/, as an update file holds. The mailbox sees the
 * envelope's size and nothing else of it: no move, no phone, no name.
 */
internal object Envelope {
    val MAGIC = "HMB1".toUtf8()

    /** The largest envelope the mailbox takes. */
    const val MAX = 8 * 1024 * 1024

    private val HEAD_AAD = "HouseMove mailbox 1 head".toUtf8()
    private val BODY_AAD = "HouseMove mailbox 1 body".toUtf8()

    fun info(moveId: String, phoneId: String): ByteArray = AutoSession.joined("HouseMove mailbox 1".toUtf8(), moveId.toUtf8(), phoneId.toUtf8())

    /**
     * What a message says of itself, sealed: [from] (the sender's phone ID, which must be the phone whose key opened it),
     * its number [seq] from that phone to this one, when it was sealed ([at]), [ack] (the newest of this phone's messages
     * the sender had opened), the sender's data [schema], and the mailbox's [access] when the sender has it.
     */
    @Serializable
    data class Head(
        val from: String = "",
        val seq: Long = 0,
        val at: Long = 0,
        val ack: Long = 0,
        val schema: Int = 0,
        val access: MailboxAccess? = null,
    )

    /** An envelope that opened: its [head] and [body], and the move ID it was sealed with. */
    class Opened(val head: Head, val body: ByteArray, val moveId: String)

    /**
     * [head] and [body] sealed from this phone ([mine]) to the phone [toPhone], whose pinned key is the point [toPoint], in
     * the move [moveId]. Null when it cannot be (a key that is not a point).
     */
    fun seal(
        crypto: PlatformCrypto, mine: NoiseKey, toPoint: ByteArray, moveId: String, toPhone: String, head: Head, body: ByteArray,
        ephemeral: () -> NoiseKey = { Noise.newEphemeral(crypto) },
    ): ByteArray? {
        val s = Hpke.setupAuthS(crypto, toPoint, info(moveId, toPhone), mine, ephemeral) ?: return null
        val h = s.context.seal(HEAD_AAD, AppJson.encodeToString(head).toUtf8())
        val b = s.context.seal(BODY_AAD, body)
        val out = ByteArray(MAGIC.size + Hpke.ENC + 4 + h.size + b.size)
        MAGIC.copyInto(out)
        s.enc.copyInto(out, MAGIC.size)
        val at = MAGIC.size + Hpke.ENC
        out[at] = (h.size ushr 24).toByte(); out[at + 1] = (h.size ushr 16).toByte(); out[at + 2] = (h.size ushr 8).toByte(); out[at + 3] = h.size.toByte()
        h.copyInto(out, at + 4)
        b.copyInto(out, at + 4 + h.size)
        return out
    }

    /**
     * Opens [bytes] sealed to this phone ([mine], [myPhone]) in the move known by [moveIds], by one of [senders] (phone ID
     * to its pinned point): each is tried in turn, as the envelope does not say who sealed it. The head must name the phone
     * whose key opened it. Null when it opens with none of them, or is not an envelope.
     */
    fun open(crypto: PlatformCrypto, mine: NoiseKey, myPhone: String, moveIds: List<String>, senders: Map<String, ByteArray>, bytes: ByteArray): Opened? {
        val start = MAGIC.size + Hpke.ENC + 4
        if (bytes.size < start + 2 * Hpke.TAG || bytes.size > MAX) return null
        for (i in MAGIC.indices) if (bytes[i] != MAGIC[i]) return null
        val enc = bytes.copyOfRange(MAGIC.size, MAGIC.size + Hpke.ENC)
        val at = MAGIC.size + Hpke.ENC
        val n = ((bytes[at].toInt() and 0xff) shl 24) or ((bytes[at + 1].toInt() and 0xff) shl 16) or ((bytes[at + 2].toInt() and 0xff) shl 8) or (bytes[at + 3].toInt() and 0xff)
        if (n < Hpke.TAG || n > 64 * 1024 || start + n + Hpke.TAG > bytes.size) return null
        val sealedHead = bytes.copyOfRange(start, start + n)
        for (moveId in moveIds.filter { it.isNotEmpty() }.distinct()) for ((phone, point) in senders) {
            val ctx = Hpke.setupAuthR(crypto, enc, mine, info(moveId, myPhone), point) ?: continue
            val headBytes = ctx.open(HEAD_AAD, sealedHead) ?: continue
            val head = try {
                AppJson.decodeFromString<Head>(headBytes.decodeUtf8())
            } catch (_: Exception) {
                return null
            }
            // Only the phone whose key opened it can have sealed it: it must say it is that phone.
            if (head.from != phone) return null
            val body = ctx.open(BODY_AAD, bytes.copyOfRange(start + n, bytes.size)) ?: return null
            return Opened(head, body, moveId)
        }
        return null
    }
}

/** An envelope waiting in a mailbox, as the program lists it: its [id], [size] and when it came ([at], milliseconds). */
@Serializable
internal data class Listed(val id: String = "", val size: Long = 0, val at: Long = 0)

@Serializable
private data class ListAnswer(val envelopes: List<Listed> = emptyList())

@Serializable
private data class Register(val secret: String = "")

/**
 * The mailbox program's API (moving-app/mailbox/README.md), at [url], through [http]:
 *
 * ```
 * GET    /v1/health                                 the program is there
 * PUT    /v1/mailboxes/{id}               token     register a mailbox: {"secret": read secret}; 201, or 200 if it was already
 * DELETE /v1/mailboxes/{id}               secret    unregister it and its envelopes
 * POST   /v1/mailboxes/{id}/envelopes               leave an envelope (the bytes); anyone who knows the mailbox ID may
 * GET    /v1/mailboxes/{id}/envelopes     secret    {"envelopes":[{"id","size","at"}]}, oldest first
 * GET    /v1/mailboxes/{id}/envelopes/{e} secret    the envelope's bytes
 * DELETE /v1/mailboxes/{id}/envelopes/{e} secret    acknowledge it: it is deleted
 * ```
 *
 * "token" is `Authorization: Bearer <house token>`, "secret" `Authorization: Bearer <read secret>`.
 */
internal class MailboxClient(url: String, private val http: HttpTransport) {
    private val base = MailboxCode.cleanUrl(url) ?: url.trimEnd('/')

    private fun bearer(s: String) = mapOf("Authorization" to "Bearer $s")

    fun health(): HttpAnswer = http.call(HttpRequest("GET", "$base/v1/health"))

    fun register(id: String, secret: String, token: String): HttpAnswer =
        http.call(HttpRequest("PUT", "$base/v1/mailboxes/$id", bearer(token) + ("Content-Type" to "application/json"), AppJson.encodeToString(Register(secret)).toUtf8()))

    fun unregister(id: String, secret: String): HttpAnswer = http.call(HttpRequest("DELETE", "$base/v1/mailboxes/$id", bearer(secret)))

    fun post(id: String, envelope: ByteArray): HttpAnswer =
        http.call(HttpRequest("POST", "$base/v1/mailboxes/$id/envelopes", mapOf("Content-Type" to "application/octet-stream"), envelope))

    /** The envelopes waiting, oldest first, or null when the answer was not a list (see the [HttpAnswer]). */
    fun list(id: String, secret: String): Pair<HttpAnswer, List<Listed>?> {
        val a = http.call(HttpRequest("GET", "$base/v1/mailboxes/$id/envelopes", bearer(secret)))
        if (a.status != 200) return a to null
        val list = try {
            AppJson.decodeFromString<ListAnswer>(a.body.decodeUtf8()).envelopes
        } catch (_: Exception) {
            null
        }
        return a to list?.filter { isEnvelopeId(it.id) }?.sortedWith(compareBy({ it.at }, { it.id }))
    }

    fun fetch(id: String, secret: String, envelope: String): HttpAnswer = http.call(HttpRequest("GET", "$base/v1/mailboxes/$id/envelopes/$envelope", bearer(secret)))

    fun delete(id: String, secret: String, envelope: String): HttpAnswer = http.call(HttpRequest("DELETE", "$base/v1/mailboxes/$id/envelopes/$envelope", bearer(secret)))

    companion object {
        /** An envelope ID as the program makes them: 24 lower-case hex digits (the time it came, then random). */
        fun isEnvelopeId(e: String): Boolean = e.length == 24 && e.all { it in '0'..'9' || it in 'a'..'f' }
    }
}

/** Bytes written into memory, as an output stream (a message's zip). */
internal class MemoryOut {
    private var buf = ByteArray(64 * 1024)
    var size = 0
        private set

    val stream: OutputStream = outputStreamOf({ b, off, len ->
        if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
        b.copyInto(buf, size, off, off + len)
        size += len
    }, {}, {})

    fun bytes(): ByteArray = buf.copyOf(size)
}

/** [b] as an input stream. */
internal fun memoryIn(b: ByteArray): InputStream {
    var pos = 0
    return inputStreamOf { dst, off, len ->
        if (len == 0) 0
        else if (pos >= b.size) -1
        else {
            val n = minOf(len, b.size - pos)
            b.copyInto(dst, off, pos, pos + n)
            pos += n
            n
        }
    }
}
