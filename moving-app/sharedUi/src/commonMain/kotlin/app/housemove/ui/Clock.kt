package app.housemove.ui

/*
 * The time and the few date and number formats the screens write themselves (the move's own dates are the shared
 * module's fmtDay and fmtDayShort). On Android and the desktop JVM each is the java.time or String.format call the
 * screens made before they were shared (jvmCommonMain); on the iPhone, Foundation's in the phone's language (iosMain).
 */

/** The time now, in milliseconds since 1970 (System.currentTimeMillis). */
expect fun nowMillis(): Long

/** Today in this phone's time zone as ISO text, "2026-11-26" (LocalDate.now().toString()), for file names. */
expect fun isoToday(): String

/** The epoch day [millis] falls on in this phone's time zone. */
expect fun localDayOf(millis: Long): Long

/** The time of day [millis] is, in this phone's time zone, as "14:05". */
expect fun clockAt(millis: Long): String

/** The time of day [minutes] from now, as "14:05" (LocalTime.now().plusMinutes). */
expect fun clockIn(minutes: Long): String

/** [day] (an epoch day) as "26 November 2026", in this phone's language. */
expect fun dayLongText(day: Long): String

/** [day] (an epoch day) as "Nov 2026", in this phone's language. */
expect fun monthYearText(day: Long): String

/** [v] with [places] decimals, "12.50" (String.format with Locale.ROOT, "%.2f"). */
expect fun decimals(v: Double, places: Int): String

/**
 * [text] for a part of a link, as android.net.Uri.encode writes it: letters, digits and _-!.~'()* as they are, everything
 * else as UTF-8 bytes, each as %XX in capitals. A lone surrogate is written as "?", as Java's UTF-8 encoder writes it.
 */
fun uriEncode(text: String): String {
    val out = StringBuilder(text.length)
    val hex = "0123456789ABCDEF"
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || "_-!.~'()*".indexOf(c) >= 0) {
            out.append(c)
            i++
            continue
        }
        val code: Int
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            code = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            i += 2
        } else {
            code = if (c.isSurrogate()) '?'.code else c.code
            i++
        }
        val bytes = when {
            code < 0x80 -> intArrayOf(code)
            code < 0x800 -> intArrayOf(0xC0 or (code shr 6), 0x80 or (code and 0x3F))
            code < 0x10000 -> intArrayOf(0xE0 or (code shr 12), 0x80 or ((code shr 6) and 0x3F), 0x80 or (code and 0x3F))
            else -> intArrayOf(0xF0 or (code shr 18), 0x80 or ((code shr 12) and 0x3F), 0x80 or ((code shr 6) and 0x3F), 0x80 or (code and 0x3F))
        }
        for (b in bytes) out.append('%').append(hex[b shr 4]).append(hex[b and 0xF])
    }
    return out.toString()
}
