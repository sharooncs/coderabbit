package app.housemove.sync

import app.housemove.data.AgreementKey
import app.housemove.data.P256Der
import app.housemove.data.PlatformCrypto
import app.housemove.data.UpdateKeyPair
import app.housemove.data.base64Decode
import app.housemove.data.hmacSha256
import app.housemove.data.platformCrypto
import app.housemove.data.sha256
import app.housemove.data.strongRandomBytes
import app.housemove.data.toUtf8
import kotlin.concurrent.Volatile

/*
 * The Noise Protocol Framework (revision 34), as encrypted sync uses it from 2.4 (design/encryption.md, Part 2): the
 * handshake patterns IK (syncing by itself: the dialling phone knows the answering phone's key from its pin) and
 * XXpsk0 (code syncs and invite joins between 2.4 phones: the pre-shared key comes from the code in the QR), with these
 * choices, since P-256 is not one of the specification's named curves:
 *
 * - DH: P-256 ECDH, the 32-byte x-coordinate (DHLEN = 32), through the platform's AgreementKey (the JCA on Android and the
 *   JVM, CryptoKit on the iPhone). Public keys travel as 65-byte uncompressed points (04, x, y), so an encrypted static
 *   key is 65 + 16 bytes. A point that is not on the curve fails the handshake (both platforms check it).
 * - Cipher: AES-256-GCM (PlatformCrypto.sealGcm), with Noise's AESGCM nonce: four zero bytes and the 64-bit counter,
 *   big-endian. REKEY is Noise's: the first 32 bytes of ENCRYPT(k, 2^64-1, empty, 32 zero bytes).
 * - Hash: SHA-256 (the platform's digest), with Noise's HKDF over HMAC-SHA256 (Hmac.kt, common).
 * - Protocol names: Noise_IK_P256_AESGCM_SHA256 and Noise_XXpsk0_P256_AESGCM_SHA256 (both at most 32 bytes, so h starts as
 *   the name padded with zeros).
 *
 * Nothing here is a cipher of its own: every encryption is the platform's AES-GCM and every key agreement its ECDH. The
 * state machine is the specification's, written once in common Kotlin, so Android and the iPhone send the same bytes;
 * NoiseCommonTest checks it against transcripts the JVM made (noise/handshakes.json) on the JVM and on the simulator, and
 * the iPhone app's crypto check against the same transcripts through CryptoKit.
 */

/** A key pair as a handshake uses it: its public point (65 bytes, uncompressed) and ECDH with its private half. */
internal interface NoiseKey {
    val point: ByteArray

    /** ECDH with the point [peer]: the 32-byte x-coordinate, or null when [peer] is not a point of P-256. */
    fun dh(peer: ByteArray): ByteArray?
}

/** Is [p] an uncompressed point's encoding (65 bytes, starting 04)? Whether it is on the curve, ECDH says. */
internal fun isPointEncoding(p: ByteArray): Boolean = p.size == P256Der.POINT && p[0] == 0x04.toByte()

/** A platform key pair (a one-use key, or a fixed one in tests) as a handshake key. Null when its public key is not a P-256 point. */
internal fun AgreementKey.noiseKey(): NoiseKey? {
    val pt = P256Der.point(publicDer) ?: return null
    val key = this
    return object : NoiseKey {
        override val point: ByteArray = pt
        override fun dh(peer: ByteArray): ByteArray? = if (isPointEncoding(peer)) key.agree(P256Der.of(peer))?.takeIf { it.size == Noise.DH_LEN } else null
    }
}

/** A phone's key pair ([app.housemove.data.Phone.keys]) as its static key in a handshake; null when the phone has none. */
internal fun UpdateKeyPair.noiseKey(): NoiseKey? {
    val text = publicText
    if (text.isBlank()) return null
    val pt = try {
        P256Der.point(base64Decode(text))
    } catch (_: Exception) {
        null
    } ?: return null
    val keys = this
    return object : NoiseKey {
        override val point: ByteArray = pt
        override fun dh(peer: ByteArray): ByteArray? = if (isPointEncoding(peer)) keys.agree(P256Der.of(peer))?.takeIf { it.size == Noise.DH_LEN } else null
    }
}

/** The point of a public key as phone entries and pins carry it (X.509, Base64), or null when it is not one. */
internal fun pointOfKeyText(text: String): ByteArray? = try {
    if (text.isBlank()) null else P256Der.point(base64Decode(text))
} catch (_: Exception) {
    null
}

/** Where one-use keys and random bytes come from. Tests that must repeat a run exactly put in seeded ones; the app never does. */
internal object Strong {
    @Volatile
    var bytes: (Int) -> ByteArray = ::strongRandomBytes

    /** A one-use key pair for a handshake; null: the platform's ([PlatformCrypto.generateKey]). */
    @Volatile
    var ephemeral: (() -> AgreementKey)? = null
}

internal object Noise {
    const val IK = "IK"
    const val XX_PSK0 = "XXpsk0"
    const val DH_LEN = 32
    const val HASH_LEN = 32
    const val TAG = 16

    /** The longest Noise message, and the longest frame of the encrypted channel ([SecureChannel]). */
    const val MAX_MESSAGE = 65_535

    fun protocolName(pattern: String): String = "Noise_${pattern}_P256_AESGCM_SHA256"

    /**
     * Noise's HKDF: [n] (2 or 3) outputs of 32 bytes from the chaining key [ck] and [ikm]. temp = HMAC(ck, ikm);
     * out1 = HMAC(temp, 0x01); out2 = HMAC(temp, out1 | 0x02); out3 = HMAC(temp, out2 | 0x03).
     */
    fun hkdf(ck: ByteArray, ikm: ByteArray, n: Int): List<ByteArray> {
        val temp = hmacSha256(ck, ikm)
        val out = ArrayList<ByteArray>(n)
        var last = ByteArray(0)
        for (i in 1..n) {
            last = hmacSha256(temp, last + byteArrayOf(i.toByte()))
            out += last
        }
        return out
    }

    /** AESGCM's nonce for the counter [n]: four zero bytes, then n as 64 bits, big-endian (n = -1 is 2^64 - 1). */
    fun nonce(n: Long): ByteArray = ByteArray(12).also { b -> for (i in 0 until 8) b[4 + i] = (n ushr (56 - 8 * i)).toByte() }

    /**
     * The keys of each encrypted session, as they are made, for the tests' relays to read what crossed (as TLS's
     * SSLKEYLOGFILE): the initiator's one-use public key, then the key of each direction (initiator to responder first).
     * Never set by the app; null unless a test sets it.
     */
    @Volatile
    internal var keyLog: ((ByteArray, ByteArray, ByteArray) -> Unit)? = null

    /** A new one-use key pair for a handshake, from [crypto] (or the tests' seeded source). */
    fun newEphemeral(crypto: PlatformCrypto): NoiseKey =
        ((Strong.ephemeral?.invoke() ?: crypto.generateKey()).noiseKey()) ?: throw IllegalStateException("the platform made a key that is not P-256")
}

/** Noise's CipherState: a key (or none) and the counter its nonces come from. */
internal class CipherState(private val crypto: PlatformCrypto) {
    private var k: ByteArray? = null

    /** The next nonce's counter. */
    var n = 0L
        private set

    val hasKey: Boolean get() = k != null

    fun initializeKey(key: ByteArray?) {
        k = key?.copyOf(32)
        n = 0
    }

    /** ENCRYPT with the next nonce (and count it); [plain] as it is while there is no key. */
    fun encryptWithAd(ad: ByteArray, plain: ByteArray): ByteArray {
        val key = k ?: return plain
        // 2^64 - 1 is reserved for rekeying: a channel stops long before ([SecureChannel.MAX_FRAMES]).
        check(n != -1L) { "nonces used up" }
        val c = crypto.sealGcm(key, Noise.nonce(n), ad, plain)
        n++
        return c
    }

    /** DECRYPT with the next nonce, counted only when it opens; null when it does not. [ciphertext] as it is while there is no key. */
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray? {
        val key = k ?: return ciphertext
        if (ciphertext.size < Noise.TAG || n == -1L) return null
        val p = crypto.openGcm(key, Noise.nonce(n), ad, ciphertext) ?: return null
        n++
        return p
    }

    /** Noise's Rekey(): k = the first 32 bytes of ENCRYPT(k, 2^64 - 1, empty, 32 zeros). The counter goes on. */
    fun rekey() {
        val key = k ?: return
        k = crypto.sealGcm(key, Noise.nonce(-1L), ByteArray(0), ByteArray(32)).copyOf(32)
    }

    /** The key, for [Noise.keyLog] only. */
    internal fun keyForLog(): ByteArray = k?.copyOf() ?: ByteArray(0)
}

/** Noise's SymmetricState. */
internal class SymmetricState(private val crypto: PlatformCrypto, protocolName: String) {
    var ck: ByteArray
        private set
    var h: ByteArray
        private set
    val cipher = CipherState(crypto)

    init {
        val name = protocolName.toUtf8()
        h = if (name.size <= Noise.HASH_LEN) name.copyOf(Noise.HASH_LEN) else sha256(name)
        ck = h.copyOf()
    }

    fun mixKey(ikm: ByteArray) {
        val (a, b) = Noise.hkdf(ck, ikm, 2)
        ck = a
        cipher.initializeKey(b)
    }

    fun mixHash(data: ByteArray) {
        h = sha256(h + data)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val (a, b, c) = Noise.hkdf(ck, ikm, 3)
        ck = a
        mixHash(b)
        cipher.initializeKey(c)
    }

    fun encryptAndHash(plain: ByteArray): ByteArray = cipher.encryptWithAd(h, plain).also { mixHash(it) }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray? = cipher.decryptWithAd(h, ciphertext)?.also { mixHash(ciphertext) }

    /** The two directions' cipher states: initiator to responder, then responder to initiator. */
    fun split(): Pair<CipherState, CipherState> {
        val (a, b) = Noise.hkdf(ck, ByteArray(0), 2)
        return CipherState(crypto).apply { initializeKey(a) } to CipherState(crypto).apply { initializeKey(b) }
    }
}

/**
 * Noise's HandshakeState for the two patterns HouseMove uses:
 *
 * ```
 * IK:                 XXpsk0:
 *   <- s
 *   ...
 *   -> e, es, s, ss     -> psk, e
 *   <- e, ee, se        <- e, ee, s, es
 *                       -> s, se
 * ```
 *
 * [s] is this phone's static key; [rs] the responder's, known beforehand (IK, the initiator); [psk] the pre-shared key
 * (XXpsk0, 32 bytes). [ephemeral] makes this side's one-use key. [write] and [read] take turns as the pattern says; once
 * a message has failed, or the last one has gone, nothing more is written or read.
 */
internal class Handshake(
    private val crypto: PlatformCrypto,
    val pattern: String,
    val initiator: Boolean,
    prologue: ByteArray,
    private val s: NoiseKey,
    rs: ByteArray? = null,
    private val psk: ByteArray? = null,
    private val ephemeral: () -> NoiseKey = { Noise.newEphemeral(crypto) },
) {
    private enum class T { E, S, EE, ES, SE, SS, PSK }

    private val messages: List<List<T>> = when (pattern) {
        Noise.IK -> listOf(listOf(T.E, T.ES, T.S, T.SS), listOf(T.E, T.EE, T.SE))
        Noise.XX_PSK0 -> listOf(listOf(T.PSK, T.E), listOf(T.E, T.EE, T.S, T.ES), listOf(T.S, T.SE))
        else -> throw IllegalArgumentException("not a pattern HouseMove uses: $pattern")
    }
    private val pskMode = pattern.contains("psk")
    private val ss = SymmetricState(crypto, Noise.protocolName(pattern))
    private var e: NoiseKey? = null
    private var re: ByteArray? = null

    /** The other side's static key (a point), once known: given (IK's initiator) or read. */
    var remoteStatic: ByteArray? = rs?.copyOf()
        private set

    /** The initiator's one-use public key, once it has been written or read. */
    var initiatorEphemeral: ByteArray? = null
        private set

    private var index = 0
    private var failed = false

    /** Every message has gone: [split] gives the channel's cipher states. */
    val done: Boolean get() = !failed && index == messages.size

    /** The handshake hash h, which both sides share once it is done. */
    val hash: ByteArray get() = ss.h.copyOf()

    init {
        require(pskMode == (psk != null)) { "a pre-shared key goes with XXpsk0, and with it only" }
        require(psk == null || psk.size == 32) { "a pre-shared key is 32 bytes" }
        ss.mixHash(prologue)
        if (pattern == Noise.IK) {
            // The pre-message "<- s": the responder's static key, which the initiator knows from its pin.
            val known = if (initiator) remoteStatic ?: throw IllegalArgumentException("IK's initiator knows the responder's key") else s.point
            require(isPointEncoding(known)) { "not an uncompressed point" }
            ss.mixHash(known)
        }
    }

    private fun myTurn(): Boolean = (index % 2 == 0) == initiator

    private fun dh(token: T): ByteArray? {
        val mine = e
        val theirsE = re
        val theirsS = remoteStatic
        return when (token) {
            T.EE -> if (mine != null && theirsE != null) mine.dh(theirsE) else null
            T.SS -> theirsS?.let { s.dh(it) }
            // "es": the initiator's e with the responder's s; "se": the initiator's s with the responder's e.
            T.ES -> if (initiator) theirsS?.let { mine?.dh(it) } else theirsE?.let { s.dh(it) }
            T.SE -> if (initiator) theirsE?.let { s.dh(it) } else theirsS?.let { mine?.dh(it) }
            else -> null
        }
    }

    /** The next message, carrying [payload]. */
    fun write(payload: ByteArray = ByteArray(0)): ByteArray {
        check(!failed && index < messages.size && myTurn()) { "not this side's turn" }
        var out = ByteArray(0)
        try {
            for (t in messages[index]) when (t) {
                T.E -> {
                    val k = ephemeral()
                    e = k
                    out += k.point
                    ss.mixHash(k.point)
                    if (pskMode) ss.mixKey(k.point)
                    if (initiator) initiatorEphemeral = k.point.copyOf()
                }
                T.S -> out += ss.encryptAndHash(s.point)
                T.PSK -> ss.mixKeyAndHash(psk!!)
                else -> ss.mixKey(dh(t) ?: throw IllegalStateException("key agreement failed"))
            }
            out += ss.encryptAndHash(payload)
        } catch (x: Exception) {
            failed = true
            throw x
        }
        check(out.size <= Noise.MAX_MESSAGE) { "message too long" }
        index++
        return out
    }

    /** The payload of the other side's next [message], or null when it is not one (then the handshake has failed). */
    fun read(message: ByteArray): ByteArray? {
        if (failed || index >= messages.size || myTurn() || message.size > Noise.MAX_MESSAGE) {
            failed = true
            return null
        }
        var at = 0
        fun take(n: Int): ByteArray? = if (at + n > message.size) null else message.copyOfRange(at, at + n).also { at += n }
        val payload = run {
            for (t in messages[index]) when (t) {
                T.E -> {
                    val p = take(P256Der.POINT)?.takeIf { isPointEncoding(it) } ?: return@run null
                    re = p
                    ss.mixHash(p)
                    if (pskMode) ss.mixKey(p)
                    if (!initiator) initiatorEphemeral = p.copyOf()
                }
                T.S -> {
                    val c = take(P256Der.POINT + if (ss.cipher.hasKey) Noise.TAG else 0) ?: return@run null
                    remoteStatic = ss.decryptAndHash(c)?.takeIf { isPointEncoding(it) } ?: return@run null
                }
                T.PSK -> ss.mixKeyAndHash(psk!!)
                else -> ss.mixKey(dh(t) ?: return@run null)
            }
            ss.decryptAndHash(message.copyOfRange(at, message.size))
        }
        if (payload == null) {
            failed = true
            return null
        }
        index++
        return payload
    }

    /** This side's two cipher states once the handshake is done: (sending, receiving). */
    fun split(): Pair<CipherState, CipherState> {
        check(done) { "the handshake is not done" }
        val (i2r, r2i) = ss.split()
        Noise.keyLog?.let { log -> initiatorEphemeral?.let { log(it, i2r.keyForLog(), r2i.keyForLog()) } }
        return if (initiator) i2r to r2i else r2i to i2r
    }
}

/** The platform's crypto, which encrypted sync needs; null on an iPhone before the app registered CryptoKit. */
internal fun noiseCrypto(): PlatformCrypto? = platformCrypto
