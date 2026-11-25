package app.housemove.data

import kotlin.concurrent.Volatile

/** The iPhone v1 has no update files, and no crypto of its own: its phone entries carry no key. */
internal actual val updateCrypto: UpdateCrypto? get() = updateCryptoStandIn

/**
 * Null in the app. Tests on the simulator that must make the bytes a JVM run makes (the golden syncs, whose hellos
 * carry the fixed phones' public keys) put in a stand-in that only reads and passes on key text, with no crypto.
 */
@Volatile
internal var updateCryptoStandIn: UpdateCrypto? = null
