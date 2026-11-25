package app.housemove.data

/*
 * Numbers that are typed or said. Every number field (ui/Common.kt's NumberInput) and every spoken size reads its
 * text here, so there is one reading of a number for the whole app, on Android and on the iPhone.
 *
 * The reading is the one 2.1.1 made: a comma counts as the decimal point, the text is trimmed, and the rest is
 * Kotlin's toDoubleOrNull. So signs, exponents, "5." and ".5" mean what they meant. What is new is what is refused:
 * NaN, the infinities (typed, or a number too long for a Double, such as 1e309), and a number beyond the bounds of
 * its kind of field. kotlinx.serialization will not write NaN or an infinity, so in 2.1.1 one of them in the data
 * stopped every later save, and the change was lost when the app was opened again.
 */

/**
 * What a number field holds, and the numbers it takes. The bounds are far beyond any real move, and only keep out
 * what cannot be meant. A field still decides itself what zero or less means, as before: the money pickers and the
 * sizes read it as "not set", and the load space ignores it.
 */
enum class NumberKind(val min: Double, val max: Double) {
    /** A sum of money: a cost, an asking price, what a thing is worth or sold for, an amount to collect. Up to 10^15, where whole sums are still exact. */
    MONEY(-1e15, 1e15),

    /** A length in cm or inches: an item's width, depth or height, a door's width. Up to 10,000 (100 m in cm). */
    LENGTH(-10_000.0, 10_000.0),

    /** A load space in m³. Up to 10,000. */
    VOLUME(-10_000.0, 10_000.0),

    /** A count or a label number: never below 0, and up to a million. */
    COUNT(0.0, 1_000_000.0),
}

/** What a number field makes of its text. */
sealed class TypedNumber {
    /** Nothing typed, or only spaces: the field is empty. */
    data object Empty : TypedNumber()

    data class Number(val value: Double) : TypedNumber()

    /** Not a number this kind of field takes, with the few words a field shows under it. */
    data class Refused(val reason: String) : TypedNumber()

    /** The number, or null for an empty field and for text that was refused, as number fields have always passed it on. */
    val valueOrNull: Double? get() = (this as? Number)?.value

    /** Why the text was refused, or null when it was not. */
    val refusal: String? get() = (this as? Refused)?.reason
}

/** The words a number field shows under text that is not a number: anything else, "NaN" and "Infinity" among them. */
const val NOT_A_NUMBER = "Not a number"

/** The words for a number beyond what its kind of field takes, either way from zero; and for 1e309, which no Double holds. */
const val TOO_BIG = "That number is too big"

/** The words for a number below zero in a field that never is (a count). */
const val TOO_SMALL = "That number is too small"

/** [text], typed or pasted into a number field of [kind]. */
fun readNumber(text: String, kind: NumberKind): TypedNumber {
    val t = text.replace(',', '.').trim()
    if (t.isEmpty()) return TypedNumber.Empty
    val v = t.toDoubleOrNull() ?: return TypedNumber.Refused(NOT_A_NUMBER)
    return when {
        // "NaN" and "Infinity" are words toDoubleOrNull knows; a number too long for a Double comes out as an infinity too.
        v.isNaN() || t.contains("Infinity") -> TypedNumber.Refused(NOT_A_NUMBER)
        v.isInfinite() || v > kind.max || v < -kind.max -> TypedNumber.Refused(TOO_BIG)
        v < kind.min -> TypedNumber.Refused(TOO_SMALL)
        else -> TypedNumber.Number(v)
    }
}

private val SPOKEN_NUMBER = Regex("\\d+(?:[.,]\\d+)?")

/**
 * The numbers in what was said, in order, each read as [kind]: "200 by 90 by 85" gives 200, 90 and 85. A decimal
 * comma counts as a point. A number that is refused keeps its place, so the ones after it stay where they were said.
 */
fun spokenNumbers(said: String, kind: NumberKind): List<TypedNumber> =
    SPOKEN_NUMBER.findAll(said).map { readNumber(it.value, kind) }.toList()
