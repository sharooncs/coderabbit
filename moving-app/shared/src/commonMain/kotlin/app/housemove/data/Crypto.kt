package app.housemove.data

/*
 * What the update files ask of the platform's cryptography (design/encryption.md): P-256 key pairs whose private half the
 * platform keeps, ECDH, and AES-256-GCM with a nonce and additional data the caller chooses. Everything else (the file
 * format, the chunks and their nonces, HKDF, HMAC, SHA-256, Base64) is common Kotlin in Sealed.kt and Update.kt, so every
 * phone works out the same bytes.
 *
 * On Android and the JVM the platform is the JCA the update files have always used (Crypto.jvm.kt): Android writes and
 * reads what it did. On the iPhone it is CryptoKit, in Swift (iosApp's CryptoKitCrypto.swift), registered when the app
 * starts (Crypto.ios.kt), with the phone's private key in the Keychain.
 */

/** A P-256 key pair whose private half the platform holds: the shared code sees the public half and agrees keys with it. */
interface AgreementKey {
    /** The public key as Android sends it: X.509 SubjectPublicKeyInfo, DER (91 bytes for an uncompressed point). */
    val publicDer: ByteArray

    /** ECDH with the public key [peerDer] (X.509, DER): the shared secret, the point's x-coordinate (32 bytes), or null when [peerDer] is not a P-256 public key. */
    fun agree(peerDer: ByteArray): ByteArray?
}

/** A phone's key pair for update files, as the shared code sees it: the public half as it travels, when it was made, and agreeing keys with it. */
interface UpdateKeyPair {
    /** The public key as it travels: X.509, Base64 on one line. Empty when the phone has none. */
    val publicText: String
    val madeAt: Long

    /** ECDH with this phone's private key ([AgreementKey.agree]); null when the phone has no key or [peerDer] is not a P-256 public key. */
    fun agree(peerDer: ByteArray): ByteArray?
}

/** The key pair of a phone without update-file crypto: none. */
internal object NoUpdateKeys : UpdateKeyPair {
    override val publicText: String = ""
    override val madeAt: Long = 0

    override fun agree(peerDer: ByteArray): ByteArray? = null
}

/** The platform's cryptography, as the update files use it. */
interface PlatformCrypto {
    /** A new P-256 key pair, kept in memory only: the one-use (ephemeral) key an update wraps its content key with. */
    fun generateKey(): AgreementKey

    /** Is [der] a public key this platform can agree a key with (X.509, DER)? */
    fun isPublicKey(der: ByteArray): Boolean

    /** AES-256-GCM with a 128-bit tag: [plain] sealed under [key] (32 bytes) with [nonce] (12 bytes) and [aad]; the ciphertext, then the tag. */
    fun sealGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray

    /** What [sealGcm] sealed, or null when it does not open (another key, nonce or [aad], or bytes changed). */
    fun openGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?

    /**
     * This phone's key pair for update files, kept by the platform: made the first time it is asked for, then the same one.
     * [file] is where Android keeps it beside the phone ID (no backup); on the iPhone the private key is in the Keychain and
     * [file] holds only the public key and when it was made. With a null [file], a pair kept in memory only.
     */
    fun phoneKeys(phoneId: String, file: File?): UpdateKeyPair
}

/** The JCA on Android and the JVM; on the iPhone, what the app registered at start-up (null until then). */
internal expect val platformCrypto: PlatformCrypto?

/** Is [text] a public key as it travels ([UpdateKeyPair.publicText]) that this platform can use? False with no crypto. */
internal fun isPublicKeyText(text: String): Boolean {
    val crypto = platformCrypto ?: return false
    if (text.isBlank()) return false
    val der = try {
        base64Decode(text)
    } catch (_: Exception) {
        return false
    }
    return crypto.isPublicKey(der)
}

/**
 * A P-256 public key's X.509 form and its bare point. Android (JCA) writes every P-256 public key as this 26-byte prefix
 * (SEQUENCE, the id-ecPublicKey and prime256v1 object identifiers, a BIT STRING of 66 bytes) and the 65-byte uncompressed
 * point (04, x, y); CryptoKit takes and gives the bare point (`x963Representation`).
 */
internal object P256Der {
    private val PREFIX = byteArrayOf(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x02, 0x01,
        0x06, 0x08, 0x2a, 0x86.toByte(), 0x48, 0xce.toByte(), 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
    )
    const val POINT = 65

    /** The X.509 form of the uncompressed point [x963]. */
    fun of(x963: ByteArray): ByteArray {
        require(x963.size == POINT && x963[0] == 0x04.toByte()) { "not an uncompressed P-256 point" }
        return PREFIX + x963
    }

    /** The uncompressed point in [der], or null when [der] is not a P-256 public key in the form Android writes. */
    fun point(der: ByteArray): ByteArray? {
        if (der.size != PREFIX.size + POINT || der[PREFIX.size] != 0x04.toByte()) return null
        for (i in PREFIX.indices) if (der[i] != PREFIX[i]) return null
        return der.copyOfRange(PREFIX.size, der.size)
    }
}

/** Base64 as java.util.Base64's basic encoder writes it (padded, no line breaks). */
internal expect fun base64Encode(bytes: ByteArray): String

/** Base64 as java.util.Base64's basic decoder reads it; IllegalArgumentException when it is not. */
internal expect fun base64Decode(text: String): ByteArray

/** [n] bytes from the platform's strong random source (SecureRandom.nextBytes on Android and the JVM, SecRandomCopyBytes on the iPhone). */
internal expect fun secureBytes(n: Int): ByteArray
