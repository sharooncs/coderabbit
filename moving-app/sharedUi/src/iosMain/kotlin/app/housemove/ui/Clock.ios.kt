package app.housemove.ui

import platform.Foundation.NSCalendar
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

// The iPhone: Foundation's clock, calendar and formats, in the phone's own language and time zone.

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

private fun dateOf(millis: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(millis / 1000.0)

/** Noon of [day] (an epoch day) in this phone's time zone: a time that is on that day whatever the clocks did. */
private fun noonOf(day: Long): NSDate {
    // The calendar date of the epoch day (the proleptic Gregorian calendar, as java.time's LocalDate.ofEpochDay).
    val z = day + 719_468
    val era = (if (z >= 0) z else z - 146_096) / 146_097
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val m = if (mp < 10) mp + 3 else mp - 9
    val local = NSDateComponents().apply {
        year = yoe + era * 400 + if (m <= 2) 1 else 0
        month = m
        this.day = doy - (153 * mp + 2) / 5 + 1
        hour = 12
    }
    val gregorian = NSCalendar(calendarIdentifier = platform.Foundation.NSCalendarIdentifierGregorian)
    gregorian.timeZone = NSTimeZone.localTimeZone
    return gregorian.dateFromComponents(local) ?: NSDate.dateWithTimeIntervalSince1970(day * 86_400.0 + 43_200.0)
}

private fun format(pattern: String, date: NSDate, template: Boolean = false): String {
    val f = NSDateFormatter()
    f.locale = NSLocale.currentLocale
    f.timeZone = NSTimeZone.localTimeZone
    if (template) f.setLocalizedDateFormatFromTemplate(pattern) else f.dateFormat = pattern
    return f.stringFromDate(date)
}

actual fun isoToday(): String {
    val f = NSDateFormatter()
    f.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    f.timeZone = NSTimeZone.localTimeZone
    f.dateFormat = "yyyy-MM-dd"
    return f.stringFromDate(NSDate())
}

actual fun localDayOf(millis: Long): Long {
    val offset = NSTimeZone.localTimeZone.secondsFromGMTForDate(dateOf(millis))
    return floor((millis / 1000.0 + offset) / 86_400.0).toLong()
}

actual fun clockAt(millis: Long): String = format("HH:mm", dateOf(millis))

actual fun clockIn(minutes: Long): String = clockAt(nowMillis() + minutes * 60_000L)

actual fun dayLongText(day: Long): String = format("d MMMM yyyy", noonOf(day))

actual fun monthYearText(day: Long): String = format("MMM yyyy", noonOf(day))

actual fun decimals(v: Double, places: Int): String {
    if (v.isNaN()) return "NaN"
    if (v.isInfinite()) return if (v > 0) "Infinity" else "-Infinity"
    val scale = 10.0.pow(places)
    // Half away from zero, as Java's HALF_UP does.
    val n = floor(abs(v) * scale + 0.5).toLong()
    val whole = n / scale.toLong()
    val frac = (n % scale.toLong()).toString().padStart(places, '0')
    val sign = if (v < 0 && n != 0L) "-" else ""
    return if (places == 0) "$sign$whole" else "$sign$whole.$frac"
}
