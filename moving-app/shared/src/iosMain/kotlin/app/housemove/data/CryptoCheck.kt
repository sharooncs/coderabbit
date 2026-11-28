package app.housemove.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One line of the crypto check: what was checked, and whether it held (null: it could not be checked in this build). */
class CryptoCheckLine(val ok: Boolean?, val what: String) {
    val text: String get() = (if (ok == null) "Check skipped: " else if (ok) "Check passed: " else "Check failed: ") + what
}

/**
 * The iPhone's crypto, checked against Android's in the app itself, with the CryptoKit the app registers: run when the app
 * is launched with `-HMCryptoCheck YES`, which only Xcode and the UI tests can pass (sharedUi's MainViewController shows the
 * lines, and LaunchTests.testCryptoKitOpensAndSealsAsAndroidDoes reads them). Kotlin tests cannot call CryptoKit, so this is
 * how CI runs the real path at least once:
 *
 * - Android's update file (golden/update.json, recorded on 2.1.1) opens: every value on the way in is Android's (ECDH,
 *   HKDF, the content key, the chunks), and Update.open on Asha's phone, started from her folder, says what it said there.
 * - The sealing known-answer file (sealing/update-sealed.json, made by the JVM's JCA) is sealed byte for byte, so the
 *   update files the iPhone writes are ones the JVM, and Android, open; and it opens on Asha's phone.
 * - The Keychain keeps a key across reads, the phone key is found again, and the reinstall marker clears old keys.
 *
 * The same checks run with the reference crypto in the simulator's Kotlin tests (CryptoCheckIosTest), so a failure here that
 * does not fail there is CryptoKit's.
 */
object CryptoCheck {
    private const val T_PRIYA = 1_767_312_000_000L

    /** Runs every check with [kit], in [scratch] (a folder of its own, emptied first and after). */
    fun run(kit: AppleCryptoKit, scratch: File): List<CryptoCheckLine> {
        val lines = ArrayList<CryptoCheckLine>()
        fun check(what: String, block: () -> Boolean?) {
            lines += try {
                CryptoCheckLine(block(), what)
            } catch (e: Throwable) {
                CryptoCheckLine(false, "$what (${e::class.simpleName}: ${e.message})")
            }
        }
        scratch.deleteRecursively()
        scratch.mkdirs()
        val crypto = IosCrypto(kit, KeychainServices.CHECK)
        val update = Json.parseToJsonElement(CryptoCheckFiles.text.getValue("update.json")).jsonObject
        val kat = Json.parseToJsonElement(CryptoCheckFiles.text.getValue("update-sealed.json")).jsonObject
        val recipient = update.obj("recipient")
        val ashaId = recipient.str("phoneId")
        val ashaKey = pkcs8Scalar(base64Decode(recipient.str("privateKey")))?.let { crypto.importKey(it) }
        val ashaAt = Json.parseToJsonElement(recipient.obj("files").str("phone-key.json")).jsonObject.getValue("at").jsonPrimitive.long

        check("CryptoKit has Asha's key pair, with the public key Android made") {
            ashaKey != null && ashaKey.publicDer.contentEquals(base64Decode(recipient.str("publicKey")))
        }
        check("ECDH, HKDF, the content key and the chunks of Android's update are Android's") {
            ashaKey?.let { androidValues(crypto, update, it) } ?: false
        }
        val before = registeredCrypto
        try {
            // Asha's phone has her key from the golden folder; every other key and all the crypto are CryptoKit's.
            registeredCrypto = if (ashaKey == null) crypto else CheckCrypto(crypto, mapOf(ashaId to FixedKeys(ashaKey, ashaAt)))
            check("Android's update opens on Asha's phone as it did on Android: " + update.obj("opened").str("message")) {
                val opened = openOnAsha(update, File(scratch, "android"), unhex(update.obj("sample").str("hex")))
                sameAs(update.obj("opened"), opened)
            }
            var made: ByteArray? = null
            check("An update sealed here is the JVM's byte for byte (sealing/update-sealed.json)") {
                val bytes = sealKnownAnswer(crypto, kat)
                made = bytes
                sha256(bytes).toHex() == kat.str("fileSha256") && bytes.toHex() == kat.str("file")
            }
            check("That update opens on Asha's phone: " + kat.obj("opened").str("message")) {
                val bytes = made ?: return@check false
                sameAs(kat.obj("opened"), openOnAsha(update, File(scratch, "sealed"), bytes))
            }
        } finally {
            registeredCrypto = before
        }
        noiseChecks(crypto, ::check)
        hpkeChecks(kit, crypto, ::check)
        keychainChecks(kit, crypto, scratch, ::check)
        scratch.deleteRecursively()
        return lines
    }

    /**
     * The encrypted sync through CryptoKit (design/encryption.md, Part 2): the IK and XXpsk0 handshakes and the channel's
     * frames the JVM recorded (noise/handshakes.json, NoiseFixtureTest), made again here with CryptoKit's ECDH and AES-GCM on
     * the same fixed keys, byte for byte; a changed message refused; and both handshakes between keys CryptoKit makes now.
     */
    private fun noiseChecks(crypto: IosCrypto, check: (String, () -> Boolean?) -> Unit) {
        val kat = Json.parseToJsonElement(CryptoCheckFiles.text.getValue("handshakes.json")).jsonObject
        val keys = kat.obj("scalars").mapValues { (_, v) -> unhex(v.jsonPrimitive.content) }
        fun key(name: String): app.housemove.sync.NoiseKey =
            crypto.importKey(keys.getValue(name))?.let { app.housemove.sync.NoiseCheck.keyOf(it) } ?: throw IllegalStateException("CryptoKit did not take $name")
        check("CryptoKit makes the handshakes' public keys the JVM made") {
            kat.obj("publicKeys").all { (name, v) -> key(name).point.toHex() == v.jsonPrimitive.content }
        }
        for ((pattern, field) in listOf(app.housemove.sync.Noise.IK to "ik", app.housemove.sync.Noise.XX_PSK0 to "xxpsk0")) {
            check("Noise $pattern through CryptoKit: every message, the hash, the keys and the frames are the JVM's (noise/handshakes.json)") {
                app.housemove.sync.NoiseCheck.replay(crypto, kat.obj(field), pattern, kat.str("moveId"), ::key)
            }
        }
        check("Noise IK and XXpsk0 between keys CryptoKit makes now; a changed message does not open") {
            app.housemove.sync.NoiseCheck.live(crypto)
        }
    }

    /**
     * HPKE through CryptoKit (design/encryption.md, "Mailbox as built"): the CFRG's vectors for HouseMove's suite, HouseMove's
     * mailbox envelope and update file of format 2 the JVM recorded (hpke/housemove.json), byte for byte, and HouseMove's HPKE
     * against CryptoKit's own.
     */
    private fun hpkeChecks(kit: AppleCryptoKit, crypto: IosCrypto, check: (String, () -> Boolean?) -> Unit) {
        val vectors = Json.parseToJsonElement(CryptoCheckFiles.text.getValue("vectors.json")).jsonObject
        val kat = Json.parseToJsonElement(CryptoCheckFiles.text.getValue("housemove.json")).jsonObject
        fun keyOf(raw: ByteArray): AgreementKey = crypto.importKey(raw) ?: throw IllegalStateException("CryptoKit did not take a fixed key")
        check("HPKE through CryptoKit: the CFRG's vectors for DHKEM(P-256), HKDF-SHA256 and AES-256-GCM, mode_auth and mode_base (hpke/vectors.json)") {
            app.housemove.sync.HpkeCheck.vectors(crypto, vectors, ::keyOf)
        }
        check("HouseMove's mailbox envelope through CryptoKit is the JVM's byte for byte, and opens (hpke/housemove.json)") {
            app.housemove.sync.HpkeCheck.envelope(crypto, kat, ::keyOf)
        }
        check("HouseMove's update file of format 2 through CryptoKit is the JVM's byte for byte (hpke/housemove.json)") {
            app.housemove.sync.HpkeCheck.update(crypto, kat, ::keyOf)
        }
        check("CryptoKit's own HPKE opens what HouseMove's HPKE seals, and the other way round") {
            app.housemove.sync.HpkeCheck.againstCryptoKit(kit, crypto, kat, ::keyOf)
        }
    }

    /** The summary line: passed when no line failed. */
    fun summary(lines: List<CryptoCheckLine>): String = if (lines.none { it.ok == false }) "Crypto check passed" else "Crypto check failed"

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The private scalar of a P-256 key in PKCS#8 as the JCA writes it: a fixed 36-byte start, then the scalar. */
    private fun pkcs8Scalar(pkcs8: ByteArray): ByteArray? {
        val start = base64Decode("MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg")
        if (pkcs8.size < start.size + 32) return null
        for (i in start.indices) if (pkcs8[i] != start[i]) return null
        return pkcs8.copyOfRange(start.size, start.size + 32)
    }

    private class FixedKeys(private val key: AgreementKey, override val madeAt: Long) : UpdateKeyPair {
        override val publicText: String = base64Encode(key.publicDer)

        override fun agree(peerDer: ByteArray): ByteArray? = key.agree(peerDer)
    }

    /** CryptoKit, with the golden phones' keys for their phone IDs. */
    private class CheckCrypto(private val base: PlatformCrypto, private val fixed: Map<String, UpdateKeyPair>) : PlatformCrypto by base {
        override fun phoneKeys(phoneId: String, file: File?): UpdateKeyPair = fixed[phoneId] ?: base.phoneKeys(phoneId, file)
    }

    private class BytesInput(private val b: ByteArray) : InputStream() {
        private var pos = 0

        override fun readBytes(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos == this.b.size) return -1
            val n = minOf(len, this.b.size - pos)
            this.b.copyInto(b, off, pos, pos + n)
            pos += n
            return n
        }
    }

    private class BytesOutput : OutputStream() {
        var bytes = ByteArray(0)
            private set

        override fun writeBytes(b: ByteArray, off: Int, len: Int) {
            bytes += b.copyOfRange(off, off + len)
        }
    }

    private fun androidValues(crypto: PlatformCrypto, update: JsonObject, asha: AgreementKey): Boolean {
        val want = update.obj("intermediate")
        val sample = unhex(update.obj("sample").str("hex"))
        val headerLength = ((sample[12].toInt() and 0xff) shl 24) or ((sample[13].toInt() and 0xff) shl 16) or ((sample[14].toInt() and 0xff) shl 8) or (sample[15].toInt() and 0xff)
        val headerBytes = sample.copyOfRange(16, 16 + headerLength)
        val header = Json.parseToJsonElement(headerBytes.decodeUtf8()).jsonObject
        val to = header.getValue("to").jsonArray.single().jsonObject
        val phoneId = update.obj("recipient").str("phoneId")
        val secret = asha.agree(base64Decode(to.str("epk"))) ?: return false
        if (secret.toHex() != want.str("ecdhSecret")) return false
        val kek = Sealing.hkdf(secret, base64Decode(to.str("salt")), "HouseMove update key 1|${header.str("moveId")}|$phoneId".toUtf8())
        if (kek.toHex() != want.str("kek")) return false
        val contentKey = crypto.openGcm(kek, base64Decode(to.str("iv")), phoneId.toUtf8(), base64Decode(to.str("key"))) ?: return false
        if (contentKey.toHex() != want.str("contentKey")) return false
        val prefix = base64Decode(header.str("nonce"))
        val reader = SealedIn(crypto, BytesInput(sample.copyOfRange(16 + headerLength, sample.size)), contentKey, prefix, sha256(headerBytes))
        val zip = BytesOutput()
        val buf = ByteArray(4096)
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) break
            zip.writeBytes(buf, 0, n)
        }
        return sha256(zip.bytes).toHex() == want.str("zipSha256")
    }

    /** Opens [bytes] on Asha's phone, started in [dir] from her folder in golden/update.json, with Priya's clock. */
    private fun openOnAsha(update: JsonObject, dir: File, bytes: ByteArray): Update.Opened {
        dir.mkdirs()
        for ((name, text) in update.obj("recipient").obj("files")) writeWhole(File(dir, name), text.jsonPrimitive.content)
        val s = Store(dir) { T_PRIYA }
        try {
            return Update.open(s, BytesInput(bytes))
        } finally {
            s.close()
        }
    }

    private fun sameAs(want: JsonObject, o: Update.Opened): Boolean =
        o.ok == want.getValue("ok").jsonPrimitive.boolean && o.message == want.str("message") && o.fromName == want.str("fromName") &&
            o.changes == want.getValue("changes").jsonPrimitive.int && o.photos == want.getValue("photos").jsonPrimitive.int

    /** The known-answer file, sealed here as the common tests' SealingKat seals it: through Update.wrappedFor and Update.writeSealed. */
    private fun sealKnownAnswer(crypto: IosCrypto, kat: JsonObject): ByteArray {
        val i = kat.obj("inputs")
        val ephemeral = crypto.importKey(unhex(i.str("ephemeralScalar"))) ?: throw IllegalStateException("CryptoKit did not take the one-use key")
        if (base64Encode(ephemeral.publicDer) != kat.str("ephemeralKey")) throw IllegalStateException("the one-use key's public half is not the JVM's")
        val contentKey = unhex(i.str("contentKey"))
        val prefix = unhex(i.str("prefix"))
        val payload = unhex(i.str("payload"))
        val to = Update.wrappedFor(
            crypto, contentKey, base64Decode(i.str("recipientKey")), i.str("recipient"), i.str("moveId"), ephemeral, unhex(i.str("salt")), unhex(i.str("iv")),
        )
        val header = Update.Header(
            schema = i.getValue("schema").jsonPrimitive.int, moveId = i.str("moveId"), from = i.str("from"), at = i.getValue("at").jsonPrimitive.long,
            nonce = base64Encode(prefix), to = listOf(to),
        )
        val out = BytesOutput()
        Update.writeSealed(crypto, out, header, contentKey, prefix) {
            it.write(payload, 0, payload.size)
            it.close()
        }
        return out.bytes
    }

    private fun keychainChecks(kit: AppleCryptoKit, crypto: IosCrypto, scratch: File, check: (String, () -> Boolean?) -> Unit) {
        val service = KeychainServices.CHECK
        val first = kit.keychainForget(service)
        if (first == KeychainStatus.MISSING_ENTITLEMENT) {
            check("the Keychain: this build has no keychain entitlement (OSStatus -34018), as with code signing off") { null }
            return
        }
        check("the Keychain keeps a key, readable again, that agrees keys (status $first)") {
            val made = kit.keychainKey(service, "check", create = true)
            val again = kit.keychainKey(service, "check", create = false)
            val a = made.key
            val b = again.key
            if (made.status != KeychainStatus.OK || again.status != KeychainStatus.OK || a == null || b == null) {
                throw IllegalStateException("statuses ${made.status} and ${again.status}")
            }
            val other = kit.generateKey()
            a.publicX963.contentEquals(b.publicX963) &&
                b.agree(other.publicX963)?.contentEquals(other.agree(a.publicX963) ?: ByteArray(0)) == true
        }
        check("this phone's key is kept in the Keychain and found again, with its public key beside the phone ID") {
            val file = File(scratch, "no-backup/phone-key.json")
            val one = crypto.phoneKeys("pcheckphone000000000", file)
            val two = IosCrypto(kit, service).phoneKeys("pcheckphone000000000", file)
            val other = crypto.generateKey()
            one.publicText.isNotEmpty() && one.publicText == two.publicText && one.madeAt == two.madeAt &&
                two.agree(other.publicDer)?.contentEquals(other.agree(base64Decode(one.publicText)) ?: ByteArray(0)) == true
        }
        check("a first start after installing clears the keys a former install left, once") {
            val noBackup = File(scratch, "reinstalled")
            val cleared = forgetKeysAfterReinstall(kit, service, noBackup)
            val gone = kit.keychainKey(service, "check", create = false).status == KeychainStatus.NOT_FOUND
            val again = forgetKeysAfterReinstall(kit, service, noBackup)
            cleared && gone && !again
        }
        kit.keychainForget(service)
    }
}
