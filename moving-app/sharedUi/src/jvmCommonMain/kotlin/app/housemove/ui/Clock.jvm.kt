package app.housemove.ui

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Android and the desktop JVM: the java.time and String.format calls the screens made before they were shared.

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy")

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun isoToday(): String = LocalDate.now().toString()

actual fun localDayOf(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

actual fun clockAt(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(CLOCK)

actual fun clockIn(minutes: Long): String = LocalTime.now().plusMinutes(minutes).format(CLOCK)

actual fun dayLongText(day: Long): String = LocalDate.ofEpochDay(day).format(DAY_LONG)

actual fun monthYearText(day: Long): String = MONTH_YEAR.format(LocalDate.ofEpochDay(day))

actual fun decimals(v: Double, places: Int): String = String.format(Locale.ROOT, "%.${places}f", v)
