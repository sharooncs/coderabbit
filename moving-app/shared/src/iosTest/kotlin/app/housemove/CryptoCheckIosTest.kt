package app.housemove

import app.housemove.data.AppleCryptoKit
import app.housemove.data.AppleKeyHandle
import app.housemove.data.CryptoCheck
import app.housemove.data.File
import app.housemove.data.IosCrypto
import app.housemove.data.KeychainAnswer
import app.housemove.data.KeychainStatus
import app.housemove.data.base64Decode
import app.housemove.data.forgetKeysAfterReinstall
import app.housemove.data.isFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The app's crypto check (CryptoCheck, which the UI tests run with CryptoKit) and the iPhone's Keychain rules (Crypto.ios.kt),
 * with a stand-in for the app's Swift: the reference crypto, and a Keychain in memory that can be made to refuse. If the
 * check passes here and fails in the app, the difference is CryptoKit's or the Keychain's.
 */
class CryptoCheckIosTest {
    /** What CryptoKitCrypto.swift does, with the reference crypto and a Keychain in memory. */
    private class StandIn : AppleCryptoKit {
        val keychain = HashMap<String, ByteArray>()

        /** The Keychain's answer to every call while it is not 0 (as before the first unlock). */
        var refusing = 0

        private class Handle(val d: ByteArray) : AppleKeyHandle {
            override val publicX963: ByteArray = RefP256.publicOf(d)

            override fun agree(peerX963: ByteArray): ByteArray? = RefP256.agree(d, peerX963)
        }

        override fun generateKey(): AppleKeyHandle = Handle(RefP256.newScalar())

        override fun importKey(raw: ByteArray): AppleKeyHandle? = if (RefP256.isScalar(raw)) Handle(raw) else null

        override fun isPublicKey(x963: ByteArray): Boolean = RefP256.isPoint(x963)

        override fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray? = RefGcm.seal(key, nonce, aad, plain)

        override fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = RefGcm.open(key, nonce, aad, sealed)

        override fun keychainKey(service: String, account: String, create: Boolean): KeychainAnswer {
            if (refusing != 0) return KeychainAnswer(refusing, null)
            val k = "$service/$account"
            keychain[k]?.let { return KeychainAnswer(KeychainStatus.OK, Handle(it)) }
            if (!create) return KeychainAnswer(KeychainStatus.NOT_FOUND, null)
            val d = RefP256.newScalar()
            keychain[k] = d
            return KeychainAnswer(KeychainStatus.OK, Handle(d))
        }

        override fun keychainForget(service: String): Int {
            if (refusing != 0) return refusing
            keychain.keys.removeAll { it.startsWith("$service/") }
            return KeychainStatus.OK
        }
    }

    @Test
    fun theCryptoCheckPassesWithTheReferenceCrypto() {
        inTempFolder { dir ->
            val lines = CryptoCheck.run(StandIn(), File(dir, "check"))
            for (l in lines) println(l.text)
            assertTrue(lines.size >= 8, "every check ran: ${lines.map { it.text }}")
            assertTrue(lines.all { it.ok == true }, lines.filter { it.ok != true }.joinToString("\n") { it.text })
            assertEquals("Crypto check passed", CryptoCheck.summary(lines))
        }
    }

    @Test
    fun thePhoneKeyIsKeptInTheKeychainUnderItsPhoneId() {
        inTempFolder { dir ->
            val kit = StandIn()
            val file = File(dir, "no-backup/phone-key.json")
            val one = IosCrypto(kit, "svc").phoneKeys("pone", file)
            assertTrue(one.publicText.isNotEmpty())
            assertTrue(file.isFile, "the public key and when it was made, beside the phone ID")
            assertEquals(setOf("svc/pone"), kit.keychain.keys, "the private key in the Keychain under the phone ID, nowhere else")
            // Found again, as it was.
            val two = IosCrypto(kit, "svc").phoneKeys("pone", file)
            assertEquals(one.publicText, two.publicText)
            assertEquals(one.madeAt, two.madeAt)
            // Another phone ID (a reinstalled app has a new one) never finds it.
            val other = IosCrypto(kit, "svc").phoneKeys("ptwo", File(dir, "other/phone-key.json"))
            assertNotEquals(one.publicText, other.publicText)
            // Opening needs the private key: what one agrees with a one-use key, the other does too.
            val eph = IosCrypto(kit, "svc").generateKey()
            assertEquals(eph.agree(base64Decode(one.publicText))?.toList(), two.agree(eph.publicDer)?.toList())
        }
    }

    @Test
    fun whileTheKeychainCannotBeUsedThePhoneHasNoKeyAndTriesAgain() {
        inTempFolder { dir ->
            val kit = StandIn()
            kit.refusing = -25308 // errSecInteractionNotAllowed: before the first unlock
            val keys = IosCrypto(kit, "svc").phoneKeys("pone", File(dir, "phone-key.json"))
            assertEquals("", keys.publicText)
            assertEquals(0L, keys.madeAt)
            assertTrue(kit.keychain.isEmpty(), "nothing made, nothing replaced")
            kit.refusing = 0
            assertTrue(keys.publicText.isNotEmpty(), "found or made once the Keychain can be used")
            assertEquals(1, kit.keychain.size)
        }
    }

    @Test
    fun aFirstStartAfterInstallingClearsWhatAFormerInstallLeft() {
        inTempFolder { dir ->
            val kit = StandIn()
            kit.keychainKey("svc", "pold", create = true)
            kit.keychainKey("other", "pkept", create = true)
            val noBackup = File(dir, "no-backup")
            assertTrue(forgetKeysAfterReinstall(kit, "svc", noBackup), "no marker: cleared")
            assertEquals(setOf("other/pkept"), kit.keychain.keys, "only this service's keys go")
            kit.keychainKey("svc", "pnew", create = true)
            assertFalse(forgetKeysAfterReinstall(kit, "svc", noBackup), "the marker is there: nothing cleared")
            assertNotNull(kit.keychain["svc/pnew"])
            // A Keychain that cannot be used leaves no marker, so it is tried again at the next start.
            val later = File(dir, "later")
            kit.refusing = -25308
            assertFalse(forgetKeysAfterReinstall(kit, "svc", later))
            assertFalse(File(later, "keychain-marker").isFile)
        }
    }
}
