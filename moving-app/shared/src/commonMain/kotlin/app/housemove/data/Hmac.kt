package app.housemove.data

/*
 * HMAC-SHA256 (RFC 2104), for syncing by itself on the same Wi-Fi (design/auto-sync.md): phones prove to each other
 * that they hold a key of the move without sending it. Written here once, on top of the platform's SHA-256 ([sha256]:
 * MessageDigest on Android and the JVM, CommonCrypto's CC_SHA256 on the iPhone), so every phone works out the same
 * bytes; HmacCommonTest checks it against RFC 4231's test cases on the JVM and on the iPhone simulator, and
 * HmacJvmTest against javax.crypto.Mac. It authenticates; it encrypts nothing.
 */

private const val BLOCK = 64

/** HMAC-SHA256 of [message] under [key]. */
internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val k = (if (key.size > BLOCK) sha256(key) else key).copyOf(BLOCK)
    val inner = ByteArray(BLOCK + message.size)
    for (i in 0 until BLOCK) inner[i] = (k[i].toInt() xor 0x36).toByte()
    message.copyInto(inner, BLOCK)
    val innerHash = sha256(inner)
    val outer = ByteArray(BLOCK + innerHash.size)
    for (i in 0 until BLOCK) outer[i] = (k[i].toInt() xor 0x5c).toByte()
    innerHash.copyInto(outer, BLOCK)
    return sha256(outer)
}

/** Are these the same bytes? Takes as long whatever they hold, so that how long it took says nothing of where they differ. */
internal fun sameBytes(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** [n] bytes from the platform's strong random source ([secureRandom]). */
internal fun strongRandomBytes(n: Int): ByteArray {
    val r = secureRandom()
    return ByteArray(n) { r.nextInt(256).toByte() }
}

/** The bytes [hex] stands for (two lower- or upper-case hex digits a byte), or null when it is not that. */
internal fun hexBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = hex[2 * i].digitToIntOrNull(16) ?: return null
        val lo = hex[2 * i + 1].digitToIntOrNull(16) ?: return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
