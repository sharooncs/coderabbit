package app.housemove

import app.housemove.data.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one reading of typed and spoken numbers (data/Numbers.kt), on the JVM and on the iPhone simulator. In 2.1.1 a
 * number field took "NaN", "Infinity" and a 310-digit number (an infinity), after which nothing could be saved.
 */
class NumbersCommonTest {
    private val kinds = NumberKind.entries

    /** What 2.1.1's number fields made of text. */
    private fun before(text: String): Double? = text.replace(',', '.').trim().toDoubleOrNull()

    private fun assertNumber(expected: Double, text: String, kind: NumberKind) {
        val read = readNumber(text, kind)
        assertTrue(read is TypedNumber.Number, "'$text' as $kind: $read")
        // The same bits: -0.0 stays -0.0.
        assertEquals(expected.toRawBits(), read.value.toRawBits(), "'$text' as $kind")
        assertNull(read.refusal)
    }

    private fun assertRefused(reason: String, text: String, kind: NumberKind) {
        val read = readNumber(text, kind)
        assertEquals(TypedNumber.Refused(reason), read, "'$text' as $kind")
        assertNull(read.valueOrNull, "a refused number goes on as null, as text that is not a number always has")
    }

    @Test
    fun nanAndTheInfinitiesAreNotANumberInAnyField() {
        for (k in kinds) for (t in listOf("NaN", "nan", "Infinity", "-Infinity", "+Infinity", " NaN ", "NaNd", "Infinityf")) {
            // "nan" was never read as a number; the others were, by toDoubleOrNull.
            assertRefused(NOT_A_NUMBER, t, k)
        }
    }

    @Test
    fun aNumberTooLongForADoubleIsTooBig() {
        val digits310 = "1" + "0".repeat(309)
        assertEquals(Double.POSITIVE_INFINITY, before(digits310), "2.1.1 took it as an infinity")
        for (k in kinds) {
            assertRefused(TOO_BIG, "1e309", k)
            assertRefused(TOO_BIG, digits310, k)
            assertRefused(TOO_BIG, "-$digits310", k)
            assertRefused(TOO_BIG, "1.8e308", k)
        }
    }

    @Test
    fun anExponentIsReadAsBeforeWithinTheBoundsOfTheField() {
        assertNumber(1e5, "1e5", NumberKind.MONEY)
        assertNumber(1e5, "1e5", NumberKind.COUNT)
        // 1 km is no item's side and no door, and no van holds 100,000 m³.
        assertRefused(TOO_BIG, "1e5", NumberKind.LENGTH)
        assertRefused(TOO_BIG, "1e5", NumberKind.VOLUME)
        for (k in kinds) assertNumber(1e-4, "1E-4", k)
    }

    @Test
    fun spacesAroundAndADecimalCommaAreAsBefore() {
        for (k in kinds) {
            assertNumber(12.5, "  12.50 ", k)
            assertNumber(12.5, "12,5", k)
            assertNumber(7.0, " 7 ", k)
            assertNumber(0.5, ".5", k)
            assertNumber(5.0, "5.", k)
            assertNumber(5.0, "+5", k)
            assertRefused(NOT_A_NUMBER, "1,000.5", k)
            assertRefused(NOT_A_NUMBER, "1 000", k)
            assertRefused(NOT_A_NUMBER, "12a", k)
            assertRefused(NOT_A_NUMBER, "-", k)
        }
    }

    @Test
    fun negativeNumbersWhereTheFieldTakesThem() {
        // Money, lengths and load space take a minus sign as 2.1.1 did: the field itself reads zero or less as not set.
        for (k in listOf(NumberKind.MONEY, NumberKind.LENGTH, NumberKind.VOLUME)) {
            assertNumber(-12.5, "-12.5", k)
            assertNumber(-12.5, " -12,5 ", k)
            assertNumber(-0.0, "-0", k)
        }
        assertNumber(-1e15, "-1e15", NumberKind.MONEY)
        assertRefused(TOO_BIG, "-1e16", NumberKind.MONEY)
        assertRefused(TOO_BIG, "-10001", NumberKind.LENGTH)
        // A count never is below 0 (the label screens refused one already, in their own words).
        assertRefused(TOO_SMALL, "-1", NumberKind.COUNT)
        assertRefused(TOO_SMALL, "-0.5", NumberKind.COUNT)
        assertNumber(-0.0, "-0", NumberKind.COUNT)
        assertRefused(TOO_BIG, "-2e6", NumberKind.COUNT)
    }

    @Test
    fun theEmptyFieldIsEmptyNotRefused() {
        for (k in kinds) for (t in listOf("", " ", "\t ")) {
            val read = readNumber(t, k)
            assertEquals(TypedNumber.Empty, read, "'$t' as $k")
            assertNull(read.valueOrNull)
            assertNull(read.refusal)
        }
    }

    @Test
    fun theBoundsOfEachKind() {
        assertNumber(1e15, "1e15", NumberKind.MONEY)
        assertNumber(1e15, "1000000000000000", NumberKind.MONEY)
        assertRefused(TOO_BIG, "1000000000000001", NumberKind.MONEY)
        assertNumber(10_000.0, "10000", NumberKind.LENGTH)
        assertRefused(TOO_BIG, "10000.5", NumberKind.LENGTH)
        assertNumber(10_000.0, "10000", NumberKind.VOLUME)
        assertRefused(TOO_BIG, "10001", NumberKind.VOLUME)
        assertNumber(1e6, "1000000", NumberKind.COUNT)
        assertRefused(TOO_BIG, "1000001", NumberKind.COUNT)
        assertNumber(0.0, "0", NumberKind.COUNT)
    }

    /** Whatever 2.1.1 took that is finite and within the field's bounds means exactly what it meant, bit for bit. */
    @Test
    fun nothingValidChangesMeaning() {
        val typed = listOf(
            "12.5", "12,5", " 7 ", "1e7", "1E-4", "-0", ".5", "5.", "+5", "0x1p4", "5d", "5f", "0.1", "0,30", "180.50", "1e-300",
            "4.9e-324", "123456789012345680", "9007199254740993", "2.5e-7", "-3", "1e3", "99999.99", "0000012", "1_000",
        )
        for (t in typed) for (k in kinds) {
            val was = before(t)
            val now = readNumber(t, k)
            if (was != null && was.isFinite() && was >= k.min && was <= k.max) {
                assertTrue(now is TypedNumber.Number, "'$t' as $k was $was, now $now")
                assertEquals(was.toRawBits(), now.value.toRawBits(), "'$t' as $k")
            } else {
                assertTrue(now !is TypedNumber.Number, "'$t' as $k was $was, now $now")
            }
        }
    }

    @Test
    fun spokenNumbersAreReadAsTypedOnesAndKeepTheirPlace() {
        assertEquals(listOf(200.0, 90.0, 85.5), spokenNumbers("200 by 90 by 85,5", NumberKind.LENGTH).map { it.valueOrNull })
        // In 2.1.1 a spoken number too long for a Double became an infinity on the item.
        val heard = spokenNumbers("200 by ${"9".repeat(310)} by 85", NumberKind.LENGTH)
        assertEquals(listOf(200.0, null, 85.0), heard.map { it.valueOrNull })
        assertEquals(TOO_BIG, heard[1].refusal)
        assertEquals(listOf(TOO_BIG), spokenNumbers("about 20000 centimetres", NumberKind.LENGTH).map { it.refusal })
        assertTrue(spokenNumbers("a big one", NumberKind.LENGTH).isEmpty())
    }

    @Test
    fun theStoreFindsEveryNonFiniteNumberInTheData() {
        assertNull(AppData().firstNonFinite())
        val finite = AppData(
            move = MoveInfo(vanCapacityM3 = 1e21),
            items = listOf(Item(id = "i", widthCm = -0.0, price = Double.MAX_VALUE)),
            expenses = listOf(Expense(id = "e", amount = 1e21)),
        )
        // Large and odd numbers are not the Store's business: the data has always held them, and sync and backups carry them.
        assertNull(finite.firstNonFinite())
        val cases = listOf(
            "the load space" to AppData(move = MoveInfo(vanCapacityM3 = Double.NaN)),
            "a door's width" to AppData(rooms = listOf(Room(id = "r", home = Home.DEST, name = "Hall", doorWidthCm = Double.POSITIVE_INFINITY))),
            "an item's width" to AppData(items = listOf(Item(id = "i", widthCm = Double.NaN))),
            "an item's depth" to AppData(items = listOf(Item(id = "i", depthCm = Double.NEGATIVE_INFINITY))),
            "an item's height" to AppData(items = listOf(Item(id = "i", heightCm = Double.NaN))),
            "an item's asking price" to AppData(hidden = listOf(Hidden(id = "i", price = Double.NaN))),
            "what an item is worth" to AppData(hidden = listOf(Hidden(id = "i", value = Double.POSITIVE_INFINITY))),
            "what an item sold for" to AppData(hidden = listOf(Hidden(id = "i", soldFor = Double.NaN))),
            "a cost's amount" to AppData(expenses = listOf(Expense(id = "e", amount = Double.NaN))),
            "a suggested asking price" to AppData(proposals = listOf(Proposal(id = "p", price = Double.NaN))),
            "a suggested cost's amount" to AppData(proposals = listOf(Proposal(id = "p", expense = Expense(id = "e", amount = Double.NaN)))),
            "an amount to collect" to AppData(collect = listOf(Collect(id = "c", amount = Double.NEGATIVE_INFINITY))),
        )
        for ((what, d) in cases) assertEquals(what, d.firstNonFinite())
        assertEquals("Not saved: a cost's amount is not a number.", notANumberRefusal("a cost's amount"))
    }
}
