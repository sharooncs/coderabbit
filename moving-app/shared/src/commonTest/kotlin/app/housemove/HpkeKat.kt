package app.housemove

import app.housemove.data.AgreementKey
import app.housemove.data.PlatformCrypto
import app.housemove.data.SCHEMA
import app.housemove.data.Update
import app.housemove.data.base64Encode
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.data.write
import app.housemove.sync.Envelope
import app.housemove.sync.Hpke
import app.housemove.sync.NoiseKey
import app.housemove.sync.noiseKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * HouseMove's own HPKE messages with fixed keys (src/jvmTest/resources/hpke/housemove.json, recorded by HpkeFixtureTest on
 * the JVM): a mailbox envelope (Envelope.seal: "HMB1", enc, the sealed head and body) and an update file of format 2 for
 * two phones (Update.writeSealedHpke: the header with each phone's HPKE envelope of the content key, the chunks, the end
 * mark and the trailer with each phone's sealed hash). Every byte is written down: the JVM (the JCA), the reference crypto on
 * the iPhone simulator (HpkeCommonTest), and the iPhone app's CryptoKit (CryptoCheck) must make exactly these, and
 * tools/hpke_check.py opens them with Python's `cryptography` and an HPKE written apart.
 *
 * The keys come from labels (SHA-256 of a text), so anyone can make them again.
 */
internal object HpkeKat {
    private fun scalar(label: String): ByteArray = sha256("HouseMove hpke test key: $label".toUtf8())

    const val MOVE = "m3906c46cfd44f4074ce7"
    const val SENDER = "pashaphone23456789ab"
    const val RECIPIENT = "ppriyaphone2345678ab"
    const val OTHER = "pomarphone234567890ab"

    val scalars: Map<String, ByteArray> = listOf("sender static", "recipient static", "other static", "envelope ephemeral",
        "update ephemeral recipient", "update ephemeral other").associateWith { scalar(it) }

    /** The body of the envelope and the contents of the update: bytes anyone can make again (no zip, to keep it short). */
    fun payload(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 131 + seed) and 0xff).toByte() }

    fun make(crypto: PlatformCrypto, keyOf: (ByteArray) -> AgreementKey): JsonObject {
        fun key(name: String): NoiseKey = keyOf(scalars.getValue(name)).noiseKey() ?: throw IllegalStateException("$name is not a P-256 key")
        val sender = key("sender static")
        val recipient = key("recipient static")
        val other = key("other static")
        return buildJsonObject {
            put("about", "HouseMove's HPKE messages (design/encryption.md, \"Mailbox as built\"): a mailbox envelope and an update file of format 2, " +
                "mode_auth with DHKEM(P-256, HKDF-SHA256), HKDF-SHA256 and AES-256-GCM, with fixed keys. Made by HpkeFixtureTest; not to be edited.")
            put("moveId", MOVE)
            putJsonObject("scalars") { for ((k, v) in scalars) put(k, v.toHex()) }
            putJsonObject("publicKeys") { for (k in scalars.keys) put(k, key(k).point.toHex()) }
            putJsonObject("envelope") {
                val head = Envelope.Head(from = SENDER, seq = 7, at = 1_767_225_600_000L, ack = 3, schema = SCHEMA)
                val body = payload(1_000, 5)
                val sealed = Envelope.seal(crypto, sender, recipient.point, MOVE, RECIPIENT, head, body) { key("envelope ephemeral") }
                    ?: throw IllegalStateException("the envelope did not seal")
                val opened = Envelope.open(crypto, recipient, RECIPIENT, listOf(MOVE), mapOf(SENDER to sender.point), sealed)
                check(opened != null && opened.body.contentEquals(body) && opened.head == head) { "the envelope does not open" }
                put("from", SENDER)
                put("to", RECIPIENT)
                put("info", Envelope.info(MOVE, RECIPIENT).toHex())
                put("head", app.housemove.data.AppJson.encodeToString(Envelope.Head.serializer(), head))
                put("bodySha256", sha256(body).toHex())
                put("bytes", sealed.toHex())
            }
            putJsonObject("update") {
                val contentKey = sha256("HouseMove hpke test content key".toUtf8())
                val prefix = sha256("HouseMove hpke test nonce prefix".toUtf8()).copyOf(8)
                val body = payload(70_000, 9)
                val to = listOf(RECIPIENT to (recipient to "update ephemeral recipient"), OTHER to (other to "update ephemeral other"))
                val senders = to.map { (phone, k) ->
                    phone to (Hpke.setupAuthS(crypto, k.first.point, Update.hpkeInfo(MOVE, phone), sender) { key(k.second) } ?: throw IllegalStateException("no setup"))
                }
                val header = Update.Header(
                    format = Update.FORMAT_HPKE, schema = SCHEMA, moveId = MOVE, from = SENDER, at = 1_767_225_600_000L, nonce = base64Encode(prefix),
                    to = senders.map { (phone, s) -> Update.To(phone = phone, enc = base64Encode(s.enc), key = base64Encode(s.context.seal(prefix, contentKey))) },
                )
                val out = BytesOut()
                Update.writeSealedHpke(crypto, out.stream, header, contentKey, prefix, { h ->
                    senders.map { (phone, s) -> Update.Sum(phone, base64Encode(s.context.seal("HouseMove update 2 sum".toUtf8(), h))) }
                }) {
                    it.write(body, 0, body.size)
                    it.close()
                }
                val file = out.bytes()
                put("contentKey", contentKey.toHex())
                put("prefix", prefix.toHex())
                put("contentsSha256", sha256(body).toHex())
                putJsonArray("to") { for ((phone, _) in to) add(phone) }
                put("infoRecipient", Update.hpkeInfo(MOVE, RECIPIENT).toHex())
                put("fileSha256", sha256(file).toHex())
                put("fileLength", file.size)
                put("start", file.copyOf(2_000).toHex())
                put("end", file.copyOfRange(file.size - 2_000, file.size).toHex())
            }
        }
    }
}
