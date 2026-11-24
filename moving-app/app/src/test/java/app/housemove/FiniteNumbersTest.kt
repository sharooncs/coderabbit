package app.housemove

import app.housemove.data.*
import app.housemove.ui.screens.spokenNumbers
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * NaN and the infinities cannot be written as JSON, so data holding one could not be saved, backed up or synced.
 * A number field never gives one ([parseNum]), and the Store never holds one ([finite]).
 */
class FiniteNumbersTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val nan = Double.NaN
    private val inf = Double.POSITIVE_INFINITY

    /** 1 followed by 309 zeros: 310 digits, more than a Double holds, so Kotlin's toDouble gives Infinity. */
    private val digits310 = "1" + "0".repeat(309)

    // ---- typing a number ----------------------------------------------------------------

    @Test
    fun aNumberFieldNeverGivesNaNOrAnInfinity() {
        // What Kotlin's toDoubleOrNull takes, and the old field passed on.
        assertEquals(inf, digits310.toDoubleOrNull())
        assertTrue("NaN".toDoubleOrNull()!!.isNaN())
        for (text in listOf("NaN", "nan", "-NaN", "Infinity", "-Infinity", "+Infinity", digits310, "-$digits310", "$digits310.5")) {
            assertNull(text, parseNum(text))
        }
    }

    @Test
    fun onlyPlainDecimalTextIsANumber() {
        for (text in listOf("0x1p4", "5d", "5f", "5D", "1e5", "1E5", "2e-3", "", " ", "-", "+", ".", ",", "1.2.3", "1,000.5", "12abc", "1 000", "--1", "١٢")) {
            assertNull(text, parseNum(text))
        }
        // As before: a comma is a decimal point (so "1,000" is one), and a half-typed "7." or ".5" already counts.
        val plain = mapOf(
            "12" to 12.0, "12.5" to 12.5, "12,5" to 12.5, " 7 " to 7.0, "-3" to -3.0, "+4" to 4.0,
            ".5" to 0.5, ",5" to 0.5, "7." to 7.0, "0" to 0.0, "007" to 7.0, "1,000" to 1.0,
        )
        for ((text, v) in plain) assertEquals(text, v, parseNum(text)!!, 0.0)
        // The longest run of digits that still fits is taken.
        assertEquals(1e308, parseNum("1" + "0".repeat(308))!!, 0.0)
    }

    @Test
    fun aSpokenNumberTooLongForADoubleIsLeftOutInItsPlace() {
        assertEquals(listOf(200.0, null, 85.0), spokenNumbers("200 by $digits310 by 85"))
        assertEquals(listOf(200.0, 90.5, 85.0), spokenNumbers("200 by 90,5 by 85"))
    }

    // ---- the data ------------------------------------------------------------------------

    /** Every number the saved data holds, as Class.field. Kept in step with [finite] by [everyDoubleInTheSavedDataIsKnown]. */
    private val doubles = sortedSetOf(
        "Collect.amount", "Expense.amount", "Hidden.price", "Hidden.soldFor", "Hidden.value",
        "Item.depthCm", "Item.heightCm", "Item.price", "Item.soldFor", "Item.value", "Item.widthCm",
        "MoveInfo.vanCapacityM3", "Proposal.price", "Room.doorWidthCm",
    )

    @OptIn(ExperimentalSerializationApi::class)
    private fun doublesIn(root: SerialDescriptor): Set<String> {
        val out = sortedSetOf<String>()
        val seen = HashSet<String>()
        fun walk(d: SerialDescriptor) {
            when (d.kind) {
                StructureKind.LIST, StructureKind.MAP -> return (0 until d.elementsCount).forEach { walk(d.getElementDescriptor(it)) }
                StructureKind.CLASS, StructureKind.OBJECT -> {}
                is PolymorphicKind, SerialKind.CONTEXTUAL -> fail("Cannot see what ${d.serialName} holds")
                else -> return
            }
            val name = d.serialName.removeSuffix("?")
            if (!seen.add(name)) return
            for (i in 0 until d.elementsCount) {
                val e = d.getElementDescriptor(i)
                if (e.kind == PrimitiveKind.DOUBLE || e.kind == PrimitiveKind.FLOAT) out += name.substringAfterLast('.') + "." + d.getElementName(i)
                else walk(e)
            }
        }
        walk(root)
        return out
    }

    @Test
    fun everyDoubleInTheSavedDataIsKnown() {
        // A new one fails here: clear it in AppData.finite() (Finite.kt), add it to the data below and to [doubles].
        assertEquals(doubles, doublesIn(AppData.serializer().descriptor))
    }

    /** Data with every number in [doubles] not a real one, and a few real ones beside them. */
    private fun allBad() = AppData(
        move = MoveInfo(name = "Maple Road", vanCapacityM3 = nan),
        rooms = listOf(Room(id = "r1", home = Home.DEST, name = "Hall", doorWidthCm = inf)),
        items = listOf(Item(id = "i1", name = "Sofa", price = nan, widthCm = inf, depthCm = 90.0, heightCm = -inf, value = nan, soldFor = inf)),
        expenses = listOf(Expense(id = "e1", title = "Van hire", amount = -inf)),
        proposals = listOf(Proposal(id = "p1", price = nan, expense = Expense(id = "e2", title = "Boxes", amount = inf))),
        hidden = listOf(Hidden(id = "i1", value = inf, price = nan, soldFor = -inf, make = "Ikea")),
        collect = listOf(Collect(id = "i1", amount = nan)),
    )

    @Test
    fun dataWithNaNOrAnInfinityCannotBeWrittenSoFiniteClearsIt() {
        val bad = allBad()
        assertThrows(SerializationException::class.java) { AppJson.encodeToString(bad) }

        val d = bad.finite()
        assertEquals(MoveInfo().vanCapacityM3, d.move.vanCapacityM3, 0.0)
        assertEquals("Maple Road", d.move.name)
        assertNull(d.rooms.single().doorWidthCm)
        with(d.items.single()) {
            assertEquals(listOf(null, null, 90.0, null, null, null), listOf(price, widthCm, depthCm, heightCm, value, soldFor))
            assertEquals("Sofa", name)
        }
        assertEquals(0.0, d.expenses.single().amount, 0.0)
        with(d.proposals.single()) {
            assertNull(price)
            assertEquals(0.0, expense!!.amount, 0.0)
        }
        with(d.hidden.single()) {
            assertEquals(listOf(null, null, null), listOf(value, price, soldFor))
            assertEquals("Ikea", make)
        }
        assertNull(d.collect.single().amount)

        // It can be written, and reads back as it was.
        assertEquals(d, AppJson.decodeFromString<AppData>(AppJson.encodeToString(d)))
    }

    @Test
    fun dataThatIsAlreadyFineIsLeftAsItIs() {
        val fine = allBad().finite()
        assertSame(fine, fine.finite())
        val empty = AppData()
        assertSame(empty, empty.finite())
    }

    // ---- the Store -----------------------------------------------------------------------

    @Test
    fun theStoreNeverHoldsNaNOrAnInfinityAndKeepsSaving() {
        val dir = tmp.newFolder("phone")
        lateinit var sofaId: String
        lateinit var hallId: String
        lateinit var vanId: String
        Store(dir).apply {
            val sofa = createItem("Sofa")
            sofaId = sofa.id
            hallId = createRoom(Home.DEST, "Hall").id

            // Every way a number gets in, given one that is not a real number.
            put(sofa.copy(widthCm = nan, depthCm = 90.0, heightCm = inf))
            put(data.value.room(hallId)!!.copy(doorWidthCm = -inf))
            assertTrue(changeMove { it.copy(vanCapacityM3 = 20.0) }.ok)
            assertTrue(changeMove { it.copy(vanCapacityM3 = nan) }.ok)
            assertTrue(setDetails(sofaId) { it.copy(value = inf, make = "Ikea") }.ok)
            assertTrue(decide(sofaId, Disposition.SELL, nan).ok)
            assertTrue(carryOut(sofaId, gone = true, soldFor = inf).ok)
            assertTrue(arrangeHandover(sofaId, to = me!!.id, amount = nan).ok)
            vanId = saveCost(Expense(id = newId(), title = "Van hire", amount = nan)).valueOrNull()!!.id
            update { it.copy(proposals = it.proposals + Proposal(id = "p1", price = inf, expense = Expense(id = "e2", amount = nan))) }

            assertEquals(data.value, data.value.finite())
            AppJson.encodeToString(data.value) // would throw if anything were left
            ByteArrayOutputStream().also { Backup.export(this, it) }.also { assertTrue(it.size() > 0) }

            // A change made afterwards is saved too: before, every save after the first bad number failed.
            put(data.value.item(sofaId)!!.copy(name = "Big sofa"))
            close()
        }

        val again = Store(dir).data.value
        with(again.item(sofaId)!!) {
            assertEquals("Big sofa", name)
            assertEquals(listOf(null, 90.0, null), listOf(widthCm, depthCm, heightCm))
        }
        assertNull(again.room(hallId)!!.doorWidthCm)
        assertEquals(MoveInfo().vanCapacityM3, again.move.vanCapacityM3, 0.0)
        with(again.hidden.single { it.id == sofaId }) {
            assertEquals(listOf(null, null, null), listOf(value, price, soldFor))
            assertEquals("Ikea", make)
        }
        assertNull(again.collect.single { it.id == sofaId }.amount)
        assertEquals(0.0, again.expense(vanId)!!.amount, 0.0)
        with(again.proposals.single { it.id == "p1" }) {
            assertNull(price)
            assertEquals(0.0, expense!!.amount, 0.0)
        }
        assertFalse(File(dir, "data.json.tmp").exists())
    }

    @Test
    fun dataTakenWholeIsClearedToo() {
        val s = Store(tmp.newFolder("phone"))
        val sofa = s.createItem("Sofa")
        s.replaceAll(s.data.value.copy(items = s.data.value.items.map { it.copy(widthCm = nan) }))
        assertNull(s.data.value.item(sofa.id)!!.widthCm)
        AppJson.encodeToString(s.data.value)
    }
}
