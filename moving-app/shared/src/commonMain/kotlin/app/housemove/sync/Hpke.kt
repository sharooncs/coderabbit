package app.housemove.sync

import app.housemove.data.PlatformCrypto
import app.housemove.data.hmacSha256
import app.housemove.data.toUtf8

/*
 * HPKE, Hybrid Public Key Encryption (RFC 9180), as HouseMove seals messages from 2.5 (design/encryption.md, "The mailbox"
 * and "Mailbox as built"): the mailbox's envelopes and update files of format 2. One suite only:
 *
 * - KEM: DHKEM(P-256, HKDF-SHA256), kem_id 0x0010: the phones' own P-256 keys, ECDH through the platform (the JCA on
 *   Android and the JVM, CryptoKit on the iPhone), public keys as 65-byte uncompressed points (enc is one).
 * - KDF: HKDF-SHA256, kdf_id 0x0001, over HMAC-SHA256 (Hmac.kt, common, on the platform's SHA-256).
 * - AEAD: AES-256-GCM, aead_id 0x0002 (PlatformCrypto.sealGcm and openGcm).
 * - Modes: mode_auth (2), which the app uses: the sender's static key goes into the key agreement, so a message that opens
 *   came from the holder of that key (the recipient knows it from its pin). mode_base (0) only to check the key schedule
 *   against the specification's test vectors as well.
 *
 * Nothing here is a cipher or a key agreement of its own: every encryption is the platform's AES-GCM and every key
 * agreement its ECDH; the labels, the key schedule and the nonces are the RFC's, written once in common Kotlin, so Android
 * and the iPhone seal the same bytes. HpkeCommonTest checks it against the CFRG's test vectors for exactly this suite
 * (src/jvmTest/resources/hpke/vectors.json) on the JVM and on the iPhone simulator; the iPhone app's crypto check runs them
 * through CryptoKit, and against CryptoKit's own HPKE; tools/hpke_check.py checks the vectors and HouseMove's sealed
 * messages with Python's `cryptography` and an HPKE written apart from this one.
 */
internal object Hpke {
    const val MODE_BASE = 0
    const val MODE_AUTH = 2

    const val KEM_ID = 0x0010
    const val KDF_ID = 0x0001
    const val AEAD_ID = 0x0002

    /** Nenc and Npk: an uncompressed P-256 point. */
    const val ENC = 65

    private const val N_SECRET = 32
    private const val N_K = 32
    private const val N_N = 12
    private const val N_H = 32
    const val TAG = 16

    private val VERSION = "HPKE-v1".toUtf8()

    /** "KEM" || I2OSP(kem_id, 2). */
    private val KEM_SUITE = "KEM".toUtf8() + i2osp(KEM_ID, 2)

    /** "HPKE" || I2OSP(kem_id, 2) || I2OSP(kdf_id, 2) || I2OSP(aead_id, 2). */
    private val HPKE_SUITE = "HPKE".toUtf8() + i2osp(KEM_ID, 2) + i2osp(KDF_ID, 2) + i2osp(AEAD_ID, 2)

    private fun i2osp(n: Int, w: Int): ByteArray = ByteArray(w) { (n ushr (8 * (w - 1 - it))).toByte() }

    /** HKDF-Extract: HMAC(salt, ikm). An empty salt is the hash's length of zeros, which HMAC pads it to anyway. */
    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray = hmacSha256(salt, ikm)

    /** HKDF-Expand to [length] bytes (at most 255 blocks). */
    private fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * N_H)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var at = 0
        var i = 1
        while (at < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - at)
            t.copyInto(out, at, 0, n)
            at += n
            i++
        }
        return out
    }

    private fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        extract(salt, VERSION + suite + label.toUtf8() + ikm)

    private fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray =
        expand(prk, i2osp(length, 2) + VERSION + suite + label.toUtf8() + info, length)

    /** DHKEM's ExtractAndExpand: the KEM's shared secret from the key agreements [dh] and [kemContext]. */
    private fun extractAndExpand(dh: ByteArray, kemContext: ByteArray): ByteArray {
        val prk = labeledExtract(KEM_SUITE, ByteArray(0), "eae_prk", dh)
        return labeledExpand(KEM_SUITE, prk, "shared_secret", kemContext, N_SECRET)
    }

    /** What the key schedule makes of a shared secret, for the tests' known answers. */
    class Schedule(val sharedSecret: ByteArray, val keyScheduleContext: ByteArray, val secret: ByteArray, val context: Context)

    /** The key schedule of [mode] (base or auth: no pre-shared key) for [sharedSecret] and [info]. */
    private fun keySchedule(crypto: PlatformCrypto, mode: Int, sharedSecret: ByteArray, info: ByteArray): Schedule {
        val pskIdHash = labeledExtract(HPKE_SUITE, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(HPKE_SUITE, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(mode.toByte()) + pskIdHash + infoHash
        val secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", ByteArray(0))
        val key = labeledExpand(HPKE_SUITE, secret, "key", context, N_K)
        val baseNonce = labeledExpand(HPKE_SUITE, secret, "base_nonce", context, N_N)
        val exporter = labeledExpand(HPKE_SUITE, secret, "exp", context, N_H)
        return Schedule(sharedSecret, context, secret, Context(crypto, key, baseNonce, exporter))
    }

    /**
     * An HPKE encryption context: AES-256-GCM under [key], the nonce of message number [seq] being [baseNonce] XOR seq. Each
     * [seal] (or [open] that opens) moves on to the next number, so the messages of one context have to be opened in the
     * order they were sealed.
     */
    class Context internal constructor(
        private val crypto: PlatformCrypto,
        internal val key: ByteArray,
        internal val baseNonce: ByteArray,
        internal val exporterSecret: ByteArray,
    ) {
        /** The number of the next message. Tests set it to check a later message's nonce. */
        internal var seq = 0L

        internal fun nonce(n: Long): ByteArray {
            val out = baseNonce.copyOf()
            for (i in 0 until 8) out[N_N - 1 - i] = (out[N_N - 1 - i].toInt() xor (n ushr (8 * i)).toInt()).toByte()
            return out
        }

        fun seal(aad: ByteArray, plain: ByteArray): ByteArray {
            check(seq >= 0 && seq < Long.MAX_VALUE) { "message numbers used up" }
            val c = crypto.sealGcm(key, nonce(seq), aad, plain)
            seq++
            return c
        }

        /** The plain text of [sealed], or null when it does not open (and then the number stays where it is). */
        fun open(aad: ByteArray, sealed: ByteArray): ByteArray? {
            if (sealed.size < TAG || seq < 0 || seq == Long.MAX_VALUE) return null
            val p = crypto.openGcm(key, nonce(seq), aad, sealed) ?: return null
            seq++
            return p
        }

        /** HPKE's secret export: [length] bytes for [exporterContext]. */
        fun export(exporterContext: ByteArray, length: Int): ByteArray =
            labeledExpand(HPKE_SUITE, exporterSecret, "sec", exporterContext, length)
    }

    /** A sender's side: the encapsulated key [enc] that goes with the messages, and the context that seals them. */
    class Sender(val enc: ByteArray, val context: Context, val schedule: Schedule)

    /**
     * SetupAuthS: a context sealing to the recipient whose public key is the point [pkR], authenticated with the sender's
     * static key [skS], bound to [info]. [ephemeral] makes the one-use key (the platform's, or a fixed one in a known-answer
     * test). Null when [pkR] is not a point of P-256.
     */
    fun setupAuthS(crypto: PlatformCrypto, pkR: ByteArray, info: ByteArray, skS: NoiseKey, ephemeral: () -> NoiseKey = { Noise.newEphemeral(crypto) }): Sender? {
        if (!isPointEncoding(pkR)) return null
        val e = ephemeral()
        val dh1 = e.dh(pkR) ?: return null
        val dh2 = skS.dh(pkR) ?: return null
        val enc = e.point
        val shared = extractAndExpand(dh1 + dh2, enc + pkR + skS.point)
        val schedule = keySchedule(crypto, MODE_AUTH, shared, info)
        return Sender(enc.copyOf(), schedule.context, schedule)
    }

    /**
     * SetupAuthR: the context opening what the holder of the static key [pkS] sealed to [skR] with [enc] and [info]. Null
     * when [enc] or [pkS] is not a point of P-256. Whether it was really [pkS]'s holder is known only once a message opens.
     */
    fun setupAuthR(crypto: PlatformCrypto, enc: ByteArray, skR: NoiseKey, info: ByteArray, pkS: ByteArray): Context? = scheduleAuthR(crypto, enc, skR, info, pkS)?.context

    /** [setupAuthR], with the key schedule's values for the tests. */
    internal fun scheduleAuthR(crypto: PlatformCrypto, enc: ByteArray, skR: NoiseKey, info: ByteArray, pkS: ByteArray): Schedule? {
        if (!isPointEncoding(enc) || !isPointEncoding(pkS)) return null
        val dh1 = skR.dh(enc) ?: return null
        val dh2 = skR.dh(pkS) ?: return null
        val shared = extractAndExpand(dh1 + dh2, enc + skR.point + pkS)
        return keySchedule(crypto, MODE_AUTH, shared, info)
    }

    /** SetupBaseS: mode_base, for the test vectors only (HouseMove's messages are always mode_auth). */
    internal fun setupBaseS(crypto: PlatformCrypto, pkR: ByteArray, info: ByteArray, ephemeral: () -> NoiseKey): Sender? {
        if (!isPointEncoding(pkR)) return null
        val e = ephemeral()
        val dh = e.dh(pkR) ?: return null
        val schedule = keySchedule(crypto, MODE_BASE, extractAndExpand(dh, e.point + pkR), info)
        return Sender(e.point.copyOf(), schedule.context, schedule)
    }

    /** SetupBaseR: mode_base, for the test vectors only. */
    internal fun scheduleBaseR(crypto: PlatformCrypto, enc: ByteArray, skR: NoiseKey, info: ByteArray): Schedule? {
        if (!isPointEncoding(enc)) return null
        val dh = skR.dh(enc) ?: return null
        return keySchedule(crypto, MODE_BASE, extractAndExpand(dh, enc + skR.point), info)
    }
}
