package app.housemove.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Android and the JVM: the update files' crypto is the JCA the app has used since 2.1 (Crypto.kt says what the shared code
// asks of it). The calls are the ones Sealed.kt and Keys.kt made: KeyPairGenerator("EC", secp256r1), KeyFactory("EC") for
// X.509 and PKCS#8, KeyAgreement("ECDH") and Cipher("AES/GCM/NoPadding") with a 128-bit tag. So Android writes and reads
// what it did, byte for byte (golden/crypto.json and golden/update.json).

private const val CURVE = "secp256r1"

/** The random source of the update files' one-use keys and random bytes, as Sealing had it: one SecureRandom. */
private val sealingRandom = SecureRandom()

internal actual fun secureBytes(n: Int): ByteArray = ByteArray(n).also { sealingRandom.nextBytes(it) }

internal actual fun base64Encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

internal actual fun base64Decode(text: String): ByteArray = Base64.getDecoder().decode(text)

/** A JCA P-256 key pair as the shared code sees it. */
internal class JcaAgreementKey(val public: PublicKey, internal val private: PrivateKey) : AgreementKey {
    override val publicDer: ByteArray get() = public.encoded

    override fun agree(peerDer: ByteArray): ByteArray? = try {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerDer))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(private)
        ka.doPhase(peer, true)
        ka.generateSecret()
    } catch (_: Exception) {
        null
    }

    companion object {
        fun of(pair: KeyPair) = JcaAgreementKey(pair.public, pair.private)
    }
}

/**
 * This installed copy's key pair for update files (see Update.kt): an EC P-256 pair made once and kept
 * beside the phone ID, outside Android's backup, so a phone restored or cloned from this one makes its own.
 * Plain JCA, so it works the same on the JVM as on a phone. The private key never leaves this phone; the
 * public one travels with this phone's entry in the move ([PhoneEntry.key]), so after any sync every phone
 * knows the keys of the move's Organiser and Decider phones and can send them an update.
 */
class PhoneKeys internal constructor(val public: PublicKey, internal val private: PrivateKey, override val madeAt: Long) : UpdateKeyPair {
    /** The public key as it travels: X.509, Base64 on one line. */
    override val publicText: String = Base64.getEncoder().encodeToString(public.encoded)

    private val key = JcaAgreementKey(public, private)

    override fun agree(peerDer: ByteArray): ByteArray? = key.agree(peerDer)

    @Serializable
    private data class Saved(val public: String = "", val private: String = "", val at: Long = 0)

    companion object {
        /** A new pair. */
        fun make(at: Long = System.currentTimeMillis()): PhoneKeys {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec(CURVE), keyRandom())
            val pair = g.generateKeyPair()
            return PhoneKeys(pair.public, pair.private, at)
        }

        /** The pair kept in [file], made and written there the first time it is asked for (or when what is there cannot be read). */
        internal fun inFile(file: File): PhoneKeys {
            try {
                if (file.isFile) {
                    val s = AppJson.decodeFromString<Saved>(file.readText())
                    val f = KeyFactory.getInstance("EC")
                    val pub = f.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(s.public)))
                    val priv = f.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(s.private)))
                    return PhoneKeys(pub, priv, s.at)
                }
            } catch (_: Exception) {
            }
            val k = make()
            try {
                file.parentFile?.mkdirs()
                writeWhole(file, AppJson.encodeToString(Saved(k.publicText, Base64.getEncoder().encodeToString(k.private.encoded), k.madeAt)))
            } catch (_: Exception) {
            }
            return k
        }

        /** A public key as it travels ([publicText]), or null when [text] is not one. */
        fun publicFrom(text: String): PublicKey? = try {
            if (text.isBlank()) null else KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(text)))
        } catch (_: Exception) {
            null
        }
    }
}

/** The JCA, as the shared code's [PlatformCrypto]. */
internal object JcaCrypto : PlatformCrypto {
    private const val TAG_BITS = 128

    override fun generateKey(): AgreementKey {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec(CURVE), sealingRandom)
        return JcaAgreementKey.of(g.generateKeyPair())
    }

    override fun isPublicKey(der: ByteArray): Boolean = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        true
    } catch (_: Exception) {
        false
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }

    override fun sealGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain)

    override fun openGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        gcm(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
    } catch (_: Exception) {
        null
    }

    override fun phoneKeys(phoneId: String, file: File?): UpdateKeyPair = file?.let { PhoneKeys.inFile(it) } ?: PhoneKeys.make()
}

/** The update-file crypto the shared code calls (Crypto.kt): the JCA, on Android and the JVM. */
internal actual val platformCrypto: PlatformCrypto? = JcaCrypto

/** Where new phone key pairs (and nothing else) get their randomness. A test that has to repeat a run exactly swaps in a seeded source. */
@Volatile
internal var keyRandom: () -> SecureRandom = { SecureRandom() }
