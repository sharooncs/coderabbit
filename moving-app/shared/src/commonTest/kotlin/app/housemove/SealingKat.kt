package app.housemove

import app.housemove.data.AgreementKey
import app.housemove.data.PlatformCrypto
import app.housemove.data.Update
import app.housemove.data.base64Decode
import app.housemove.data.base64Encode
import app.housemove.data.write
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The sealing known-answer file (src/jvmTest/resources/sealing/update-sealed.json, recorded by SealingFixtureTest): an
 * update file sealed with fixed keys and fixed random bytes, so every phone must write it byte for byte. Its contents are
 * the inner zip of Android's update file in golden/update.json, from Priya's phone to Asha's. The JVM (the JCA), the
 * reference crypto on the simulator, and the iPhone app's CryptoKit (CryptoCheck.kt, which repeats [seal] in iosMain) make
 * it through the same code Update.write uses: [Update.wrappedFor] and [Update.writeSealed].
 */
internal class SealingKat(val json: JsonObject) {
    private val inputs = json.getValue("inputs").jsonObject

    private fun str(key: String) = inputs.getValue(key).jsonPrimitive.content
    private fun hexOf(key: String) = unhexKat(str(key))

    val moveId = str("moveId")
    val from = str("from")
    val at = inputs.getValue("at").jsonPrimitive.long
    val schema = inputs.getValue("schema").jsonPrimitive.int
    val recipient = str("recipient")
    val recipientKey: ByteArray = base64Decode(str("recipientKey"))
    val contentKey = hexOf("contentKey")
    val prefix = hexOf("prefix")
    val ephemeralScalar = hexOf("ephemeralScalar")
    val salt = hexOf("salt")
    val iv = hexOf("iv")
    val payload = hexOf("payload")

    /** The update file these inputs make, with [ephemeral] (the key pair of [ephemeralScalar]) and [crypto]. */
    fun seal(crypto: PlatformCrypto, ephemeral: AgreementKey): ByteArray {
        val to = Update.wrappedFor(crypto, contentKey, recipientKey, recipient, moveId, ephemeral, salt, iv)
        val header = Update.Header(schema = schema, moveId = moveId, from = from, at = at, nonce = base64Encode(prefix), to = listOf(to))
        val out = BytesOut()
        Update.writeSealed(crypto, out.stream, header, contentKey, prefix) {
            it.write(payload, 0, payload.size)
            it.close()
        }
        return out.bytes()
    }

    companion object {
        fun unhexKat(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
