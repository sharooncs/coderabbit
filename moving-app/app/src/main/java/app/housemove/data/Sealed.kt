package app.housemove.data

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The sealing of update files (Update.kt), with nothing but the JCA every phone and JVM has.
 *
 * The contents are encrypted once, with a random AES-256-GCM content key, in chunks of [CHUNK] bytes so that a
 * large file never has to be held whole. Chunk n has the nonce "prefix + n" and is bound to the file's header
 * (its SHA-256), its own number and whether it is the last: a chunk changed, moved, dropped or added, a header
 * changed, or a file cut short, all fail to open. The content key is wrapped once for each phone the update is
 * for: an ephemeral EC P-256 key agreed with that phone's public key (ECDH), stretched with HKDF-SHA256 into a key
 * that seals it with AES-GCM, bound to that phone's ID.
 */

internal object Sealing {
    const val CHUNK = 64 * 1024
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** HKDF-SHA256 (RFC 5869) of [ikm] into [length] bytes, at most 32. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1)
        return mac.doFinal().copyOf(length)
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            updateAAD(aad)
        }

    /** What wrapping a content key for one phone gives: the ephemeral public key, the salt, the nonce and the sealed key. */
    class Wrapped(val ephemeral: ByteArray, val salt: ByteArray, val iv: ByteArray, val sealed: ByteArray)

    private fun info(moveId: String, phoneId: String) = "HouseMove update key 1|$moveId|$phoneId".toByteArray()

    fun wrap(contentKey: ByteArray, to: PublicKey, phoneId: String, moveId: String): Wrapped {
        val g = java.security.KeyPairGenerator.getInstance("EC")
        g.initialize(java.security.spec.ECGenParameterSpec("secp256r1"), random)
        val eph = g.generateKeyPair()
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(eph.private)
        ka.doPhase(to, true)
        val salt = randomBytes(16)
        val kek = hkdf(ka.generateSecret(), salt, info(moveId, phoneId))
        val iv = randomBytes(12)
        val sealed = gcm(Cipher.ENCRYPT_MODE, kek, iv, phoneId.toByteArray()).doFinal(contentKey)
        return Wrapped(eph.public.encoded, salt, iv, sealed)
    }

    /** The content key [w] holds for this phone ([keys], [phoneId]), or null when it cannot be opened with them. */
    fun unwrap(w: Wrapped, keys: PhoneKeys, phoneId: String, moveId: String): ByteArray? = try {
        val eph = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(w.ephemeral))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keys.private)
        ka.doPhase(eph, true)
        val kek = hkdf(ka.generateSecret(), w.salt, info(moveId, phoneId))
        gcm(Cipher.DECRYPT_MODE, kek, w.iv, phoneId.toByteArray()).doFinal(w.sealed).takeIf { it.size == 32 }
    } catch (_: Exception) {
        null
    }

    fun chunkCipher(mode: Int, key: ByteArray, prefix: ByteArray, headerHash: ByteArray, index: Int, last: Boolean): Cipher {
        val iv = ByteBuffer.allocate(12).put(prefix, 0, 8).putInt(index).array()
        val aad = ByteBuffer.allocate(headerHash.size + 5).put(headerHash).putInt(index).put(if (last) 1 else 0).array()
        return gcm(mode, key, iv, aad)
    }
}

/**
 * Writes what goes through it to [out] sealed in chunks (see [Sealing]). [close] seals the last chunk and flushes
 * [out], which it leaves open.
 */
internal class SealedOut(
    private val out: DataOutputStream,
    private val key: ByteArray,
    private val prefix: ByteArray,
    private val headerHash: ByteArray,
) : OutputStream() {
    private val buf = ByteArray(Sealing.CHUNK)
    private var n = 0
    private var index = 0
    private var closed = false

    override fun write(b: Int) {
        if (n == buf.size) seal(last = false)
        buf[n++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            if (n == buf.size) seal(last = false)
            val k = minOf(left, buf.size - n)
            System.arraycopy(b, o, buf, n, k)
            n += k
            o += k
            left -= k
        }
    }

    private fun seal(last: Boolean) {
        if (closed) throw IOException("closed")
        val c = Sealing.chunkCipher(Cipher.ENCRYPT_MODE, key, prefix, headerHash, index, last).doFinal(buf, 0, n)
        out.writeInt(c.size)
        out.write(c)
        index++
        n = 0
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        seal(last = true)
        closed = true
        out.flush()
    }
}

/**
 * Reads what [SealedOut] wrote, chunk by chunk, each checked before any of it is given out. A chunk that does not
 * open, a file cut short or with anything after its last chunk, all end in an IOException. Read it to the end
 * ([drain]) to be sure the whole file was as it was made.
 */
internal class SealedIn(
    private val input: DataInputStream,
    private val key: ByteArray,
    private val prefix: ByteArray,
    private val headerHash: ByteArray,
) : InputStream() {
    private var plain = ByteArray(0)
    private var pos = 0
    private var index = 0
    private var done = false
    private var nextLength: Int? = lengthOrEnd() ?: throw EOFException("no contents")

    private fun lengthOrEnd(): Int? {
        val a = input.read()
        if (a < 0) return null
        val b = input.read()
        val c = input.read()
        val d = input.read()
        if ((b or c or d) < 0) throw EOFException("cut short")
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    private fun fill(): Boolean {
        while (pos >= plain.size) {
            if (done) return false
            val len = nextLength ?: throw EOFException("cut short")
            if (len < 16 || len > Sealing.CHUNK + 16) throw IOException("bad chunk")
            val sealed = ByteArray(len)
            input.readFully(sealed)
            nextLength = lengthOrEnd()
            val last = nextLength == null
            plain = try {
                Sealing.chunkCipher(Cipher.DECRYPT_MODE, key, prefix, headerHash, index, last).doFinal(sealed)
            } catch (e: Exception) {
                throw IOException("a chunk does not open", e)
            }
            pos = 0
            index++
            if (last) done = true
        }
        return true
    }

    override fun read(): Int = if (!fill()) -1 else plain[pos++].toInt() and 0xff

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val k = minOf(len, plain.size - pos)
        System.arraycopy(plain, pos, b, off, k)
        pos += k
        return k
    }

    /** Read to the end, so every chunk has been checked. */
    fun drain() {
        val skip = ByteArray(8192)
        while (read(skip, 0, skip.size) >= 0) Unit
    }
}
