package app.housemove.sync

import app.housemove.data.AgreementKey
import app.housemove.data.AppJson
import app.housemove.data.AppleCryptoKit
import app.housemove.data.PlatformCrypto
import app.housemove.data.SCHEMA
import app.housemove.data.Update
import app.housemove.data.base64Encode
import app.housemove.data.outputStreamOf
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.data.write
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The iPhone app's crypto check of HPKE (data/CryptoCheck.kt; design/encryption.md, "Mailbox as built"), with the CryptoKit
 * the app registered:
 *
 * - the CFRG's test vectors for HouseMove's suite (hpke/vectors.json), every value made again with CryptoKit's ECDH and
 *   AES-GCM under HouseMove's HPKE;
 * - HouseMove's mailbox envelope and update file of format 2 with fixed keys (hpke/housemove.json, recorded by the JVM),
 *   made again byte for byte, as the common tests' HpkeKat makes them, and opened;
 * - against CryptoKit's own HPKE (iOS 17's `HPKE.Sender` and `HPKE.Recipient`, mode_auth, P256_SHA256_AES_GCM_256): what
 *   HouseMove seals, Apple's opens, and the other way round.
 */
internal object HpkeCheck {
    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.hex(k: String) = unhex(str(k))

    /** Every value of the CFRG's vectors, made with [crypto] and the keys [keyOf] makes from scalars. */
    fun vectors(crypto: PlatformCrypto, file: JsonObject, keyOf: (ByteArray) -> AgreementKey): Boolean {
        for (v in file.getValue("vectors").jsonArray.map { it.jsonObject }) {
            val mode = v.getValue("mode").jsonPrimitive.int
            val e = NoiseCheck.keyOf(keyOf(v.hex("skEm")))
            val r = NoiseCheck.keyOf(keyOf(v.hex("skRm")))
            if (e.point.toHex() != v.str("pkEm") || r.point.toHex() != v.str("pkRm")) return false
            val info = v.hex("info")
            val s = if (mode == Hpke.MODE_AUTH) NoiseCheck.keyOf(keyOf(v.hex("skSm"))) else null
            val sender = (if (s != null) Hpke.setupAuthS(crypto, r.point, info, s) { e } else Hpke.setupBaseS(crypto, r.point, info) { e }) ?: return false
            val back = (if (s != null) Hpke.scheduleAuthR(crypto, sender.enc, r, info, s.point) else Hpke.scheduleBaseR(crypto, sender.enc, r, info)) ?: return false
            if (sender.enc.toHex() != v.str("enc") || back.sharedSecret.toHex() != v.str("shared_secret")) return false
            if (back.context.key.toHex() != v.str("key") || back.context.baseNonce.toHex() != v.str("base_nonce")) return false
            if (back.context.exporterSecret.toHex() != v.str("exporter_secret") || sender.schedule.secret.toHex() != v.str("secret")) return false
            for (m in v.getValue("encryptions").jsonArray.map { it.jsonObject }) {
                val seq = m.getValue("seq").jsonPrimitive.long
                sender.context.seq = seq
                back.context.seq = seq
                if (sender.context.seal(m.hex("aad"), m.hex("pt")).toHex() != m.str("ct")) return false
                if (back.context.open(m.hex("aad"), m.hex("ct"))?.toHex() != m.str("pt")) return false
            }
            for (x in v.getValue("exports").jsonArray.map { it.jsonObject }) {
                if (back.context.export(x.hex("exporter_context"), x.getValue("L").jsonPrimitive.int).toHex() != x.str("exported_value")) return false
            }
        }
        return true
    }

    private fun payload(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 131 + seed) and 0xff).toByte() }

    /** HouseMove's envelope, sealed again with [crypto] byte for byte, and opened. */
    fun envelope(crypto: PlatformCrypto, file: JsonObject, keyOf: (ByteArray) -> AgreementKey): Boolean {
        val scalars = file.getValue("scalars").jsonObject
        fun key(name: String) = NoiseCheck.keyOf(keyOf(unhex(scalars.getValue(name).jsonPrimitive.content)))
        val env = file.getValue("envelope").jsonObject
        val move = file.str("moveId")
        val head = AppJson.decodeFromString<Envelope.Head>(env.str("head"))
        val body = payload(1_000, 5)
        val sender = key("sender static")
        val recipient = key("recipient static")
        val sealed = Envelope.seal(crypto, sender, recipient.point, move, env.str("to"), head, body) { key("envelope ephemeral") } ?: return false
        if (sealed.toHex() != env.str("bytes")) return false
        val opened = Envelope.open(crypto, recipient, env.str("to"), listOf(move), mapOf(env.str("from") to sender.point), sealed) ?: return false
        return opened.head == head && sha256(opened.body).toHex() == env.str("bodySha256")
    }

    /** HouseMove's update file of format 2, written again with [crypto] byte for byte. */
    fun update(crypto: PlatformCrypto, file: JsonObject, keyOf: (ByteArray) -> AgreementKey): Boolean {
        val scalars = file.getValue("scalars").jsonObject
        fun key(name: String) = NoiseCheck.keyOf(keyOf(unhex(scalars.getValue(name).jsonPrimitive.content)))
        val up = file.getValue("update").jsonObject
        val move = file.str("moveId")
        val sender = key("sender static")
        val phones = up.getValue("to").jsonArray.map { it.jsonPrimitive.content }
        val keys = listOf(key("recipient static") to "update ephemeral recipient", key("other static") to "update ephemeral other")
        val contentKey = up.hex("contentKey")
        val prefix = up.hex("prefix")
        val body = payload(70_000, 9)
        val senders = phones.zip(keys).map { (phone, k) ->
            phone to (Hpke.setupAuthS(crypto, k.first.point, Update.hpkeInfo(move, phone), sender) { key(k.second) } ?: return false)
        }
        val header = Update.Header(
            format = Update.FORMAT_HPKE, schema = SCHEMA, moveId = move, from = file.getValue("envelope").jsonObject.str("from"), at = 1_767_225_600_000L,
            nonce = base64Encode(prefix), to = senders.map { (phone, s) -> Update.To(phone = phone, enc = base64Encode(s.enc), key = base64Encode(s.context.seal(prefix, contentKey))) },
        )
        var out = ByteArray(0)
        val sink = outputStreamOf({ b, off, len -> out += b.copyOfRange(off, off + len) }, {}, {})
        Update.writeSealedHpke(crypto, sink, header, contentKey, prefix, { h ->
            senders.map { (phone, s) -> Update.Sum(phone, base64Encode(s.context.seal("HouseMove update 2 sum".toUtf8(), h))) }
        }) {
            it.write(body, 0, body.size)
            it.close()
        }
        return out.size == up.getValue("fileLength").jsonPrimitive.int && sha256(out).toHex() == up.str("fileSha256")
    }

    /**
     * HouseMove's HPKE against CryptoKit's own: a message HouseMove seals (mode_auth, with the fixed keys) opens with
     * `HPKE.Recipient`, and one `HPKE.Sender` seals opens with HouseMove's. Null when this CryptoKit has no HPKE.
     */
    fun againstCryptoKit(kit: AppleCryptoKit, crypto: PlatformCrypto, file: JsonObject, keyOf: (ByteArray) -> AgreementKey): Boolean {
        val scalars = file.getValue("scalars").jsonObject
        fun raw(name: String) = unhex(scalars.getValue(name).jsonPrimitive.content)
        val sender = NoiseCheck.keyOf(keyOf(raw("sender static")))
        val recipient = NoiseCheck.keyOf(keyOf(raw("recipient static")))
        val info = Envelope.info(file.str("moveId"), "ppriyaphone2345678ab")
        val aad = "HouseMove mailbox 1 head".toUtf8()
        val plain = "Sealed by HouseMove's HPKE, opened by CryptoKit's".toUtf8()
        val ours = Hpke.setupAuthS(crypto, recipient.point, info, sender) ?: return false
        val sealed = ours.context.seal(aad, plain)
        val opened = kit.hpkeOpenAuth(raw("recipient static"), sender.point, info, aad, ours.enc, sealed) ?: return false
        if (!opened.contentEquals(plain)) return false
        val theirs = "Sealed by CryptoKit's HPKE, opened by HouseMove's".toUtf8()
        val fromKit = kit.hpkeSealAuth(recipient.point, raw("sender static"), info, aad, theirs) ?: return false
        if (fromKit.size < Hpke.ENC + Hpke.TAG) return false
        val ctx = Hpke.setupAuthR(crypto, fromKit.copyOf(Hpke.ENC), recipient, info, sender.point) ?: return false
        return ctx.open(aad, fromKit.copyOfRange(Hpke.ENC, fromKit.size))?.contentEquals(theirs) == true
    }
}
