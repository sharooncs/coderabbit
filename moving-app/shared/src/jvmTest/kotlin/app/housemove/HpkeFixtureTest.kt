package app.housemove

import app.housemove.data.JcaCrypto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * HouseMove's HPKE messages with fixed keys, src/jvmTest/resources/hpke/housemove.json ([HpkeKat]): made here with the
 * JCA, Android's crypto, and compared with what was recorded. HpkeCommonTest makes them again with the reference crypto on
 * the iPhone simulator, the iPhone app's crypto check with CryptoKit, and tools/hpke_check.py with an HPKE written apart.
 *
 * Record (only on purpose, when the format changes): `HPKE_RECORD=true tools/test.sh --tests app.housemove.HpkeFixtureTest`.
 */
class HpkeFixtureTest {
    private companion object {
        val RECORD = System.getenv("HPKE_RECORD") == "true"

        val SOURCE: File? = listOf(".", "shared", "moving-app/shared").map { File(it).absoluteFile }
            .firstOrNull { File(it, "src/jvmTest/kotlin/app/housemove").isDirectory }?.let { File(it, "src/jvmTest/resources") }
    }

    @Test
    fun theMessagesAreTheRecordedOnes() {
        val made = NoiseKat.PRETTY.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), HpkeKat.make(JcaCrypto, ::keyFromScalar)) + "\n"
        val again = NoiseKat.PRETTY.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), HpkeKat.make(JcaCrypto, ::keyFromScalar)) + "\n"
        assertEquals("nothing in it is random", made, again)
        if (RECORD) {
            val dir = File(SOURCE ?: error("the source folder was not found"), "hpke").apply { mkdirs() }
            File(dir, "housemove.json").writeText(made)
            return
        }
        val recorded = javaClass.getResource("/hpke/housemove.json")?.readText() ?: error("hpke/housemove.json is missing: record it")
        assertEquals(recorded, made)
    }
}
