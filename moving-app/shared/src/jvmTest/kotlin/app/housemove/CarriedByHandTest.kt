package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Things that travel with someone ("With me" when loading, as a suitcase does unless told otherwise)
 * are carried by hand, not on the van. The data layer judges "still on the van" the way the move-day
 * screens do: such a thing taken along without a trip is never left on the van.
 */
class CarriedByHandTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L

    @Test
    fun whatTravelsWithSomeoneIsNeverStillOnTheVan() {
        val s = Store(tmp.newFolder("s")) { clock++ }
        val trip = Trip(id = "t1", name = "Trip 1").also { s.put(it) }
        val carton = s.createBox("L001", null).id
        val suitcase = s.createBox("B1", null, ContainerKind.SUITCASE).id
        val passports = s.createItem("Passports", null).id
        assertTrue(s.decide(passports, Disposition.MOVE).ok)
        s.updateItems(listOf(passports)) { it.copy(travelsWithMe = true) }
        val camera = s.createItem("Camera", null).id
        assertTrue(s.putInto(camera, suitcase).ok)

        // The carton goes on the van; the suitcase and the passports are taken along by hand.
        s.updateBoxes(listOf(carton)) { it.copy(stage = Stage.LOADED, tripId = trip.id) }
        s.updateBoxes(listOf(suitcase)) { it.copy(stage = Stage.LOADED) }
        s.updateItems(listOf(passports)) { it.copy(stage = Stage.LOADED) }
        var d = s.data.value
        assertTrue(d.box(suitcase)!!.carriedByHand())
        assertFalse(d.box(carton)!!.carriedByHand())
        assertEquals(listOf(carton), d.movables().filter { it.onTheVan() }.map { it.id })
        assertEquals("Carried by hand, on its way to New home", d.whereIs(d.box(suitcase)!!.toMovable()))
        // What is in the suitcase is carried with it.
        assertEquals("In Suitcase B1 · Carried by hand, on its way to New home", d.whereIs(d.item(camera)!!.toMovable()))
        assertEquals("Carried by hand", d.statusOf(d.item(camera)!!.toMovable()))
        assertEquals("On the van", d.statusOf(d.box(carton)!!.toMovable()))
        assertTrue(d.latestActivity(10).any { it.text == "took along suitcase B1" })

        // The van is unloaded. Nothing is left on it, so nothing is said to be: before, the suitcase and the passports were.
        s.updateBoxes(listOf(carton)) { it.copy(stage = Stage.DELIVERED) }
        d = s.data.value
        assertTrue(d.issues().none { it.text.contains("loaded but not yet delivered") })
        // They still count as on their way until someone marks them arrived, as the Unload screen does.
        assertEquals(Ratio(1, 3), d.progress().delivered)
        assertEquals(Ratio(3, 3), d.progress().loaded)

        // Something marked "with me" that was put on a trip after all is on the van like anything else.
        s.updateItems(listOf(passports)) { it.copy(tripId = trip.id) }
        d = s.data.value
        assertEquals(listOf("1 loaded but not yet delivered"), d.issues().filter { it.text.contains("loaded but") }.map { it.text })
        assertEquals("On the van (Trip 1)", d.whereIs(d.item(passports)!!.toMovable()))
    }
}
