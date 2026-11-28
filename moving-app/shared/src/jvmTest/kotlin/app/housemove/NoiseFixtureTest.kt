package app.housemove

import app.housemove.data.JcaCrypto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The encrypted sync's known answers, src/jvmTest/resources/noise/handshakes.json ([NoiseKat]): made here with the JCA,
 * Android's crypto, and compared with what was recorded. The common test (NoiseCommonTest) makes them again with the
 * reference crypto on the iPhone simulator, and the iPhone app's crypto check with CryptoKit; tools/noise_check.py checks
 * the file with an implementation written apart. So a phone of either kind writes, byte for byte, what the other reads.
 *
 * Record (only on purpose, when the protocol changes): `NOISE_RECORD=true tools/test.sh --tests app.housemove.NoiseFixtureTest`.
 */
class NoiseFixtureTest {
    private companion object {
        val RECORD = System.getenv("NOISE_RECORD") == "true" || System.getProperty("noise.record") == "true"

        val SOURCE: File? = listOf(".", "shared", "moving-app/shared").map { File(it).absoluteFile }
            .firstOrNull { File(it, "src/jvmTest/kotlin/app/housemove").isDirectory }?.let { File(it, "src/jvmTest/resources") }
    }

    @Test
    fun theTranscriptsAreTheRecordedOnes() {
        val made = NoiseKat.PRETTY.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), NoiseKat.make(JcaCrypto, ::keyFromScalar)) + "\n"
        // Made twice, the same: nothing in it is random.
        val again = NoiseKat.PRETTY.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), NoiseKat.make(JcaCrypto, ::keyFromScalar)) + "\n"
        assertEquals(made, again)
        if (RECORD) {
            val dir = File(SOURCE ?: error("the source folder was not found"), "noise").apply { mkdirs() }
            File(dir, "handshakes.json").writeText(made)
            return
        }
        val recorded = javaClass.getResource("/noise/handshakes.json")?.readText() ?: error("noise/handshakes.json is missing: record it")
        assertEquals(recorded, made)
    }
}
