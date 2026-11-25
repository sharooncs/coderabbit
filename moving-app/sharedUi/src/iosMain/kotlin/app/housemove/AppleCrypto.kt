@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package app.housemove

import app.housemove.data.AppleCryptoKit
import app.housemove.data.AppleKeyHandle
import app.housemove.data.KeychainAnswer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/*
 * The iPhone's crypto comes from Swift: Kotlin cannot call CryptoKit, which is Swift only. The app's CryptoKitCrypto
 * (iosApp/HouseMove/CryptoKitCrypto.swift) implements these protocols, and StartView hands it to MainViewController at
 * start-up, which passes it on to the shared code (app.housemove.data.useAppleCrypto) through [SwiftCrypto]. Data crosses
 * as Data in Swift and NSData here; nothing else does, and the private keys stay in Swift and the Keychain.
 */

/** CryptoKit and the Keychain, as the Swift app gives them. In Swift: `final class CryptoKitCrypto: NSObject, AppleCrypto`. */
interface AppleCrypto {
    /** A new P-256 key pair, in memory only: `P256.KeyAgreement.PrivateKey()`. */
    fun generateKey(): AppleKey

    /** The key pair of the 32-byte private scalar [raw] (`rawRepresentation`), or nil; the app's crypto check only. */
    fun importKey(raw: NSData): AppleKey?

    /** Is [x963] (65 bytes: 04, x, y) a point on P-256? */
    fun isPublicKey(x963: NSData): Boolean

    /** AES-256-GCM with this 12-byte [nonce] and [aad]: the ciphertext, then the 16-byte tag; nil if it cannot. */
    fun seal(key: NSData, nonce: NSData, aad: NSData, plain: NSData): NSData?

    /** What [seal] sealed (ciphertext, then tag), or nil when it does not open. */
    fun open(key: NSData, nonce: NSData, aad: NSData, sealed: NSData): NSData?

    /**
     * The key pair kept in the Keychain (generic password, [service], [account]), or with [create] a new one kept there
     * (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`) when there is none, with the Keychain's OSStatus.
     */
    fun keychainKey(service: String, account: String, create: Boolean): AppleKeychainItem

    /** Removes every key kept under [service]: the OSStatus, 0 when done or when there was none. */
    fun keychainForget(service: String): Int

    /**
     * CryptoKit's own HPKE, for the crypto check only (HouseMove's HPKE is Kotlin on the calls above): `HPKE.Sender` with
     * ciphersuite `P256_SHA256_AES_GCM_256`, sealing [plain] to the point [recipientX963], authenticated by the key of the
     * scalar [senderRaw], with [info] and [aad]. The encapsulated key (65 bytes), then the sealed message; nil if it cannot.
     */
    fun hpkeSealAuth(recipientX963: NSData, senderRaw: NSData, info: NSData, aad: NSData, plain: NSData): NSData?

    /** `HPKE.Recipient`, the same ciphersuite and mode: what was sealed to the scalar [recipientRaw] by [senderX963], or nil. */
    fun hpkeOpenAuth(recipientRaw: NSData, senderX963: NSData, info: NSData, aad: NSData, enc: NSData, sealed: NSData): NSData?
}

/** A P-256 key pair CryptoKit holds. */
interface AppleKey {
    /** The public key, uncompressed (`x963Representation`, 65 bytes). */
    val publicX963: NSData

    /** The ECDH shared secret (32 bytes) with the point [peerX963], or nil when it is not a P-256 point. */
    fun agree(peerX963: NSData): NSData?
}

/** What the Keychain said: an OSStatus, and the key when there is one. */
interface AppleKeychainItem {
    val status: Int
    val key: AppleKey?
}

internal fun ByteArray.toData(): NSData = if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

internal fun NSData.toBytes(): ByteArray {
    val n = length.toInt()
    if (n == 0) return ByteArray(0)
    val out = ByteArray(n)
    out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** The Swift app's [AppleCrypto] as the shared code's AppleCryptoKit: Data in Swift, byte arrays in Kotlin. */
internal class SwiftCrypto(private val swift: AppleCrypto) : AppleCryptoKit {
    private class Handle(val key: AppleKey) : AppleKeyHandle {
        override val publicX963: ByteArray = key.publicX963.toBytes()

        override fun agree(peerX963: ByteArray): ByteArray? = key.agree(peerX963.toData())?.toBytes()
    }

    override fun generateKey(): AppleKeyHandle = Handle(swift.generateKey())

    override fun importKey(raw: ByteArray): AppleKeyHandle? = swift.importKey(raw.toData())?.let { Handle(it) }

    override fun isPublicKey(x963: ByteArray): Boolean = swift.isPublicKey(x963.toData())

    override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray? =
        swift.seal(key.toData(), nonce.toData(), aad.toData(), plain.toData())?.toBytes()

    override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        swift.open(key.toData(), nonce.toData(), aad.toData(), sealed.toData())?.toBytes()

    override fun keychainKey(service: String, account: String, create: Boolean): KeychainAnswer {
        val item = swift.keychainKey(service, account, create)
        return KeychainAnswer(item.status, item.key?.let { Handle(it) })
    }

    override fun keychainForget(service: String): Int = swift.keychainForget(service)

    override fun hpkeSealAuth(recipientX963: ByteArray, senderRaw: ByteArray, info: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray? =
        swift.hpkeSealAuth(recipientX963.toData(), senderRaw.toData(), info.toData(), aad.toData(), plain.toData())?.toBytes()

    override fun hpkeOpenAuth(recipientRaw: ByteArray, senderX963: ByteArray, info: ByteArray, aad: ByteArray, enc: ByteArray, sealed: ByteArray): ByteArray? =
        swift.hpkeOpenAuth(recipientRaw.toData(), senderX963.toData(), info.toData(), aad.toData(), enc.toData(), sealed.toData())?.toBytes()
}
