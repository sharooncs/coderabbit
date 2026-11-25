package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.CountDownLatch
import app.housemove.data.File
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.data.UpdateCrypto
import app.housemove.data.UpdateKeyPair
import app.housemove.data.awaitMillis
import app.housemove.data.deleteRecursively
import app.housemove.data.isFile
import app.housemove.data.readText
import app.housemove.sync.ServerSocket
import kotlinx.serialization.Serializable

/*
 * What the common tests need of the platform they run on (jvmTest and iosTest give it): temporary folders, bytes in
 * memory as streams, a listening socket on 127.0.0.1 only, and ids from a counter for one run's threads.
 */

/** A new, empty folder for one test, in the platform's temporary folder. */
internal expect fun newTempFolder(): File

/** Runs [block] with newId() giving [next]'s ids, on this thread and on every thread started meanwhile. */
internal expect fun <T> withIds(next: () -> String, block: () -> T): T

/** A socket listening on 127.0.0.1 only, on a free port. */
internal expect fun loopbackServer(): ServerSocket

/** [b] as an input stream. */
internal expect fun bytesIn(b: ByteArray): InputStream

/** An output stream into memory. */
internal expect class BytesOut() {
    val stream: OutputStream

    fun bytes(): ByteArray
}

/**
 * On a platform without the update-file crypto (the iPhone), puts in [KeyTextOnly] for this run, so its phones say the
 * fixed public keys of their folders as JVM phones do. Elsewhere does nothing. Undone by the function it returns.
 */
internal expect fun useKeyTextIfNoCrypto(): () -> Unit

/** Waits [ms] milliseconds. */
internal fun sleepMillis(ms: Long) {
    CountDownLatch(1).awaitMillis(ms)
}

/** A folder for [block], deleted afterwards. */
internal inline fun inTempFolder(block: (File) -> Unit) {
    val dir = newTempFolder()
    try {
        block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

/**
 * A stand-in for the update-file crypto with none in it, for tests only: a phone's key pair is the public key text in
 * its key file, as it is; another phone's key text is taken when it has the shape of an EC P-256 public key in X.509
 * and Base64. Nothing is decoded, checked or worked out.
 */
internal object KeyTextOnly : UpdateCrypto {
    @Serializable
    private data class Saved(val public: String = "", val private: String = "", val at: Long = 0)

    private class Pair(override val publicText: String, override val madeAt: Long) : UpdateKeyPair

    override fun keysIn(file: File?): UpdateKeyPair {
        val saved = file?.takeIf { it.isFile }?.let { AppJson.decodeFromString(Saved.serializer(), it.readText()) } ?: Saved()
        return Pair(saved.public, saved.at)
    }

    override fun isPublicKey(text: String): Boolean = text.length == 124 && text.startsWith("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE")
}
