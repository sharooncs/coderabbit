@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarIdentifierGregorian
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.localeIdentifier
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneForSecondsFromGMT
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import kotlin.math.floor

// The iPhone: Apple's clock, calendar, randomness and SHA-256 (CommonCrypto's CC_SHA256 is a digest, not encryption);
// text as the JVM writes it from the common rules in JavaText.kt.

internal actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

internal actual fun localToday(): Long {
    val now = NSDate()
    val seconds = floor(now.timeIntervalSince1970).toLong() + NSTimeZone.localTimeZone.secondsFromGMTForDate(now)
    return seconds.floorDiv(86_400L)
}

/**
 * As java.time formats on Android: names in the phone's language, but the Gregorian calendar and ASCII digits whatever
 * the language (DateTimeFormatter.ofPattern uses the ISO calendar and DecimalStyle.STANDARD).
 */
private fun dayFormatter(pattern: String): NSDateFormatter = NSDateFormatter().apply {
    val id = NSLocale.currentLocale.localeIdentifier
    locale = NSLocale(localeIdentifier = if ('@' in id) "$id;numbers=latn" else "$id@numbers=latn")
    calendar = NSCalendar(calendarIdentifier = NSCalendarIdentifierGregorian)
    timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    dateFormat = pattern
}

private val DAY_FMT by lazy { dayFormatter("EEE d MMM yyyy") }
private val SHORT_FMT by lazy { dayFormatter("d MMM") }

private fun dateOf(day: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(day * 86_400.0)

internal actual fun dayText(day: Long): String = DAY_FMT.stringFromDate(dateOf(day))

internal actual fun dayShortText(day: Long): String = SHORT_FMT.stringFromDate(dateOf(day))

internal actual fun isoDate(day: Long): String = JavaText.isoDate(day)

internal actual fun parseIsoDate(text: String): Long = JavaText.parseIsoDate(text)

internal actual fun randomUuid(): String = NSUUID().UUIDString().lowercase()

internal actual fun mathRandom(): Double = kotlin.random.Random.nextDouble()

/** Apple's strong randomness (SecRandomCopyBytes), drawn on as java.util.Random.nextInt(bound) draws on its bits. */
private class AppleRandom : RandomInts {
    private fun next31(): Int {
        val b = ByteArray(4)
        val status = b.usePinned { SecRandomCopyBytes(kSecRandomDefault, 4.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "no random bytes ($status)" }
        val v = ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        return v ushr 1
    }

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        var r = next31()
        val m = bound - 1
        if (bound and m == 0) return ((bound.toLong() * r.toLong()) shr 31).toInt()
        var u = r
        while (true) {
            r = u % bound
            if (u - r + m >= 0) return r
            u = next31()
        }
    }
}

internal actual fun secureRandom(): RandomInts = AppleRandom()

internal actual fun sha256(bytes: ByteArray): ByteArray {
    val out = ByteArray(CC_SHA256_DIGEST_LENGTH)
    out.usePinned { o ->
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, o.addressOf(0).reinterpret())
        } else {
            bytes.usePinned { i -> CC_SHA256(i.addressOf(0), bytes.size.convert(), o.addressOf(0).reinterpret()) }
        }
    }
    return out
}

internal actual fun String.toUtf8(): ByteArray = JavaText.utf8(this)

internal actual fun ByteArray.decodeUtf8(): String = JavaText.decodeUtf8(this)

internal actual fun urlEncode(text: String): String = JavaText.urlEncode(text)

internal actual fun urlDecode(text: String): String = JavaText.urlDecode(text)

internal actual fun Double.javaText(): String = JavaText.doubleText(this)
