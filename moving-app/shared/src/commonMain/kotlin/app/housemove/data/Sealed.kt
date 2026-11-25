package app.housemove.data

import kotlin.concurrent.Volatile

/*
 * The sealing of update files (Update.kt), in common Kotlin on top of the platform's P-256 and AES-GCM ([PlatformCrypto]:
 * the JCA on Android and the JVM, CryptoKit on the iPhone), with HKDF and SHA-256 written here once. Every phone works out
 * the same bytes: golden/crypto.json and golden/update.json, recorded on 2.1.1, check it on the JVM and on the iPhone.
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
    const val TAG = 16

    /**
     * Where the random bytes of an update come from (content key, nonce prefix, salts and IVs): the platform's strong
     * random source. A known-answer test that has to repeat a sealing exactly swaps in fixed bytes.
     */
    @Volatile
    var random: (Int) -> ByteArray = ::secureBytes

    fun randomBytes(n: Int): ByteArray = random(n)

    fun sha256(b: ByteArray): ByteArray = app.housemove.data.sha256(b)

    /** HKDF-SHA256 (RFC 5869) of [ikm] into [length] bytes, at most 32: one block. An empty salt is 32 zero bytes. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        return hmacSha256(prk, info + byteArrayOf(1)).copyOf(length)
    }

    /** What wrapping a content key for one phone gives: the ephemeral public key (X.509), the salt, the nonce and the sealed key. */
    class Wrapped(val ephemeral: ByteArray, val salt: ByteArray, val iv: ByteArray, val sealed: ByteArray)

    private fun info(moveId: String, phoneId: String) = "HouseMove update key 1|$moveId|$phoneId".toUtf8()

    /**
     * [contentKey] wrapped for the phone [phoneId], whose public key is [to] (X.509): agreed with the one-use key [ephemeral],
     * stretched with [salt], sealed with [iv]. Throws IllegalArgumentException when [to] is not a key [ephemeral] can agree with.
     */
    fun wrap(
        crypto: PlatformCrypto,
        contentKey: ByteArray,
        to: ByteArray,
        phoneId: String,
        moveId: String,
        ephemeral: AgreementKey = crypto.generateKey(),
        salt: ByteArray = randomBytes(16),
        iv: ByteArray = randomBytes(12),
    ): Wrapped {
        val secret = ephemeral.agree(to) ?: throw IllegalArgumentException("not a P-256 public key")
        val kek = hkdf(secret, salt, info(moveId, phoneId))
        val sealed = crypto.sealGcm(kek, iv, phoneId.toUtf8(), contentKey)
        return Wrapped(ephemeral.publicDer, salt, iv, sealed)
    }

    /** The content key [w] holds for this phone ([keys], [phoneId]), or null when it cannot be opened with them. */
    fun unwrap(crypto: PlatformCrypto, w: Wrapped, keys: UpdateKeyPair, phoneId: String, moveId: String): ByteArray? = try {
        val secret = keys.agree(w.ephemeral)
        if (secret == null) {
            null
        } else {
            val kek = hkdf(secret, w.salt, info(moveId, phoneId))
            crypto.openGcm(kek, w.iv, phoneId.toUtf8(), w.sealed)?.takeIf { it.size == 32 }
        }
    } catch (_: Exception) {
        null
    }

    /** Chunk [index]'s nonce: the 8-byte [prefix], then [index] as 4 big-endian bytes. */
    fun chunkNonce(prefix: ByteArray, index: Int): ByteArray {
        val n = ByteArray(12)
        prefix.copyInto(n, 0, 0, 8)
        putInt(n, 8, index)
        return n
    }

    /** Chunk [index]'s additional data: the header's SHA-256, [index] as 4 big-endian bytes, then 1 for the last chunk or 0. */
    fun chunkAad(headerHash: ByteArray, index: Int, last: Boolean): ByteArray {
        val a = ByteArray(headerHash.size + 5)
        headerHash.copyInto(a)
        putInt(a, headerHash.size, index)
        a[a.size - 1] = if (last) 1 else 0
        return a
    }

    fun sealChunk(crypto: PlatformCrypto, key: ByteArray, prefix: ByteArray, headerHash: ByteArray, index: Int, last: Boolean, plain: ByteArray): ByteArray =
        crypto.sealGcm(key, chunkNonce(prefix, index), chunkAad(headerHash, index, last), plain)

    fun openChunk(crypto: PlatformCrypto, key: ByteArray, prefix: ByteArray, headerHash: ByteArray, index: Int, last: Boolean, sealed: ByteArray): ByteArray? =
        crypto.openGcm(key, chunkNonce(prefix, index), chunkAad(headerHash, index, last), sealed)

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }
}

/** [v] as 4 big-endian bytes to [out] (DataOutputStream.writeInt). */
internal fun writeInt(out: OutputStream, v: Int) {
    val b = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    out.write(b, 0, 4)
}

/** Fills [b] from [input] (DataInputStream.readFully): EOFException when the bytes end first. */
internal fun readFully(input: InputStream, b: ByteArray) {
    var n = 0
    while (n < b.size) {
        val r = input.read(b, n, b.size - n)
        if (r < 0) throw EOFException()
        n += r
    }
}

/** One byte from [input], 0 to 255, or -1 at the end (InputStream.read()). */
internal fun readByte(input: InputStream): Int {
    val one = ByteArray(1)
    while (true) {
        val r = input.read(one, 0, 1)
        if (r < 0) return -1
        if (r == 1) return one[0].toInt() and 0xff
    }
}

/** 4 big-endian bytes from [input] (DataInputStream.readInt): EOFException when the bytes end first. */
internal fun readInt(input: InputStream): Int {
    val b = ByteArray(4)
    readFully(input, b)
    return ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
}

/**
 * Writes what goes through it ([write], or [stream] for a zip writer) to [out] sealed in chunks (see [Sealing]): each
 * chunk as its length (4 bytes, big-endian) and the sealed bytes. [close] seals the last chunk and flushes [out], which it
 * leaves open. With [endMark] (update files of format 2), a length of 0 follows the last chunk, so that more can come
 * after it; [onChunk] is given each sealed chunk as it goes (format 2 hashes them).
 */
internal class SealedOut(
    private val crypto: PlatformCrypto,
    private val out: OutputStream,
    private val key: ByteArray,
    private val prefix: ByteArray,
    private val headerHash: ByteArray,
    private val endMark: Boolean = false,
    private val onChunk: ((ByteArray) -> Unit)? = null,
) {
    private val buf = ByteArray(Sealing.CHUNK)
    private var n = 0
    private var index = 0
    private var closed = false

    fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            if (n == buf.size) seal(last = false)
            val k = minOf(left, buf.size - n)
            b.copyInto(buf, n, o, o + k)
            n += k
            o += k
            left -= k
        }
    }

    private fun seal(last: Boolean) {
        if (closed) throw IOException("closed")
        val c = Sealing.sealChunk(crypto, key, prefix, headerHash, index, last, buf.copyOf(n))
        writeInt(out, c.size)
        out.write(c, 0, c.size)
        onChunk?.invoke(c)
        index++
        n = 0
    }

    fun flush() = out.flush()

    fun close() {
        if (closed) return
        seal(last = true)
        closed = true
        if (endMark) writeInt(out, 0)
        out.flush()
    }

    /** This, as an output stream. */
    val stream: OutputStream = outputStreamOf(::write, ::flush, ::close)
}

/**
 * Reads what [SealedOut] wrote, chunk by chunk, each checked before any of it is given out ([read], or [stream] for a zip
 * reader). A chunk that does not open, a file cut short or with anything after its last chunk, all end in an IOException.
 * Read it to the end ([drain]) to be sure the whole file was as it was made. With [endMark] (format 2) the chunks end at a
 * length of 0, not at the end of the bytes, and [input] is left just after it; [onChunk] is given each sealed chunk.
 */
internal class SealedIn(
    private val crypto: PlatformCrypto,
    private val input: InputStream,
    private val key: ByteArray,
    private val prefix: ByteArray,
    private val headerHash: ByteArray,
    private val endMark: Boolean = false,
    private val onChunk: ((ByteArray) -> Unit)? = null,
) {
    private var plain = ByteArray(0)
    private var pos = 0
    private var index = 0
    private var done = false
    private var nextLength: Int? = lengthOrEnd() ?: throw EOFException("no contents")

    private fun lengthOrEnd(): Int? {
        val a = readByte(input)
        if (a < 0) {
            if (endMark) throw EOFException("cut short")
            return null
        }
        val b = readByte(input)
        val c = readByte(input)
        val d = readByte(input)
        if ((b or c or d) < 0) throw EOFException("cut short")
        val n = (a shl 24) or (b shl 16) or (c shl 8) or d
        return if (endMark && n == 0) null else n
    }

    private fun fill(): Boolean {
        while (pos >= plain.size) {
            if (done) return false
            val len = nextLength ?: throw EOFException("cut short")
            if (len < Sealing.TAG || len > Sealing.CHUNK + Sealing.TAG) throw IOException("bad chunk")
            val sealed = ByteArray(len)
            readFully(input, sealed)
            onChunk?.invoke(sealed)
            nextLength = lengthOrEnd()
            val last = nextLength == null
            plain = try {
                Sealing.openChunk(crypto, key, prefix, headerHash, index, last, sealed)
            } catch (e: Exception) {
                throw IOException("a chunk does not open", e)
            } ?: throw IOException("a chunk does not open")
            pos = 0
            index++
            if (last) done = true
        }
        return true
    }

    /** Up to [len] bytes into [b] from [off]: how many, or -1 after the last chunk. */
    fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val k = minOf(len, plain.size - pos)
        plain.copyInto(b, off, pos, pos + k)
        pos += k
        return k
    }

    /** Read to the end, so every chunk has been checked. */
    fun drain() {
        val skip = ByteArray(8192)
        while (read(skip, 0, skip.size) >= 0) Unit
    }

    /** This, as an input stream. */
    val stream: InputStream = inputStreamOf(::read)
}
