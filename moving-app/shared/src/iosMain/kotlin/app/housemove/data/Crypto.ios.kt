@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.concurrent.Volatile

// The iPhone's side of Crypto.kt. P-256 and AES-256-GCM are CryptoKit's, in Swift (iosApp's CryptoKitCrypto.swift): Kotlin
// cannot call CryptoKit, so the app hands its Swift object to the shared screens at start-up (sharedUi's MainViewController),
// which pass it here through [useAppleCrypto]. The phone's private key is kept in the Keychain, readable after the first
// unlock and on this device only, never in a file and never in a backup.

/** The crypto the app registered ([useAppleCrypto]); tests on the simulator may put in another. Null until then: no keys, no update files. */
@Volatile
internal var registeredCrypto: PlatformCrypto? = null

internal actual val platformCrypto: PlatformCrypto? get() = registeredCrypto

internal actual fun base64Encode(bytes: ByteArray): String = JavaBase64.encode(bytes)

internal actual fun base64Decode(text: String): ByteArray = JavaBase64.decode(text)

internal actual fun secureBytes(n: Int): ByteArray {
    val b = ByteArray(n)
    if (n == 0) return b
    val status = b.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "no random bytes ($status)" }
    return b
}

/**
 * What the iPhone app gives the shared code: CryptoKit's P-256 key agreement and AES-GCM, and the Keychain. Implemented in
 * Swift (CryptoKitCrypto.swift) behind sharedUi's AppleCrypto protocol, which passes Data where this passes byte arrays.
 * Public keys are bare uncompressed points (65 bytes, CryptoKit's `x963Representation`); [IosCrypto] turns them into the
 * X.509 form Android sends.
 */
interface AppleCryptoKit {
    /** A new P-256 key pair, in memory only (`P256.KeyAgreement.PrivateKey()`). */
    fun generateKey(): AppleKeyHandle

    /** The key pair whose private key is the 32-byte scalar [raw] (`init(rawRepresentation:)`), or null: for the known-answer check only. */
    fun importKey(raw: ByteArray): AppleKeyHandle?

    /** Is [x963] a point on P-256 (`P256.KeyAgreement.PublicKey(x963Representation:)`)? */
    fun isPublicKey(x963: ByteArray): Boolean

    /** AES-256-GCM: ciphertext then the 16-byte tag, or null when it cannot seal. */
    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray?

    /** What [seal] sealed, or null when it does not open. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?

    /**
     * The key pair kept in the Keychain under [service] and [account], or with [create] one made and kept there when there is
     * none (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`). [KeychainAnswer.status] is the Keychain's OSStatus.
     */
    fun keychainKey(service: String, account: String, create: Boolean): KeychainAnswer

    /** Removes every key kept under [service]: an OSStatus, 0 when done (or there was none). */
    fun keychainForget(service: String): Int
}

/** A P-256 key pair CryptoKit holds: the public point, and ECDH with its private key. */
interface AppleKeyHandle {
    /** The public key as an uncompressed point (65 bytes). */
    val publicX963: ByteArray

    /** The shared secret with the point [peerX963] (32 bytes, the x-coordinate), or null when it is not a P-256 point. */
    fun agree(peerX963: ByteArray): ByteArray?
}

/** What the Keychain said: [status] (an OSStatus: 0 found or made, -25300 none there, others the Keychain cannot be used now) and the key. */
class KeychainAnswer(val status: Int, val key: AppleKeyHandle?)

/** The Keychain's OSStatus values the shared code tells apart. */
internal object KeychainStatus {
    const val OK = 0
    const val NOT_FOUND = -25300
    const val MISSING_ENTITLEMENT = -34018
}

/** The app's Keychain service for this phone's key, and demo mode's and the crypto check's, kept apart from it. */
object KeychainServices {
    const val PHONE = "app.housemove.phone-key"
    const val DEMO = "app.housemove.demo-key"
    const val CHECK = "app.housemove.check-key"
}

/**
 * Registers the app's CryptoKit for the shared code, keeping keys under the Keychain [service]. Called once at start-up,
 * before any move is opened. [noBackupDir] is the folder beside the phone ID that iOS keeps out of backups and deletes with
 * the app: when it holds no reinstall marker, this is the first start since HouseMove was installed (or the phone was
 * restored), and every key a former install left in the Keychain (which outlives the app) is removed first, so a reinstalled
 * app makes a new key rather than finding a stale one.
 */
fun useAppleCrypto(kit: AppleCryptoKit, service: String, noBackupDir: File) {
    forgetKeysAfterReinstall(kit, service, noBackupDir)
    registeredCrypto = IosCrypto(kit, service)
}

/** The reinstall marker's name, in the no-backup folder. */
internal const val REINSTALL_MARKER = "keychain-marker"

/** See [useAppleCrypto]. True when the Keychain was cleared now. */
internal fun forgetKeysAfterReinstall(kit: AppleCryptoKit, service: String, noBackupDir: File): Boolean {
    val marker = File(noBackupDir, REINSTALL_MARKER)
    if (marker.isFile) return false
    val status = kit.keychainForget(service)
    // When the Keychain cannot be used now, the marker waits for a start when it can; meanwhile no stale key can be found,
    // since each key is kept under its own phone's ID, which a new install does not have.
    if (status != KeychainStatus.OK) return false
    noBackupDir.mkdirs()
    writeWhole(marker, "HouseMove keeps this phone's key in the Keychain under service $service.\n")
    return true
}

/** CryptoKit's ECDH and AES-GCM ([AppleCryptoKit]) as the shared code's [PlatformCrypto], with X.509 public keys. */
internal class IosCrypto(private val kit: AppleCryptoKit, private val service: String) : PlatformCrypto {
    override fun generateKey(): AgreementKey = AppleAgreementKey(kit.generateKey())

    /** The key pair of the 32-byte scalar [raw] (the known-answer check), or null. */
    fun importKey(raw: ByteArray): AgreementKey? = kit.importKey(raw)?.let { AppleAgreementKey(it) }

    override fun isPublicKey(der: ByteArray): Boolean = P256Der.point(der)?.let { kit.isPublicKey(it) } == true

    override fun sealGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray =
        kit.seal(key, nonce, aad, plain) ?: throw IllegalStateException("AES-GCM did not seal")

    override fun openGcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        if (key.size != 32 || nonce.size != 12 || sealed.size < Sealing.TAG) null else kit.open(key, nonce, aad, sealed)

    override fun phoneKeys(phoneId: String, file: File?): UpdateKeyPair =
        if (file == null) MemoryKeys(generateKey(), currentTimeMillis()) else KeychainKeys(kit, service, phoneId, file)
}

/** A CryptoKit key pair, with X.509 public keys. */
internal class AppleAgreementKey(private val handle: AppleKeyHandle) : AgreementKey {
    override val publicDer: ByteArray = P256Der.of(handle.publicX963)

    override fun agree(peerDer: ByteArray): ByteArray? = P256Der.point(peerDer)?.let { handle.agree(it) }
}

/** A phone key pair kept in memory only (a Phone without a key file). */
private class MemoryKeys(private val key: AgreementKey, override val madeAt: Long) : UpdateKeyPair {
    override val publicText: String = base64Encode(key.publicDer)

    override fun agree(peerDer: ByteArray): ByteArray? = key.agree(peerDer)
}

/**
 * This phone's key pair for update files on the iPhone: the private key in the Keychain under [service] and the phone's
 * ID (so a key can only ever be found by the install that made it), and in [file], beside the phone ID, the public key and
 * when it was made, which the phone's entry in the move carries. Found or made the first time it is used; while the
 * Keychain cannot be used (before the first unlock after a restart), the phone has no key for now and tries again later.
 */
private class KeychainKeys(private val kit: AppleCryptoKit, private val service: String, private val phoneId: String, private val file: File) : UpdateKeyPair {
    @Serializable
    private data class Saved(val public: String = "", val at: Long = 0)

    private val lock = Lock()
    private var found: Pair<AgreementKey, Long>? = null

    private fun key(): Pair<AgreementKey, Long>? = synchronized(lock) { found ?: load()?.also { found = it } }

    override val publicText: String get() = key()?.let { base64Encode(it.first.publicDer) } ?: ""
    override val madeAt: Long get() = key()?.second ?: 0

    override fun agree(peerDer: ByteArray): ByteArray? = key()?.first?.agree(peerDer)

    private fun load(): Pair<AgreementKey, Long>? {
        val saved = try {
            if (file.isFile) AppJson.decodeFromString<Saved>(file.readText()) else null
        } catch (_: Exception) {
            null
        }
        var answer = kit.keychainKey(service, phoneId, create = false)
        if (answer.status == KeychainStatus.NOT_FOUND) answer = kit.keychainKey(service, phoneId, create = true)
        val handle = answer.key
        if (answer.status != KeychainStatus.OK || handle == null) return null
        val key = AppleAgreementKey(handle)
        val text = base64Encode(key.publicDer)
        if (saved != null && saved.public == text) return key to saved.at
        // Made now, or the file was lost: the key the Keychain holds is the one this phone can open updates with.
        val at = currentTimeMillis()
        try {
            file.parentFile?.mkdirs()
            writeWhole(file, AppJson.encodeToString(Saved(text, at)))
        } catch (_: Exception) {
        }
        return key to at
    }
}
