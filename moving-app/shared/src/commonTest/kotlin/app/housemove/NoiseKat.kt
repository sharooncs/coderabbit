package app.housemove

import app.housemove.data.AgreementKey
import app.housemove.data.PlatformCrypto
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.sync.AutoSession
import app.housemove.sync.Handshake
import app.housemove.sync.Noise
import app.housemove.sync.NoiseKey
import app.housemove.sync.SecureChannel
import app.housemove.sync.WifiSync
import app.housemove.sync.dataInputOf
import app.housemove.sync.dataOutputOf
import app.housemove.sync.flush
import app.housemove.sync.noiseKey
import app.housemove.sync.readFully
import app.housemove.sync.write
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The encrypted sync's known answers (src/jvmTest/resources/noise/handshakes.json, recorded by NoiseFixtureTest on the
 * JVM): an IK handshake (syncing by itself) and an XXpsk0 handshake (a code sync), each with fixed static and one-use keys,
 * then frames of the encrypted channel both ways, with the key changed every two frames so that a rekey is in it. Every
 * byte is written down: the JVM (the JCA), the reference crypto on the iPhone simulator (NoiseCommonTest), and the iPhone
 * app's CryptoKit (CryptoCheck.kt, which plays the same handshakes in iosMain) must make exactly these.
 *
 * The keys come from labels (SHA-256 of a text), so anyone can make them again; tools/noise_check.py checks the recorded
 * file with Python's `cryptography` and a Noise written apart from this one.
 */
internal object NoiseKat {
    private fun scalar(label: String): ByteArray = sha256("HouseMove noise test key: $label".toUtf8())

    const val MOVE = "m3906c46cfd44f4074ce7"
    const val DIALLER = "ppriyaphone2345678ab"
    const val ANSWERER = "pashaphone23456789ab"
    const val CODE = "E2GQ7K2MXP"

    /** Every scalar the transcripts use, by name. */
    val scalars: Map<String, ByteArray> = listOf("initiator static", "responder static", "initiator ephemeral ik", "responder ephemeral ik",
        "initiator ephemeral xx", "responder ephemeral xx").associateWith { scalar(it) }

    /** The bytes of the long message sent in the channel: two frames' worth. */
    fun longPlain(): ByteArray = ByteArray(SecureChannel.MAX_PLAIN + 4_481) { ((it * 31 + 7) and 0xff).toByte() }

    /** Both transcripts, with [crypto] and the key pairs [keyOf] makes from scalars, as the recorded file holds them. */
    fun make(crypto: PlatformCrypto, keyOf: (ByteArray) -> AgreementKey): JsonObject {
        fun key(name: String): NoiseKey = keyOf(scalars.getValue(name)).noiseKey() ?: throw IllegalStateException("$name is not a P-256 key")
        return buildJsonObject {
            put("about", "Noise_IK and Noise_XXpsk0 over P-256, AES-256-GCM and SHA-256, as HouseMove 2.4 syncs (design/encryption.md, Part 2). Made by NoiseFixtureTest; not to be edited.")
            put("moveId", MOVE)
            putJsonObject("scalars") { for ((k, v) in scalars) put(k, v.toHex()) }
            putJsonObject("publicKeys") { for (k in scalars.keys) put(k, key(k).point.toHex()) }
            putJsonObject("ik") {
                val d = Handshake(crypto, Noise.IK, true, AutoSession.prologue(MOVE), key("initiator static"), key("responder static").point) { key("initiator ephemeral ik") }
                val a = Handshake(crypto, Noise.IK, false, AutoSession.prologue(MOVE), key("responder static")) { key("responder ephemeral ik") }
                put("protocol", Noise.protocolName(Noise.IK))
                put("prologue", AutoSession.prologue(MOVE).toHex())
                play(this, d, a, listOf(AutoSession.joined(DIALLER.toUtf8()), AutoSession.joined(ANSWERER.toUtf8(), AutoSession.OK.toUtf8())))
            }
            putJsonObject("xxpsk0") {
                val psk = WifiSync.codeKey(CODE, MOVE)
                val j = Handshake(crypto, Noise.XX_PSK0, true, WifiSync.codePrologue(MOVE), key("initiator static"), psk = psk) { key("initiator ephemeral xx") }
                val h = Handshake(crypto, Noise.XX_PSK0, false, WifiSync.codePrologue(MOVE), key("responder static"), psk = psk) { key("responder ephemeral xx") }
                put("protocol", Noise.protocolName(Noise.XX_PSK0))
                put("code", CODE)
                put("psk", psk.toHex())
                put("prologue", WifiSync.codePrologue(MOVE).toHex())
                play(this, j, h, listOf(ByteArray(0), AutoSession.joined(ANSWERER.toUtf8()), AutoSession.joined(DIALLER.toUtf8())))
            }
        }
    }

    /** Plays the handshake of [i] and [r] with [payloads], then the channel, into [o]. */
    private fun play(o: JsonObjectBuilder, i: Handshake, r: Handshake, payloads: List<ByteArray>) {
        o.putJsonArray("messages") {
            for ((n, p) in payloads.withIndex()) {
                val (from, to) = if (n % 2 == 0) i to r else r to i
                val m = from.write(p)
                val got = to.read(m) ?: throw IllegalStateException("message ${n + 1} did not open")
                check(got.contentEquals(p)) { "message ${n + 1}'s payload changed" }
                add(buildJsonObject {
                    put("payload", p.toHex())
                    put("bytes", m.toHex())
                })
            }
        }
        check(i.done && r.done && i.hash.contentEquals(r.hash)) { "the two sides do not agree" }
        o.put("handshakeHash", i.hash.toHex())
        val (iSend, iReceive) = i.split()
        val (rSend, rReceive) = r.split()
        // The keys, which only a test may see: those of initiator to responder, then of responder to initiator.
        o.putJsonObject("keys") {
            put("initiatorToResponder", iSend.keyForLog().toHex())
            put("responderToInitiator", rSend.keyForLog().toHex())
        }
        // The channel, with the keys changed every two frames: a short message, its answer, a long one, an answer.
        val iOut = BytesOut()
        val rOut = BytesOut()
        val iChannel = SecureChannel(dataInputOf(bytesIn(ByteArray(0))), dataOutputOf(iOut.stream), iSend, iReceive, rekeyEvery = 2)
        val rChannel = SecureChannel(dataInputOf(bytesIn(ByteArray(0))), dataOutputOf(rOut.stream), rSend, rReceive, rekeyEvery = 2)
        val said = listOf("i" to "hello from the dialling phone".toUtf8(), "r" to "and from the answering one".toUtf8(), "i" to longPlain(), "r" to "got it".toUtf8())
        val sent = ArrayList<Pair<String, ByteArray>>()
        for ((who, plain) in said) {
            val (ch, out) = if (who == "i") iChannel to iOut else rChannel to rOut
            val before = out.bytes().size
            ch.dataOut.write(plain)
            ch.dataOut.flush()
            sent += who to out.bytes().copyOfRange(before, out.bytes().size)
        }
        // Each side reads back what the other sent, as one stream.
        val toR = SecureChannel(dataInputOf(bytesIn(iOut.bytes())), dataOutputOf(BytesOut().stream), rSend, rReceive, rekeyEvery = 2)
        o.putJsonArray("frames") {
            for ((who, bytes) in sent) {
                var at = 0
                while (at < bytes.size) {
                    val len = ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)
                    val frame = bytes.copyOfRange(at, at + 2 + len)
                    add(buildJsonObject {
                        put("from", if (who == "i") "initiator" else "responder")
                        put("length", len)
                        if (frame.size <= 512) put("bytes", frame.toHex()) else {
                            put("sha256", sha256(frame).toHex())
                            put("start", frame.copyOf(64).toHex())
                        }
                    })
                    at += 2 + len
                }
            }
        }
        o.putJsonArray("plain") { for ((who, p) in said) add(buildJsonObject { put("from", if (who == "i") "initiator" else "responder"); if (p.size <= 512) put("text", p.decodeToString()) else { put("length", p.size); put("sha256", sha256(p).toHex()) } }) }
        // The other side opens it all: what was said, in order.
        val back = ByteArray(said.filter { it.first == "i" }.sumOf { it.second.size })
        toR.dataIn.readFully(back)
        check(back.contentEquals(said.filter { it.first == "i" }.fold(ByteArray(0)) { a, p -> a + p.second })) { "the channel did not carry what was said" }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    val PRETTY = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}
