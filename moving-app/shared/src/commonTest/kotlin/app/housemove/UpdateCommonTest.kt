package app.housemove

import app.housemove.data.File
import app.housemove.data.Sealing
import app.housemove.data.SealedIn
import app.housemove.data.SealedOut
import app.housemove.data.Store
import app.housemove.data.Update
import app.housemove.data.base64Decode
import app.housemove.data.base64Encode
import app.housemove.data.mkdirs
import app.housemove.data.write
import app.housemove.data.platformCrypto
import app.housemove.data.sha256
import app.housemove.data.toHex
import app.housemove.data.toUtf8
import app.housemove.data.writeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Update files through the common code (Sealed.kt, Update.kt) on the platform's crypto: the JCA on the JVM, and on the
 * iPhone simulator the reference crypto (ReferenceCrypto.kt), since Kotlin tests there cannot call the app's CryptoKit.
 * The update-file parts of the golden fixtures, recorded on Android 2.1.1 (crypto.json, update.json), come out the same,
 * Android's update file opens on Asha's phone as it did there, and the sealing known-answer file (sealing/
 * update-sealed.json) is written byte for byte. The app's own CryptoKit runs the same checks in the simulator's UI tests
 * (CryptoCheck, iosMain).
 */
class UpdateCommonTest {
    private companion object {
        const val T_PRIYA = 1_767_312_000_000L
    }

    private fun golden(name: String): JsonObject = Json.parseToJsonElement(GoldenFiles.text.getValue(name)).jsonObject

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun <T> withCrypto(block: () -> T): T {
        val undo = useTestCryptoIfNone()
        try {
            return block()
        } finally {
            undo()
        }
    }

    /** Opens [bytes] on Asha's phone, started from her folder in golden/update.json with Priya's clock. */
    private fun openOnAsha(root: File, bytes: ByteArray): Update.Opened {
        val dir = File(root, "asha-" + bytes.size)
        dir.mkdirs()
        for ((name, text) in golden("update.json").obj("recipient").obj("files")) File(dir, name).writeText(text.jsonPrimitive.content)
        val s = Store(dir) { T_PRIYA }
        try {
            return Update.open(s, bytesIn(bytes))
        } finally {
            s.close()
        }
    }

    private fun assertOpenedAsRecorded(want: JsonObject, o: Update.Opened, what: String) {
        assertEquals(want.getValue("ok").jsonPrimitive.boolean, o.ok, "$what: ok (${o.message})")
        assertEquals(want.str("message"), o.message, "$what: message")
        assertEquals(want.str("fromName"), o.fromName, "$what: from")
        assertEquals(want.getValue("changes").jsonPrimitive.int, o.changes, "$what: changes")
        assertEquals(want.getValue("photos").jsonPrimitive.int, o.photos, "$what: photos")
    }

    @Test
    fun theUpdateFileBuildingBlocksAreAndroids() = withCrypto {
        val crypto = assertNotNull(platformCrypto)
        val f = golden("crypto.json")
        for (c in f.getValue("hkdf").jsonArray.map { it.jsonObject }) {
            assertEquals(c.str("okm32"), Sealing.hkdf(unhex(c.str("ikm")), unhex(c.str("salt")), unhex(c.str("info"))).toHex(), c.str("name"))
            assertEquals(c.str("okm16"), Sealing.hkdf(unhex(c.str("ikm")), unhex(c.str("salt")), unhex(c.str("info")), 16).toHex(), c.str("name"))
        }
        val chunks = f.obj("chunks")
        val key = unhex(chunks.str("key"))
        val prefix = unhex(chunks.str("prefix"))
        val headerHash = unhex(chunks.str("headerHash"))
        assertEquals(chunks.str("headerHash"), sha256("""{"format":1}""".toUtf8()).toHex())
        for (c in chunks.getValue("one").jsonArray.map { it.jsonObject }) {
            val index = c.getValue("index").jsonPrimitive.int
            val last = c.getValue("last").jsonPrimitive.boolean
            val sealed = Sealing.sealChunk(crypto, key, prefix, headerHash, index, last, unhex(c.str("plaintext")))
            assertEquals(c.str("sealed"), sealed.toHex(), "chunk $index, last $last")
            assertContentEquals(unhex(c.str("plaintext")), Sealing.openChunk(crypto, key, prefix, headerHash, index, last, sealed))
            assertNull(Sealing.openChunk(crypto, key, prefix, headerHash, index, !last, sealed), "bound to whether it is the last")
        }
        val stream = chunks.obj("stream")
        val plain = ByteArray(150_000) { (it % 251).toByte() }
        val out = BytesOut()
        SealedOut(crypto, out.stream, key, prefix, headerHash).stream.use { it.write(plain, 0, plain.size) }
        val sealed = out.bytes()
        assertEquals(stream.getValue("sealedLength").jsonPrimitive.int, sealed.size)
        assertEquals(stream.str("sha256"), sha256(sealed).toHex())
        val back = BytesOut()
        val reader = SealedIn(crypto, bytesIn(sealed), key, prefix, headerHash)
        val buf = ByteArray(10_000)
        while (true) {
            val n = reader.read(buf, 0, buf.size)
            if (n < 0) break
            back.stream.write(buf, 0, n)
        }
        assertContentEquals(plain, back.bytes())
    }

    @Test
    fun androidsUpdateFileOpensOnAshasPhone() = withCrypto {
        val crypto = assertNotNull(platformCrypto)
        val f = golden("update.json")
        val sample = unhex(f.obj("sample").str("hex"))
        val want = f.obj("intermediate")
        // Each value met on the way in, worked out by the common code from the sample and Asha's key.
        val headerLength = ((sample[12].toInt() and 0xff) shl 24) or ((sample[13].toInt() and 0xff) shl 16) or ((sample[14].toInt() and 0xff) shl 8) or (sample[15].toInt() and 0xff)
        val headerBytes = sample.copyOfRange(16, 16 + headerLength)
        assertEquals(want.str("header"), headerBytes.decodeToString())
        assertEquals(want.str("headerSha256"), sha256(headerBytes).toHex())
        val header = Json.parseToJsonElement(headerBytes.decodeToString()).jsonObject
        val to = header.getValue("to").jsonArray.single().jsonObject
        inTempFolder { root ->
            val keyFile = File(root, "phone-key.json")
            keyFile.writeText(f.obj("recipient").obj("files").str("phone-key.json"))
            val asha = crypto.phoneKeys(f.obj("recipient").str("phoneId"), keyFile)
            assertEquals(f.obj("recipient").str("publicKey"), asha.publicText, "Asha's public key, from her folder")
            val secret = assertNotNull(asha.agree(base64Decode(to.str("epk"))))
            assertEquals(want.str("ecdhSecret"), secret.toHex(), "ECDH")
            val kek = Sealing.hkdf(secret, base64Decode(to.str("salt")), "HouseMove update key 1|${header.str("moveId")}|${f.obj("recipient").str("phoneId")}".toUtf8())
            assertEquals(want.str("kek"), kek.toHex(), "HKDF")
            val w = Sealing.Wrapped(base64Decode(to.str("epk")), base64Decode(to.str("salt")), base64Decode(to.str("iv")), base64Decode(to.str("key")))
            val contentKey = assertNotNull(Sealing.unwrap(crypto, w, asha, f.obj("recipient").str("phoneId"), header.str("moveId")))
            assertEquals(want.str("contentKey"), contentKey.toHex(), "the content key")
            val prefix = base64Decode(header.str("nonce"))
            assertEquals(want.str("prefix"), prefix.toHex())
            for (c in want.getValue("chunks").jsonArray.map { it.jsonObject }) {
                val index = c.getValue("index").jsonPrimitive.int
                val last = c.getValue("last").jsonPrimitive.boolean
                assertEquals(c.str("nonce"), Sealing.chunkNonce(prefix, index).toHex())
                assertEquals(c.str("aad"), Sealing.chunkAad(sha256(headerBytes), index, last).toHex())
            }
            val rest = sample.copyOfRange(16 + headerLength, sample.size)
            val zip = BytesOut()
            val reader = SealedIn(crypto, bytesIn(rest), contentKey, prefix, sha256(headerBytes))
            val buf = ByteArray(4096)
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                zip.stream.write(buf, 0, n)
            }
            assertEquals(want.getValue("zipLength").jsonPrimitive.int, zip.bytes().size)
            assertEquals(want.str("zipSha256"), sha256(zip.bytes()).toHex())
            // And the whole file, through Update.open on Asha's phone.
            assertOpenedAsRecorded(f.obj("opened"), openOnAsha(root, sample), "Android's update")
        }
    }

    @Test
    fun theKnownAnswerFileIsSealedByteForByteAndOpens() = withCrypto {
        val crypto = assertNotNull(platformCrypto)
        val kat = SealingKat(Json.parseToJsonElement(SealingFiles.text.getValue("update-sealed.json")).jsonObject)
        assertEquals(SealingFiles.sha256.getValue("update-sealed.json"), sha256(SealingFiles.text.getValue("update-sealed.json").toUtf8()).toHex())
        val ephemeral = keyFromScalar(kat.ephemeralScalar)
        assertEquals(kat.json.str("ephemeralKey"), base64Encode(ephemeral.publicDer), "the one-use key's public half")
        assertEquals(kat.json.str("ecdhSecret"), assertNotNull(ephemeral.agree(kat.recipientKey)).toHex(), "ECDH")
        val file = kat.seal(crypto, ephemeral)
        assertEquals(kat.json.str("fileSha256"), sha256(file).toHex(), "the update file, byte for byte")
        assertEquals(kat.json.str("file"), file.toHex())
        inTempFolder { root -> assertOpenedAsRecorded(kat.json.obj("opened"), openOnAsha(root, file), "the known-answer file") }
    }

    /** The content key wrapped for [phoneId] in update file [bytes], opened with [keys], and what is sealed after the header. */
    private fun openFor(bytes: ByteArray, phoneId: String, keys: app.housemove.data.UpdateKeyPair): ByteArray? {
        val crypto = assertNotNull(platformCrypto)
        val headerLength = ((bytes[12].toInt() and 0xff) shl 24) or ((bytes[13].toInt() and 0xff) shl 16) or ((bytes[14].toInt() and 0xff) shl 8) or (bytes[15].toInt() and 0xff)
        val headerBytes = bytes.copyOfRange(16, 16 + headerLength)
        val header = Json.parseToJsonElement(headerBytes.decodeToString()).jsonObject
        val to = header.getValue("to").jsonArray.map { it.jsonObject }.single { it.str("phone") == phoneId }
        val w = Sealing.Wrapped(base64Decode(to.str("epk")), base64Decode(to.str("salt")), base64Decode(to.str("iv")), base64Decode(to.str("key")))
        val contentKey = Sealing.unwrap(crypto, w, keys, phoneId, header.str("moveId")) ?: return null
        val reader = SealedIn(crypto, bytesIn(bytes.copyOfRange(16 + headerLength, bytes.size)), contentKey, base64Decode(header.str("nonce")), sha256(headerBytes))
        val out = BytesOut()
        val buf = ByteArray(4096)
        return try {
            while (true) {
                val n = reader.read(buf, 0, buf.size)
                if (n < 0) break
                out.stream.write(buf, 0, n)
            }
            out.bytes()
        } catch (_: Exception) {
            null
        }
    }

    @Test
    fun anUpdateMadeHereOpensForThePhoneItIsFor() = withCrypto {
        inTempFolder { root ->
            // Asha's phone writes an update for every Organiser's and Decider's phone it knows: Priya's, whose key is on her entry.
            val f = golden("update.json")
            val dir = File(root, "asha")
            dir.mkdirs()
            for ((name, text) in f.obj("recipient").obj("files")) File(dir, name).writeText(text.jsonPrimitive.content)
            val asha = Store(dir) { T_PRIYA }
            val out = BytesOut()
            try {
                assertEquals(1, Update.write(asha, out.stream).phones)
            } finally {
                asha.close()
            }
            val bytes = out.bytes()
            val priyaId = f.obj("sender").str("phoneId")
            assertEquals(listOf(priyaId), Update.peek(bytesIn(bytes)).to)
            val keyFile = File(root, "priya-key.json")
            keyFile.writeText("""{"public":"${f.obj("sender").str("publicKey")}","private":"${f.obj("sender").str("privateKey")}","at":1}""")
            val priya = assertNotNull(platformCrypto).phoneKeys(priyaId, keyFile)
            assertEquals(f.obj("sender").str("publicKey"), priya.publicText)
            // Priya's key opens it: a zip whose data.json is Asha's data.
            val zip = assertNotNull(openFor(bytes, priyaId, priya), "Priya's key opens it")
            val entries = BytesOut().also { it.stream.write(zip, 0, zip.size) }.bytes()
            assertTrue(entries.size > 30 && entries[0] == 'P'.code.toByte() && entries[1] == 'K'.code.toByte(), "a zip inside")
            val reader = app.housemove.data.ZipReader(bytesIn(zip))
            assertEquals("data.json", reader.nextEntry()?.name)
            val data = reader.readBytes().decodeToString()
            assertTrue(data.contains("Maple Road"), "Asha's data")
            // A byte changed in the last chunk: it does not open.
            val spoilt = bytes.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
            assertNull(openFor(spoilt, priyaId, priya), "a changed byte")
            // Asha's own key is not Priya's: the content key does not unwrap for her ID with it.
            val ashaFile = File(root, "asha-key.json")
            ashaFile.writeText(f.obj("recipient").obj("files").str("phone-key.json"))
            assertNull(openFor(bytes, priyaId, assertNotNull(platformCrypto).phoneKeys("x", ashaFile)), "another phone's key")
        }
    }
}
