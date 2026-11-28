package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The shared log of who did what. */
class ActivityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private var folders = 0
    private fun store(name: String) = Store(tmp.newFolder("s" + folders++)) { clock++ }.also { s -> s.setDevice { it.copy(name = name) } }

    private fun Store.lines(n: Int = 50) = data.value.latestActivity(n).map { data.value.line(it) }

    @Test
    fun theNotableThingsAreWrittenDownWithWhoDidThem() {
        val asha = store("Asha")
        val box = asha.createBox("L012", null)
        val suitcase = asha.createBox("B2", null, ContainerKind.SUITCASE)
        val sofa = asha.createItem("Sofa", null)
        assertTrue(asha.lines().isEmpty()) // adding things is not news

        asha.put(asha.data.value.box(box.id)!!.copy(sealed = true))
        assertEquals("Asha sealed L012", asha.lines().first())
        asha.put(asha.data.value.box(box.id)!!.copy(sealed = false, contents = "Plates"))
        assertEquals("Asha opened L012", asha.lines().first())
        asha.put(asha.data.value.box(box.id)!!.copy(contents = "Plates, bowls", notes = "heavy")) // an ordinary edit
        assertEquals(2, asha.lines().size)

        asha.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.LOADED) }
        asha.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.DELIVERED) }
        asha.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.DONE) }
        assertEquals(listOf("Asha unpacked L012", "Asha delivered L012", "Asha loaded L012"), asha.lines().take(3))
        asha.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.DELIVERED) } // an undo is not announced
        assertEquals("Asha unpacked L012", asha.lines().first())

        asha.updateBoxes(listOf(suitcase.id)) { it.copy(damaged = true, damageNote = "Wheel broken") }
        assertEquals("Asha reported damage to suitcase B2", asha.lines().first())
        asha.updateItems(listOf(sofa.id)) { it.copy(damaged = true) }
        assertEquals("Asha reported damage to Sofa", asha.lines().first())
        asha.updateItems(listOf(sofa.id)) { it.copy(damageNote = "Leg cracked") } // adding the note is not a second report
        assertEquals("Asha reported damage to Sofa", asha.lines().first())

        asha.put(asha.data.value.item(sofa.id)!!.copy(disposition = Disposition.DONATE))
        assertEquals("Asha decided to donate Sofa", asha.lines().first())
        asha.carryOut(sofa.id, gone = true)
        assertEquals("Asha donated Sofa", asha.lines().first())
        asha.put(asha.data.value.item(sofa.id)!!.copy(disposition = Disposition.UNDECIDED))
        assertEquals("Asha reopened the decision on Sofa", asha.lines().first())

        // Each line says who, when and about what.
        val last = asha.data.value.latestActivity(1).single()
        assertEquals(asha.me!!.id, last.by)
        assertEquals(sofa.id, last.refId)
        assertTrue(last.at > 0)
        // The latest N, newest first.
        assertEquals(3, asha.data.value.latestActivity(3).size)
        val times = asha.data.value.latestActivity(50).map { it.at }
        assertEquals(times.sortedDescending(), times)
    }

    @Test
    fun manyThingsAtOnceMakeOneLineAndTheLogStaysSmall() {
        val asha = store("Asha")
        val ids = (1..40).map { asha.createBox("L$it", null).id }
        asha.updateBoxes(ids) { it.copy(sealed = true, stage = Stage.LOADED) }
        assertEquals(setOf("Asha loaded 40 things", "Asha sealed 40 things"), asha.lines().toSet())
        asha.updateBoxes(ids.take(1)) { it.copy(stage = Stage.DELIVERED) }
        assertEquals("Asha delivered L1", asha.lines().first())

        // Only the newest lines are kept, on every phone alike.
        repeat(ACTIVITY_KEEP + 60) { n -> asha.put(asha.data.value.box(ids[0])!!.copy(sealed = n % 2 == 0)) }
        assertEquals(ACTIVITY_KEEP, asha.data.value.activity.size)
        assertEquals(20, asha.data.value.latestActivity().size)
        val other = store("Ben")
        other.mergeIn(asha.data.value)
        asha.mergeIn(other.data.value)
        assertEquals(ACTIVITY_KEEP, asha.data.value.activity.size)
        assertEquals(asha.data.value.activity.toSet(), other.data.value.activity.toSet())
    }

    @Test
    fun everyonesWorkEndsUpInTheSameLog() {
        val asha = store("Asha")
        val box = asha.createBox("L001", null)
        val hal = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        val phone = store("")
        assertTrue(phone.joinAs(hal.id, asha.data.value).ok)
        phone.updateBoxes(listOf(box.id)) { it.copy(sealed = true) }
        phone.updateBoxes(listOf(box.id)) { it.copy(stage = Stage.LOADED) }
        asha.setRole(hal.id, Role.DECIDER)
        asha.mergeIn(phone.data.value)
        phone.mergeIn(asha.data.value)
        for (s in listOf(asha, phone)) {
            val log = s.lines()
            assertTrue(log.toString(), log.containsAll(listOf("Asha added Hal as a Helper", "Hal sealed L001", "Hal loaded L001", "Asha made Hal a Decider")))
        }
        assertEquals(asha.lines(), phone.lines())
    }
}
