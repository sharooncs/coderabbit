package app.housemove

import app.housemove.data.JcaCrypto
import app.housemove.data.PhoneKeys
import app.housemove.data.SCHEMA
import app.housemove.data.Sealing
import app.housemove.data.SealedIn
import app.housemove.data.Store
import app.housemove.data.Update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * The sealing known-answer file, src/jvmTest/resources/sealing/update-sealed.json: Android's update from golden/update.json
 * (Priya's phone to Asha's) sealed again with fixed keys and fixed random bytes, worked out from labels, so that it comes
 * out the same byte for byte on every phone. This test makes it with the JCA (Android's crypto) through the code Update.write
 * uses, and checks it is what was recorded and that the JVM opens it on Asha's phone as it opens Android's own. The common
 * test (UpdateCommonTest) makes it again with the reference crypto on the simulator, and the iPhone app's crypto check
 * with CryptoKit: so an update the iPhone writes is one the JVM, and Android, open.
 *
 * Record (only on purpose, if the inputs change): `SEALING_RECORD=true tools/test.sh --tests app.housemove.SealingFixtureTest`.
 * The golden fixtures are not touched.
 */
class SealingFixtureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private companion object {
        val RECORD = System.getenv("SEALING_RECORD") == "true" || System.getProperty("sealing.record") == "true"

        val SOURCE: File? = listOf(".", "shared", "moving-app/shared").map { File(it).absoluteFile }
            .firstOrNull { File(it, "src/jvmTest/kotlin/app/housemove").isDirectory }?.let { File(it, "src/jvmTest/resources") }

        val PRETTY = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** The recorded update file is opened on Asha's phone with Priya's clock, as golden/update.json was. */
        const val T_PRIYA = 1_767_312_000_000L

        /** A day after the fixed move's last step. */
        const val AT = T_PRIYA + 86_400_000L
    }

    private fun resource(path: String): String =
        SOURCE?.let { File(it, path) }?.takeIf { it.isFile }?.readText()
            ?: javaClass.classLoader.getResource(path)?.readText()
            ?: throw AssertionError("$path is missing")

    private fun hex(b: ByteArray): String = b.joinToString("") { ((it.toInt() and 0xff) + 0x100).toString(16).substring(1) }
    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun sha(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))
    private fun label(what: String, n: Int): ByteArray = MessageDigest.getInstance("SHA-256").digest("HouseMove sealing check: $what".toByteArray()).copyOf(n)

    /** The inner zip of Android's update in golden/update.json, opened with Asha's key (the JCA). */
    private fun androidPayload(update: JsonObject): ByteArray {
        val recipient = update.getValue("recipient").jsonObject
        val keyFile = recipient.getValue("files").jsonObject.getValue("phone-key.json").jsonPrimitive.content
        val dir = tmp.newFolder()
        File(dir, "phone-key.json").writeText(keyFile)
        val asha = PhoneKeys.inFile(File(dir, "phone-key.json"))
        val b = unhex(update.getValue("sample").jsonObject.getValue("hex").jsonPrimitive.content)
        val d = DataInputStream(ByteArrayInputStream(b))
        d.readFully(ByteArray(8))
        d.readInt()
        val headerBytes = ByteArray(d.readInt()).also { d.readFully(it) }
        val header = Json.parseToJsonElement(headerBytes.decodeToString()).jsonObject
        val to = header.getValue("to").jsonArray.single().jsonObject
        fun b64(k: String) = Base64.getDecoder().decode(to.getValue(k).jsonPrimitive.content)
        val moveId = header.getValue("moveId").jsonPrimitive.content
        val w = Sealing.Wrapped(b64("epk"), b64("salt"), b64("iv"), b64("key"))
        val key = Sealing.unwrap(JcaCrypto, w, asha, recipient.getValue("phoneId").jsonPrimitive.content, moveId)!!
        val prefix = Base64.getDecoder().decode(header.getValue("nonce").jsonPrimitive.content)
        return SealedIn(JcaCrypto, d, key, prefix, Sealing.sha256(headerBytes)).stream.readBytes()
    }

    /** What opening [bytes] on Asha's phone, started from her folder in golden/update.json, says. */
    private fun opened(update: JsonObject, bytes: ByteArray): Update.Opened {
        val dir = tmp.newFolder()
        val files = update.getValue("recipient").jsonObject.getValue("files").jsonObject
        for ((name, text) in files) File(dir, name).writeText(text.jsonPrimitive.content)
        return GoldenIds.using(GoldenIds()) {
            val s = Store(dir) { T_PRIYA }
            try {
                Update.open(s, ByteArrayInputStream(bytes))
            } finally {
                s.close()
            }
        }
    }

    private fun fixture(): JsonObject {
        val update = Json.parseToJsonElement(resource("golden/update.json")).jsonObject
        val recipient = update.getValue("recipient").jsonObject
        val payload = androidPayload(update)
        assertEquals("the inner zip of Android's update", update.getValue("intermediate").jsonObject.getValue("zipSha256").jsonPrimitive.content, sha(payload))
        val inputs = buildJsonObject {
            put("moveId", recipient.getValue("moveId").jsonPrimitive.content)
            put("from", update.getValue("sender").jsonObject.getValue("phoneId").jsonPrimitive.content)
            put("at", AT)
            put("schema", SCHEMA)
            put("recipient", recipient.getValue("phoneId").jsonPrimitive.content)
            put("recipientKey", recipient.getValue("publicKey").jsonPrimitive.content)
            put("contentKey", hex(label("content key", 32)))
            put("prefix", hex(label("nonce prefix", 8)))
            put("ephemeralScalar", hex(label("ephemeral key", 32)))
            put("salt", hex(label("salt", 16)))
            put("iv", hex(label("iv", 12)))
            put("payload", hex(payload))
        }
        val kat = SealingKat(buildJsonObject { put("inputs", inputs) })
        val ephemeral = keyFromScalar(kat.ephemeralScalar)
        val file = kat.seal(JcaCrypto, ephemeral)
        val secret = ephemeral.agree(kat.recipientKey)!!
        val kek = Sealing.hkdf(secret, kat.salt, "HouseMove update key 1|${kat.moveId}|${kat.recipient}".toByteArray())
        val headerBytes = file.copyOfRange(16, 16 + DataInputStream(ByteArrayInputStream(file, 12, 4)).readInt())
        val o = opened(update, file)
        assertTrue("the JVM opens it on Asha's phone: ${o.message}", o.ok)
        return buildJsonObject {
            put(
                "about",
                "Android's update file in golden/update.json (Priya's phone to Asha's), its inner zip sealed again with fixed keys " +
                    "and fixed random bytes (SealingFixtureTest; each the first bytes of SHA-256 of a label), through the code Update.write " +
                    "uses. Every phone must write 'file' byte for byte from 'inputs': the JVM with the JCA, the iPhone simulator with " +
                    "the reference crypto (UpdateCommonTest) and the iPhone app with CryptoKit (CryptoCheck). 'opened' is what the JVM " +
                    "says when it opens it on Asha's phone, started from her folder in golden/update.json.",
            )
            put("inputs", inputs)
            put("ephemeralKey", Base64.getEncoder().encodeToString(ephemeral.publicDer))
            put("ecdhSecret", hex(secret))
            put("kek", hex(kek))
            put("header", headerBytes.decodeToString())
            put("file", hex(file))
            put("fileSha256", sha(file))
            putJsonObject("opened") {
                put("ok", o.ok)
                put("message", o.message)
                put("fromName", o.fromName)
                put("changes", o.changes)
                put("photos", o.photos)
            }
        }
    }

    private fun render(e: JsonElement): String = PRETTY.encodeToString(JsonElement.serializer(), e) + "\n"

    @Test
    fun theJvmSealsTheKnownAnswerFileAndOpensIt() {
        val made = render(fixture())
        if (RECORD) {
            val target = File(SOURCE ?: throw AssertionError("no source folder to record into"), "sealing/update-sealed.json")
            target.parentFile.mkdirs()
            target.writeText(made)
            return
        }
        assertEquals("sealing/update-sealed.json is what the JVM makes today", resource("sealing/update-sealed.json"), made)
    }
}
