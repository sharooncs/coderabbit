package app.housemove.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * This installed copy's key pair for update files (see Update.kt): an EC P-256 pair made once and kept
 * beside the phone ID, outside Android's backup, so a phone restored or cloned from this one makes its own.
 * Plain JCA, so it works the same on the JVM as on a phone. The private key never leaves this phone; the
 * public one travels with this phone's entry in the move ([PhoneEntry.key]), so after any sync every phone
 * knows the keys of the move's Organiser and Decider phones and can send them an update.
 */
class PhoneKeys internal constructor(val public: PublicKey, internal val private: PrivateKey, val madeAt: Long) {
    /** The public key as it travels: X.509, Base64 on one line. */
    val publicText: String = Base64.getEncoder().encodeToString(public.encoded)

    @Serializable
    private data class Saved(val public: String = "", val private: String = "", val at: Long = 0)

    companion object {
        private const val CURVE = "secp256r1"

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

/** Where new key pairs (and nothing else) get their randomness. A test that has to repeat a run exactly swaps in a seeded source. */
@Volatile
internal var keyRandom: () -> SecureRandom = { SecureRandom() }
