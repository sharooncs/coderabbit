package app.housemove

import app.housemove.data.File
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.data.idSource
import app.housemove.sync.ServerSocket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.UUID

// The JVM side of the common tests' needs (TestPlatform.kt).

internal actual fun newTempFolder(): File = java.nio.file.Files.createTempDirectory("hm-test").toFile()

/** Inherited, so the threads a run starts (a sync host's) take their ids from the same counter, and no other thread does. */
private val runIds = InheritableThreadLocal<(() -> String)?>()

internal actual fun <T> withIds(next: () -> String, block: () -> T): T {
    val before = idSource
    idSource = { runIds.get()?.invoke() ?: UUID.randomUUID().toString() }
    runIds.set(next)
    try {
        return block()
    } finally {
        runIds.remove()
        idSource = before
    }
}

internal actual fun loopbackServer(): ServerSocket = java.net.ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

internal actual fun bytesIn(b: ByteArray): InputStream = ByteArrayInputStream(b)

internal actual class BytesOut actual constructor() {
    private val out = ByteArrayOutputStream()
    actual val stream: OutputStream get() = out

    actual fun bytes(): ByteArray = out.toByteArray()
}

/** The JVM has the update-file crypto (Keys.kt): nothing to stand in. */
internal actual fun useKeyTextIfNoCrypto(): () -> Unit = {}
