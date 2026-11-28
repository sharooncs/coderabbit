package app.housemove.sync

import app.housemove.data.AgreementKey
import app.housemove.data.PlatformCrypto
import app.housemove.data.outputStreamOf
import app.housemove.data.inputStreamOf
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The iPhone app's crypto check of the encrypted sync (data/CryptoCheck.kt), with the CryptoKit the app registered: the
 * recorded handshakes and frames (noise/handshakes.json, made by the JVM's NoiseFixtureTest and checked by the common
 * tests on the simulator with the reference crypto) played again with the same fixed keys, as the common tests' NoiseKat
 * plays them, and compared byte for byte; then both handshakes with keys CryptoKit makes now.
 */
internal object NoiseCheck {
    fun keyOf(k: AgreementKey): NoiseKey = k.noiseKey() ?: throw IllegalStateException("not a P-256 key")

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    /** Collects what an output stream is given. */
    private class Sink {
        var bytes = ByteArray(0)
        val stream = outputStreamOf({ b, off, len -> bytes += b.copyOfRange(off, off + len) }, {}, {})
    }

    private fun source(b: ByteArray): app.housemove.data.InputStream {
        var at = 0
        return inputStreamOf { buf, off, len ->
            if (at == b.size) -1 else {
                val n = minOf(len, b.size - at)
                b.copyInto(buf, off, at, at + n)
                at += n
                n
            }
        }
    }

    /** What the channel carries in the recorded file, as NoiseKat says it. */
    private val said: List<Pair<String, ByteArray>> = listOf(
        "initiator" to "hello from the dialling phone".toUtf8(),
        "responder" to "and from the answering one".toUtf8(),
        "initiator" to ByteArray(SecureChannel.MAX_PLAIN + 4_481) { ((it * 31 + 7) and 0xff).toByte() },
        "responder" to "got it".toUtf8(),
    )

    /** Plays the recorded [pattern] ([t], of the move [moveId]) with [crypto] and the keys [key] names; true when every byte is the recorded one. */
    fun replay(crypto: PlatformCrypto, t: JsonObject, pattern: String, moveId: String, key: (String) -> NoiseKey): Boolean {
        val ik = pattern == Noise.IK
        val suffix = if (ik) "ik" else "xx"
        val psk = if (ik) null else WifiSync.codeKey(t.str("code"), moveId)
        if (psk != null && psk.toHex() != t.str("psk")) return false
        val prologue = if (ik) AutoSession.prologue(moveId) else WifiSync.codePrologue(moveId)
        if (prologue.toHex() != t.str("prologue")) return false
        val i = Handshake(crypto, pattern, true, prologue, key("initiator static"), rs = if (ik) key("responder static").point else null, psk = psk) { key("initiator ephemeral $suffix") }
        val r = Handshake(crypto, pattern, false, prologue, key("responder static"), psk = psk) { key("responder ephemeral $suffix") }
        for ((n, m) in t.getValue("messages").jsonArray.withIndex()) {
            val o = m.jsonObject
            val (from, to) = if (n % 2 == 0) i to r else r to i
            val payload = unhex(o.str("payload"))
            val made = from.write(payload)
            if (made.toHex() != o.str("bytes")) return false
            if (to.read(made)?.contentEquals(payload) != true) return false
        }
        if (!i.done || !r.done || i.hash.toHex() != t.str("handshakeHash") || !i.hash.contentEquals(r.hash)) return false
        val (iSend, iReceive) = i.split()
        val (rSend, rReceive) = r.split()
        val keys = t.getValue("keys").jsonObject
        if (iSend.keyForLog().toHex() != keys.str("initiatorToResponder") || rSend.keyForLog().toHex() != keys.str("responderToInitiator")) return false
        // The channel, with the key changed every two frames, as recorded.
        val iOut = Sink()
        val rOut = Sink()
        val ci = SecureChannel(dataInputOf(source(ByteArray(0))), dataOutputOf(iOut.stream), iSend, iReceive, rekeyEvery = 2)
        val cr = SecureChannel(dataInputOf(source(ByteArray(0))), dataOutputOf(rOut.stream), rSend, rReceive, rekeyEvery = 2)
        val frames = ArrayList<Pair<String, ByteArray>>()
        for ((who, plain) in said) {
            val (ch, sink) = if (who == "initiator") ci to iOut else cr to rOut
            val before = sink.bytes.size
            ch.dataOut.write(plain)
            ch.dataOut.flush()
            val sent = sink.bytes.copyOfRange(before, sink.bytes.size)
            var at = 0
            while (at < sent.size) {
                val len = ((sent[at].toInt() and 0xff) shl 8) or (sent[at + 1].toInt() and 0xff)
                frames += who to sent.copyOfRange(at, at + 2 + len)
                at += 2 + len
            }
        }
        val recorded = t.getValue("frames").jsonArray.map { it.jsonObject }
        if (recorded.size != frames.size) return false
        for ((f, want) in frames.zip(recorded)) {
            if (f.first != want.str("from") || f.second.size - 2 != want.getValue("length").jsonPrimitive.int) return false
            val ok = if ("bytes" in want) f.second.toHex() == want.str("bytes")
            else sha256(f.second).toHex() == want.str("sha256") && f.second.copyOf(64).toHex() == want.str("start")
            if (!ok) return false
        }
        // And the other side opens what the initiator sent, in order.
        val back = SecureChannel(dataInputOf(source(iOut.bytes)), dataOutputOf(Sink().stream), rSend, rReceive, rekeyEvery = 2)
        val want = said.filter { it.first == "initiator" }.fold(ByteArray(0)) { a, p -> a + p.second }
        val got = ByteArray(want.size).also { back.dataIn.readFully(it) }
        return got.contentEquals(want)
    }

    /** Both handshakes between two key pairs [crypto] makes now; a message with one byte changed does not open. */
    fun live(crypto: PlatformCrypto): Boolean {
        val a = keyOf(crypto.generateKey())
        val b = keyOf(crypto.generateKey())
        for (pattern in listOf(Noise.IK, Noise.XX_PSK0)) {
            val psk = if (pattern == Noise.XX_PSK0) WifiSync.codeKey("E2CHECK123", "m-check") else null
            val i = Handshake(crypto, pattern, true, "check".toUtf8(), a, rs = if (pattern == Noise.IK) b.point else null, psk = psk)
            val r = Handshake(crypto, pattern, false, "check".toUtf8(), b, psk = psk)
            val first = i.write("one".toUtf8())
            val changed = first.copyOf().also { it[first.size - 1] = (it[first.size - 1].toInt() xor 1).toByte() }
            if (Handshake(crypto, pattern, false, "check".toUtf8(), b, psk = psk).read(changed) != null) return false
            if (r.read(first)?.decodeToString() != "one") return false
            var turn = 1
            while (!i.done || !r.done) {
                val (from, to) = if (turn % 2 == 0) i to r else r to i
                if (to.read(from.write("more".toUtf8()))?.decodeToString() != "more") return false
                turn++
            }
            if (!i.hash.contentEquals(r.hash) || r.remoteStatic?.contentEquals(a.point) != true) return false
        }
        return true
    }
}
