package app.housemove

import app.housemove.data.AgreementKey
import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.P256Der
import app.housemove.data.PlatformCrypto
import app.housemove.data.UpdateKeyPair
import app.housemove.data.base64Decode
import app.housemove.data.base64Encode
import app.housemove.data.isFile
import app.housemove.data.mkdirs
import app.housemove.data.parentFile
import app.housemove.data.readText
import app.housemove.data.secureBytes
import app.housemove.data.writeText
import kotlinx.serialization.Serializable

/*
 * A reference P-256 and AES-256-GCM in plain Kotlin, for the tests only (it is in commonTest: no app, Android's or the
 * iPhone's, contains it). It lets the iPhone simulator's Kotlin tests, which cannot call the app's CryptoKit (Swift), run the
 * common sealing code against the golden files: ReferenceCryptoJvmTest checks it against the JCA on random keys and texts,
 * and the real CryptoKit path is checked in the app itself (CryptoCheck, run by the UI tests).
 *
 * Written for clarity, not speed or secrecy: nothing here runs in constant time.
 */

/** P-256 (FIPS 186-4 D.1.2.3; SEC 2): field arithmetic in Montgomery form with 32-bit limbs, points in Jacobian coordinates. */
internal object RefP256 {
    private const val N = 8
    private const val M = 0xFFFFFFFFuL

    /** A 256-bit number as 8 little-endian 32-bit limbs, from big-endian hex. */
    private fun limbs(hex: String): UIntArray {
        val h = hex.replace(" ", "").padStart(64, '0')
        return UIntArray(N) { i -> h.substring(64 - 8 * (i + 1), 64 - 8 * i).toUInt(16) }
    }

    val P = limbs("FFFFFFFF 00000001 00000000 00000000 00000000 FFFFFFFF FFFFFFFF FFFFFFFF")
    val ORDER = limbs("FFFFFFFF 00000000 FFFFFFFF FFFFFFFF BCE6FAAD A7179E84 F3B9CAC2 FC632551")
    private val B = limbs("5AC635D8 AA3A93E7 B3EBBD55 769886BC 651D06B0 CC53B0F6 3BCE3C3E 27D2604B")
    private val GX = limbs("6B17D1F2 E12C4247 F8BCE6E5 63A440F2 77037D81 2DEB33A0 F4A13945 D898C296")
    private val GY = limbs("4FE342E2 FE1A7F9B 8EE7EB4A 7C0F9E16 2BCE3357 6B315ECE CBB64068 37BF51F5")

    /** -P^-1 mod 2^32, by Newton's iteration. */
    private val P_INV: UInt = run {
        var x = 1u
        repeat(5) { x *= 2u - P[0] * x }
        0u - x
    }

    fun compare(a: UIntArray, b: UIntArray): Int {
        for (i in N - 1 downTo 0) if (a[i] != b[i]) return if (a[i] < b[i]) -1 else 1
        return 0
    }

    private fun isZero(a: UIntArray) = a.all { it == 0u }

    /** a - b over 8 limbs; the borrow out (0 or 1). */
    private fun subInto(a: UIntArray, b: UIntArray, out: UIntArray): UInt {
        var borrow = 0uL
        for (i in 0 until N) {
            val d = a[i].toULong() - b[i].toULong() - borrow
            out[i] = d.toUInt()
            borrow = (d shr 63) and 1uL
        }
        return borrow.toUInt()
    }

    private fun add(a: UIntArray, b: UIntArray): UIntArray {
        val out = UIntArray(N)
        var carry = 0uL
        for (i in 0 until N) {
            val s = a[i].toULong() + b[i].toULong() + carry
            out[i] = s.toUInt()
            carry = s shr 32
        }
        if (carry != 0uL || compare(out, P) >= 0) subInto(out, P, out)
        return out
    }

    private fun sub(a: UIntArray, b: UIntArray): UIntArray {
        val out = UIntArray(N)
        if (subInto(a, b, out) != 0u) {
            var carry = 0uL
            for (i in 0 until N) {
                val s = out[i].toULong() + P[i].toULong() + carry
                out[i] = s.toUInt()
                carry = s shr 32
            }
        }
        return out
    }

    /** Montgomery multiplication: a * b / 2^256 mod P (CIOS). */
    private fun mul(a: UIntArray, b: UIntArray): UIntArray {
        val t = ULongArray(N + 2)
        for (i in 0 until N) {
            var c = 0uL
            val bi = b[i].toULong()
            for (j in 0 until N) {
                val s = t[j] + a[j].toULong() * bi + c
                t[j] = s and M
                c = s shr 32
            }
            var s = t[N] + c
            t[N] = s and M
            t[N + 1] = s shr 32
            val m = (t[0].toUInt() * P_INV).toULong()
            s = t[0] + m * P[0].toULong()
            c = s shr 32
            for (j in 1 until N) {
                s = t[j] + m * P[j].toULong() + c
                t[j - 1] = s and M
                c = s shr 32
            }
            s = t[N] + c
            t[N - 1] = s and M
            t[N] = t[N + 1] + (s shr 32)
        }
        val out = UIntArray(N) { t[it].toUInt() }
        if (t[N] != 0uL || compare(out, P) >= 0) subInto(out, P, out)
        return out
    }

    private val ONE_PLAIN = UIntArray(N).also { it[0] = 1u }

    /** 2^512 mod P, to bring numbers into Montgomery form. */
    private val R2: UIntArray = run {
        var x = ONE_PLAIN
        repeat(512) { x = add(x, x) }
        x
    }

    private fun toMont(a: UIntArray) = mul(a, R2)
    private fun fromMont(a: UIntArray) = mul(a, ONE_PLAIN)
    private val ONE = toMont(ONE_PLAIN)
    private val BM = toMont(B)

    private fun inv(a: UIntArray): UIntArray {
        val e = UIntArray(N)
        subInto(P, UIntArray(N).also { it[0] = 2u }, e)
        var r = ONE
        for (i in 255 downTo 0) {
            r = mul(r, r)
            if ((e[i / 32] shr (i % 32)) and 1u == 1u) r = mul(r, a)
        }
        return r
    }

    /** A point in Jacobian coordinates (Montgomery form); Z = 0 is the point at infinity. */
    class Point(val x: UIntArray, val y: UIntArray, val z: UIntArray)

    private val INFINITY = Point(ONE, ONE, UIntArray(N))
    private val G = Point(toMont(GX), toMont(GY), ONE)

    private fun double(p: Point): Point {
        if (isZero(p.z)) return p
        val delta = mul(p.z, p.z)
        val gamma = mul(p.y, p.y)
        val beta = mul(p.x, gamma)
        val t = mul(sub(p.x, delta), add(p.x, delta))
        val alpha = add(add(t, t), t)
        val beta4 = add(add(beta, beta), add(beta, beta))
        val x3 = sub(mul(alpha, alpha), add(beta4, beta4))
        val yz = add(p.y, p.z)
        val z3 = sub(sub(mul(yz, yz), gamma), delta)
        val gamma2 = mul(gamma, gamma)
        val gamma8 = add(add(add(gamma2, gamma2), add(gamma2, gamma2)), add(add(gamma2, gamma2), add(gamma2, gamma2)))
        val y3 = sub(mul(alpha, sub(beta4, x3)), gamma8)
        return Point(x3, y3, z3)
    }

    private fun add(p: Point, q: Point): Point {
        if (isZero(p.z)) return q
        if (isZero(q.z)) return p
        val z1z1 = mul(p.z, p.z)
        val z2z2 = mul(q.z, q.z)
        val u1 = mul(p.x, z2z2)
        val u2 = mul(q.x, z1z1)
        val s1 = mul(mul(p.y, q.z), z2z2)
        val s2 = mul(mul(q.y, p.z), z1z1)
        val h = sub(u2, u1)
        val rr = sub(s2, s1)
        if (isZero(h)) return if (isZero(rr)) double(p) else INFINITY
        val h2 = add(h, h)
        val i = mul(h2, h2)
        val j = mul(h, i)
        val r = add(rr, rr)
        val v = mul(u1, i)
        val x3 = sub(sub(mul(r, r), j), add(v, v))
        val s1j = mul(s1, j)
        val y3 = sub(mul(r, sub(v, x3)), add(s1j, s1j))
        val zz = add(p.z, q.z)
        val z3 = mul(sub(sub(mul(zz, zz), z1z1), z2z2), h)
        return Point(x3, y3, z3)
    }

    /** [k] (32 bytes, big-endian) times [p]. */
    private fun times(k: ByteArray, p: Point): Point {
        var r = INFINITY
        for (byte in k) for (bit in 7 downTo 0) {
            r = double(r)
            if ((byte.toInt() shr bit) and 1 == 1) r = add(r, p)
        }
        return r
    }

    private fun fromBytes(b: ByteArray, at: Int): UIntArray = UIntArray(N) { i ->
        var v = 0u
        for (k in 0 until 4) v = (v shl 8) or (b[at + 28 - 4 * i + k].toUInt() and 0xFFu)
        v
    }

    private fun toBytes(a: UIntArray, out: ByteArray, at: Int) {
        for (i in 0 until N) for (k in 0 until 4) out[at + 28 - 4 * i + k] = (a[i] shr (24 - 8 * k)).toByte()
    }

    /** The affine point of [p] as an uncompressed point (65 bytes), or null for infinity. */
    private fun encode(p: Point): ByteArray? {
        if (isZero(p.z)) return null
        val zi = inv(p.z)
        val zi2 = mul(zi, zi)
        val out = ByteArray(65)
        out[0] = 4
        toBytes(fromMont(mul(p.x, zi2)), out, 1)
        toBytes(fromMont(mul(p.y, mul(zi2, zi))), out, 33)
        return out
    }

    /** The point in [x963] (04, x, y), checked to be on the curve, or null. */
    private fun decode(x963: ByteArray): Point? {
        if (x963.size != 65 || x963[0] != 4.toByte()) return null
        val x = fromBytes(x963, 1)
        val y = fromBytes(x963, 33)
        if (compare(x, P) >= 0 || compare(y, P) >= 0) return null
        val xm = toMont(x)
        val ym = toMont(y)
        val rhs = add(sub(mul(mul(xm, xm), xm), add(add(xm, xm), xm)), BM)
        if (compare(mul(ym, ym), rhs) != 0) return null
        return Point(xm, ym, ONE)
    }

    fun isPoint(x963: ByteArray): Boolean = decode(x963) != null

    /** Is [d] (32 bytes, big-endian) a private key: 0 < d < the group order? */
    fun isScalar(d: ByteArray): Boolean {
        if (d.size != 32) return false
        val v = fromBytes(d, 0)
        return !isZero(v) && compare(v, ORDER) < 0
    }

    /** The public point (65 bytes) of the private key [d]. */
    fun publicOf(d: ByteArray): ByteArray {
        require(isScalar(d)) { "not a P-256 private key" }
        return encode(times(d, G))!!
    }

    /** ECDH: the x-coordinate of [d] times the point [peerX963], or null when that is not a point on the curve. */
    fun agree(d: ByteArray, peerX963: ByteArray): ByteArray? {
        val q = decode(peerX963) ?: return null
        return encode(times(d, q))?.copyOfRange(1, 33)
    }

    /** A new private key. */
    fun newScalar(): ByteArray {
        while (true) {
            val d = secureBytes(32)
            if (isScalar(d)) return d
        }
    }
}

/** AES-256 (FIPS 197), encryption only: all GCM needs. */
internal class RefAes(key: ByteArray) {
    private val w: IntArray

    init {
        require(key.size == 32) { "AES-256 takes a 32-byte key" }
        w = IntArray(60)
        for (i in 0 until 8) w[i] = word(key, 4 * i)
        var rcon = 1
        for (i in 8 until 60) {
            var t = w[i - 1]
            if (i % 8 == 0) {
                t = subWord((t shl 8) or (t ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (i % 8 == 4) {
                t = subWord(t)
            }
            w[i] = w[i - 8] xor t
        }
    }

    fun encrypt(input: ByteArray): ByteArray {
        val s = input.copyOf(16)
        addRoundKey(s, 0)
        for (round in 1 until 14) {
            subBytes(s)
            shiftRows(s)
            mixColumns(s)
            addRoundKey(s, round)
        }
        subBytes(s)
        shiftRows(s)
        addRoundKey(s, 14)
        return s
    }

    private fun addRoundKey(s: ByteArray, round: Int) {
        for (c in 0 until 4) {
            val k = w[4 * round + c]
            for (r in 0 until 4) s[4 * c + r] = (s[4 * c + r].toInt() xor (k ushr (24 - 8 * r))).toByte()
        }
    }

    private fun subBytes(s: ByteArray) {
        for (i in s.indices) s[i] = SBOX[s[i].toInt() and 0xff].toByte()
    }

    private fun shiftRows(s: ByteArray) {
        val t = s.copyOf()
        for (r in 1 until 4) for (c in 0 until 4) s[4 * c + r] = t[4 * ((c + r) % 4) + r]
    }

    private fun mixColumns(s: ByteArray) {
        for (c in 0 until 4) {
            val a0 = s[4 * c].toInt() and 0xff
            val a1 = s[4 * c + 1].toInt() and 0xff
            val a2 = s[4 * c + 2].toInt() and 0xff
            val a3 = s[4 * c + 3].toInt() and 0xff
            s[4 * c] = (xtime(a0) xor xtime(a1) xor a1 xor a2 xor a3).toByte()
            s[4 * c + 1] = (a0 xor xtime(a1) xor xtime(a2) xor a2 xor a3).toByte()
            s[4 * c + 2] = (a0 xor a1 xor xtime(a2) xor xtime(a3) xor a3).toByte()
            s[4 * c + 3] = (xtime(a0) xor a0 xor a1 xor a2 xor xtime(a3)).toByte()
        }
    }

    private companion object {
        fun xtime(a: Int): Int = ((a shl 1) xor (if (a and 0x80 != 0) 0x1b else 0)) and 0xff

        /** The S-box, worked out (Wikipedia, "Rijndael S-box"): multiplicative inverse, then the affine map. */
        val SBOX: IntArray = IntArray(256).also { box ->
            fun rotl(x: Int, s: Int) = ((x shl s) or (x ushr (8 - s))) and 0xff
            var p = 1
            var q = 1
            do {
                p = (p xor (p shl 1) xor (if (p and 0x80 != 0) 0x1b else 0)) and 0xff
                q = q xor (q shl 1)
                q = q xor (q shl 2)
                q = q xor (q shl 4)
                q = q and 0xff
                if (q and 0x80 != 0) q = q xor 0x09
                box[p] = q xor rotl(q, 1) xor rotl(q, 2) xor rotl(q, 3) xor rotl(q, 4) xor 0x63
            } while (p != 1)
            box[0] = 0x63
        }

        fun word(b: ByteArray, at: Int) =
            ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

        fun subWord(x: Int): Int =
            (SBOX[x ushr 24] shl 24) or (SBOX[(x ushr 16) and 0xff] shl 16) or (SBOX[(x ushr 8) and 0xff] shl 8) or SBOX[x and 0xff]
    }
}

/** AES-256-GCM (NIST SP 800-38D) with a 12-byte nonce and a 16-byte tag. */
internal object RefGcm {
    private fun mulH(x: LongArray, h: LongArray) {
        var zh = 0L
        var zl = 0L
        var vh = h[0]
        var vl = h[1]
        for (i in 0 until 128) {
            val bit = if (i < 64) (x[0] ushr (63 - i)) and 1L else (x[1] ushr (127 - i)) and 1L
            if (bit == 1L) {
                zh = zh xor vh
                zl = zl xor vl
            }
            val lsb = vl and 1L
            vl = (vl ushr 1) or (vh shl 63)
            vh = vh ushr 1
            if (lsb == 1L) vh = vh xor (0xE1L shl 56)
        }
        x[0] = zh
        x[1] = zl
    }

    private fun long(b: ByteArray, at: Int): Long {
        var v = 0L
        for (k in 0 until 8) v = (v shl 8) or (b[at + k].toLong() and 0xff)
        return v
    }

    private fun ghash(h: LongArray, aad: ByteArray, c: ByteArray): ByteArray {
        val x = LongArray(2)
        for (data in listOf(aad, c)) {
            var i = 0
            while (i < data.size) {
                val block = data.copyOfRange(i, minOf(i + 16, data.size)).copyOf(16)
                x[0] = x[0] xor long(block, 0)
                x[1] = x[1] xor long(block, 8)
                mulH(x, h)
                i += 16
            }
        }
        x[0] = x[0] xor (aad.size.toLong() * 8)
        x[1] = x[1] xor (c.size.toLong() * 8)
        mulH(x, h)
        return ByteArray(16) { i -> (x[i / 8] ushr (56 - 8 * (i % 8))).toByte() }
    }

    private fun ctr(aes: RefAes, j0: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        val counter = j0.copyOf()
        var i = 0
        while (i < input.size) {
            var c = ((counter[12].toInt() and 0xff) shl 24) or ((counter[13].toInt() and 0xff) shl 16) or ((counter[14].toInt() and 0xff) shl 8) or (counter[15].toInt() and 0xff)
            c += 1
            counter[12] = (c ushr 24).toByte()
            counter[13] = (c ushr 16).toByte()
            counter[14] = (c ushr 8).toByte()
            counter[15] = c.toByte()
            val ks = aes.encrypt(counter)
            for (k in 0 until minOf(16, input.size - i)) out[i + k] = (input[i + k].toInt() xor ks[k].toInt()).toByte()
            i += 16
        }
        return out
    }

    private fun setup(key: ByteArray, nonce: ByteArray): Triple<RefAes, LongArray, ByteArray> {
        require(nonce.size == 12) { "a 12-byte nonce" }
        val aes = RefAes(key)
        val hb = aes.encrypt(ByteArray(16))
        val j0 = nonce.copyOf(16).also { it[15] = 1 }
        return Triple(aes, longArrayOf(long(hb, 0), long(hb, 8)), j0)
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val (aes, h, j0) = setup(key, nonce)
        val c = ctr(aes, j0, plain)
        val s = ghash(h, aad, c)
        val ek = aes.encrypt(j0)
        val tag = ByteArray(16) { (s[it].toInt() xor ek[it].toInt()).toByte() }
        return c + tag
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12 || sealed.size < 16) return null
        val (aes, h, j0) = setup(key, nonce)
        val c = sealed.copyOfRange(0, sealed.size - 16)
        val s = ghash(h, aad, c)
        val ek = aes.encrypt(j0)
        for (i in 0 until 16) if ((s[i].toInt() xor ek[i].toInt()).toByte() != sealed[sealed.size - 16 + i]) return null
        return ctr(aes, j0, c)
    }
}

/** A P-256 key pair of the reference code, from its private scalar [d]. */
internal class RefKey(val d: ByteArray) : AgreementKey {
    val point: ByteArray = RefP256.publicOf(d)
    override val publicDer: ByteArray = P256Der.of(point)

    override fun agree(peerDer: ByteArray): ByteArray? = P256Der.point(peerDer)?.let { RefP256.agree(d, it) }
}

/** The private key in PKCS#8 as the JCA writes a P-256 one: a fixed 36-byte start, the scalar, then the public point. */
internal object RefPkcs8 {
    private val START = base64Decode("MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg")
    private val MIDDLE = byteArrayOf(0xa1.toByte(), 0x44, 0x03, 0x42, 0x00)

    /** The scalar in [pkcs8], or null when it is not in this form. */
    fun scalar(pkcs8: ByteArray): ByteArray? {
        if (pkcs8.size != START.size + 32 + MIDDLE.size + 65) return null
        for (i in START.indices) if (pkcs8[i] != START[i]) return null
        return pkcs8.copyOfRange(START.size, START.size + 32)
    }

    fun of(key: RefKey): ByteArray = START + key.d + MIDDLE + key.point
}

/**
 * The reference code as the platform's crypto, for the tests on the iPhone simulator. A phone's key pair is kept as the JCA
 * keeps it on Android (`phone-key.json`: X.509 and PKCS#8 in Base64), so the golden phones' folders work as they are.
 */
internal object ReferenceCrypto : PlatformCrypto {
    @Serializable
    private data class Saved(val public: String = "", val private: String = "", val at: Long = 0)

    private class Keys(val key: RefKey, override val madeAt: Long) : UpdateKeyPair {
        override val publicText: String = base64Encode(key.publicDer)

        override fun agree(peerDer: ByteArray): ByteArray? = key.agree(peerDer)
    }

    override fun generateKey(): AgreementKey = RefKey(RefP256.newScalar())

    override fun isPublicKey(der: ByteArray): Boolean = P256Der.point(der)?.let { RefP256.isPoint(it) } == true

    override fun sealGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray = RefGcm.seal(key, nonce, aad, plain)

    override fun openGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = RefGcm.open(key, nonce, aad, sealed)

    override fun phoneKeys(phoneId: String, file: File?): UpdateKeyPair {
        try {
            if (file != null && file.isFile) {
                val s = AppJson.decodeFromString(Saved.serializer(), file.readText())
                val d = RefPkcs8.scalar(base64Decode(s.private))
                if (d != null) return Keys(RefKey(d), s.at)
            }
        } catch (_: Exception) {
        }
        val key = RefKey(RefP256.newScalar())
        val pair = Keys(key, app.housemove.data.currentTimeMillis())
        if (file != null) {
            file.parentFile?.mkdirs()
            file.writeText(AppJson.encodeToString(Saved.serializer(), Saved(pair.publicText, base64Encode(RefPkcs8.of(key)), pair.madeAt)))
        }
        return pair
    }

    /** The key pair of the private scalar [d]. */
    fun keyOf(d: ByteArray): RefKey = RefKey(d)
}
