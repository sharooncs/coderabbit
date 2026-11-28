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

/** A JCA key pair from the scalar [d]. Its public point is worked out with the reference code; the JCA checks it is on the curve. */
internal actual fun keyFromScalar(d: ByteArray): app.housemove.data.AgreementKey {
    val params = java.security.AlgorithmParameters.getInstance("EC")
        .apply { init(java.security.spec.ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(java.security.spec.ECParameterSpec::class.java)
    val f = java.security.KeyFactory.getInstance("EC")
    val private = f.generatePrivate(java.security.spec.ECPrivateKeySpec(java.math.BigInteger(1, d), params))
    val public = f.generatePublic(java.security.spec.X509EncodedKeySpec(app.housemove.data.P256Der.of(RefP256.publicOf(d))))
    return app.housemove.data.JcaAgreementKey(public, private)
}

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

/** The JVM has the update-file crypto (the JCA, Crypto.jvm.kt): nothing to stand in. */
internal actual fun useTestCryptoIfNone(): () -> Unit = {}
