package app.housemove.data

/*
 * The small things the shared code asks of the phone (step 1 of design/ios-plan.md): the time, today's date, random
 * numbers and ids, SHA-256, and text as the JVM writes it.
 *
 * On Android and the JVM each is the call the code made before it was shared (Platform.jvm.kt). On the iPhone
 * (Platform.ios.kt) the clock, the dates, randomness and SHA-256 are Apple's own, and the text rules are the common
 * Java-compatible ones in JavaText.kt, which the golden fixtures check on the JVM and on the iPhone simulator.
 */

/** The time now, in milliseconds since 1970 (System.currentTimeMillis). */
internal expect fun currentTimeMillis(): Long

/** Today in this phone's time zone, as an epoch day (LocalDate.now().toEpochDay()). */
internal expect fun localToday(): Long

/** [day] (an epoch day) as "Tue 1 Jun 2026", in this phone's language. */
internal expect fun dayText(day: Long): String

/** [day] (an epoch day) as "1 Jun", in this phone's language. */
internal expect fun dayShortText(day: Long): String

/** [day] (an epoch day) as ISO text, "2026-06-01" (LocalDate.toString). */
internal expect fun isoDate(day: Long): String

/** The epoch day of ISO text such as "2026-06-01" (LocalDate.parse); throws when it is not a real day. */
internal expect fun parseIsoDate(text: String): Long

/** A random UUID as lower-case text (UUID.randomUUID().toString()). */
internal expect fun randomUuid(): String

/** A random number from 0 (included) to 1 (excluded), for choices that need no secrecy (Math.random). */
internal expect fun mathRandom(): Double

/** Random whole numbers from a cryptographically strong source (java.security.SecureRandom on Android and the JVM). */
internal interface RandomInts {
    /** A number from 0 to [bound] - 1, each as likely (Random.nextInt(bound)). */
    fun nextInt(bound: Int): Int
}

/** A new strong random source. */
internal expect fun secureRandom(): RandomInts

/** The SHA-256 of [bytes]. A digest only: nothing is encrypted with it. */
internal expect fun sha256(bytes: ByteArray): ByteArray

/** UTF-8 as the JVM writes it (String.toByteArray()): a lone surrogate becomes '?'. */
internal expect fun String.toUtf8(): ByteArray

/** UTF-8 decoded as the JVM decodes it (ByteArray.decodeToString()): what is not UTF-8 becomes U+FFFD, in the JVM's pieces. */
internal expect fun ByteArray.decodeUtf8(): String

/** java.net.URLEncoder.encode(text, "UTF-8"). */
internal expect fun urlEncode(text: String): String

/** java.net.URLDecoder.decode(text, "UTF-8"); throws IllegalArgumentException on a bad '%' escape. */
internal expect fun urlDecode(text: String): String

/** This Double as the JVM's Double.toString writes it ("1.0E-4", "12.5", "-0.0", "NaN"). */
internal expect fun Double.javaText(): String

/** Lower-case hex, two digits a byte (as "%02x" formats each byte). */
internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        out.append(digits[v shr 4]).append(digits[v and 0xf])
    }
    return out.toString()
}
