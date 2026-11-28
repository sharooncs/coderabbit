package app.housemove.data

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

// Android and the JVM: what the code called before it was shared.

internal actual fun currentTimeMillis(): Long = System.currentTimeMillis()

internal actual fun localToday(): Long = LocalDate.now().toEpochDay()

private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val SHORT_FMT = DateTimeFormatter.ofPattern("d MMM")

internal actual fun dayText(day: Long): String = LocalDate.ofEpochDay(day).format(DAY_FMT)

internal actual fun dayShortText(day: Long): String = LocalDate.ofEpochDay(day).format(SHORT_FMT)

internal actual fun isoDate(day: Long): String = LocalDate.ofEpochDay(day).toString()

internal actual fun parseIsoDate(text: String): Long = LocalDate.parse(text).toEpochDay()

internal actual fun randomUuid(): String = UUID.randomUUID().toString()

internal actual fun mathRandom(): Double = Math.random()

internal actual fun secureRandom(): RandomInts = object : RandomInts {
    private val random = SecureRandom()

    override fun nextInt(bound: Int): Int = random.nextInt(bound)
}

internal actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal actual fun String.toUtf8(): ByteArray = toByteArray()

internal actual fun ByteArray.decodeUtf8(): String = decodeToString()

internal actual fun urlEncode(text: String): String = URLEncoder.encode(text, "UTF-8")

internal actual fun urlDecode(text: String): String = URLDecoder.decode(text, "UTF-8")

internal actual fun Double.javaText(): String = toString()
