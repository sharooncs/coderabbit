package app.housemove

import app.housemove.data.Expense
import app.housemove.data.Role
import app.housemove.data.newId
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalLan
import app.housemove.sync.Meetings
import app.housemove.sync.memberPhone
import app.housemove.data.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Syncing by itself (design/auto-sync.md), two to four phones in one process: each keeps its moves as the app does, has
 * its own engine, finds the others through an in-process stand-in for Bonjour, and syncs with them over real sockets on
 * 127.0.0.1, with no code shown or scanned after joining. On the JVM in every test run, and on the iPhone simulator in CI.
 */
class AutoSyncCommonTest {
    private val root = newTempFolder()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()

    private fun phone(name: String, engineOn: Boolean = true) = AutoPhone(root, name, lan, engineOn).also { phones += it }

    @AfterTest
    fun cleanUp() {
        phones.forEach { it.close() }
        Meetings.clear()
        root.deleteRecursively()
    }

    private fun AutoPhone.hasItem(name: String) = store.data.value.liveItems.any { it.name == name }

    private fun AutoPhone.keyIds() = store.ring.keys(store.data.value).map { it.id }.toSet()

    @Test
    fun twoPhonesFindEachOtherAndSyncWithNoCode() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        // The join was a meeting: the two swap keys by themselves, and from then on need no code.
        waitUntil("both phones hold the same Wi-Fi key") { asha.keyIds().isNotEmpty() && asha.keyIds() == priya.keyIds() }
        waitUntil("a pair key each") { asha.store.ring.pairs().containsKey(priya.store.phone.id) && priya.store.ring.pairs().containsKey(asha.store.phone.id) }

        priya.store.createItem("Lamp", null)
        waitUntil("Asha's phone has Priya's lamp") { asha.hasItem("Lamp") }
        asha.store.createItem("Sofa", null)
        waitUntil("Priya's phone has Asha's sofa") { priya.hasItem("Sofa") }

        waitUntil("Priya's phone says it is up to date with Asha's") { priya.engine.status.value.state == AutoSync.State.UP_TO_DATE }
        val now = app.housemove.data.currentTimeMillis()
        assertTrue(priya.engine.status.value.line(now).startsWith("Up to date with Asha's phone · "), priya.engine.status.value.line(now))
        assertTrue(asha.engine.status.value.line(now).startsWith("Up to date with Priya's phone · "), asha.engine.status.value.line(now))
        assertFalse(priya.engine.status.value.needsCode)
    }

    @Test
    fun fourPhonesKeepEachOtherUpToDateAndTheRulesStillHold() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        val omar = phone("Omar")
        val hal = phone("Hal")
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        asha.invite(omar, Role.ORGANISER)
        asha.invite(hal, Role.HELPER)
        val all = listOf(asha, priya, omar, hal)
        waitUntil("every phone holds a Wi-Fi key they share") { all.all { it.keyIds().isNotEmpty() } && all.map { it.keyIds().max() }.toSet().size == 1 }

        // Priya and Omar never met: they sync by themselves all the same, with the move's Wi-Fi key.
        omar.store.createItem("Bike", null)
        hal.store.createItem("Toolbox", null)
        assertTrue(omar.store.saveCost(Expense(id = newId(), title = "Van hire", amount = 180.0)).ok)
        waitUntil("everyone has the bike and the toolbox") { all.all { it.hasItem("Bike") && it.hasItem("Toolbox") } }
        waitUntil("the Decider has the cost") { priya.store.data.value.expenses.any { it.title == "Van hire" } }
        // A Helper's phone is still never sent costs: the rules of the sync are the same.
        holds("Hal's phone, a Helper's, was sent a cost", 800) { hal.store.data.value.expenses.isEmpty() }
        assertTrue(priya.store.ring.pairs().containsKey(omar.store.phone.id) || omar.store.ring.pairs().containsKey(priya.store.phone.id))
    }

    @Test
    fun aPhoneThatJoinedBeforeGetsItsKeysAtItsNextCodeSync() {
        val asha = phone("Asha")
        // Priya's phone joined as a 2.2 phone would: nothing by itself.
        val priya = phone("Priya", engineOn = false)
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        // Long ago: the meeting no longer counts.
        Meetings.clear()
        priya.engine.resume()
        waitUntil("Priya's phone says it needs one code sync") { priya.engine.status.value.needsCode }
        assertTrue(priya.engine.status.value.hint(0)!!.startsWith("Sync once with a code"))
        priya.store.createItem("Lamp", null)
        holds("nothing synced by itself without a key", 1_000) { !asha.hasItem("Lamp") }

        // One code sync: from then on, by itself.
        assertTrue(asha.codeSync(priya).ok)
        waitUntil("Priya's phone has the Wi-Fi key") { priya.keyIds().isNotEmpty() && priya.keyIds() == asha.keyIds() }
        priya.store.createItem("Desk", null)
        waitUntil("Asha's phone has Priya's desk, with no code") { asha.hasItem("Desk") }
        assertFalse(priya.engine.status.value.needsCode)
    }

    @Test
    fun aRemovedPhoneIsLeftOutAndTheOthersCarryOn() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        val omar = phone("Omar")
        val hal = phone("Hal")
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        asha.invite(omar, Role.DECIDER)
        val halPerson = asha.invite(hal, Role.HELPER)
        val all = listOf(asha, priya, omar, hal)
        waitUntil("every phone holds the Wi-Fi key") { all.all { it.keyIds().isNotEmpty() } && all.map { it.keyIds().max() }.toSet().size == 1 }
        // Everyone has met everyone by itself (pair keys), as a family's phones on one Wi-Fi do in a few minutes.
        waitUntil("pair keys between the phones that stay") {
            listOf(asha, priya, omar).all { a -> listOf(asha, priya, omar).all { b -> a === b || a.store.ring.pairs().containsKey(b.store.phone.id) } }
        }
        val old = asha.keyIds()

        assertTrue(asha.store.removePerson(halPerson.id).ok)
        // Asha's phone knows Hal is out: the key Hal holds is dropped, and a new one, which lists Hal's phone, takes over.
        waitUntil("a new Wi-Fi key that the others share") {
            val k = listOf(asha, priya, omar).map { it.keyIds() }
            k.all { it.isNotEmpty() && it.intersect(old).isEmpty() } && k.toSet().size == 1
        }
        assertTrue(hal.keyIds().isNotEmpty(), "Hal's phone still holds the old key: it heard nothing")
        for (p in listOf(asha, priya, omar)) assertEquals(null, p.store.data.value.memberPhone(hal.store.phone.id), "${p.name} knows Hal is out")

        // Hal's phone can no longer sync by itself, though it holds the old key; the others still do, with no code.
        hal.store.createItem("Hal's crate", null)
        omar.store.createItem("Omar's rug", null)
        waitUntil("Priya's phone has Omar's rug") { priya.hasItem("Omar's rug") && asha.hasItem("Omar's rug") }
        holds("Hal's crate reached a phone of the move", 1_500) { listOf(asha, priya, omar).none { it.hasItem("Hal's crate") } }
        assertFalse(hal.hasItem("Omar's rug"))
        assertNotEquals(hal.keyIds(), asha.keyIds())
    }

    @Test
    fun theSettingStopsItAndTheStatusSaysSo() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.invite(priya, Role.HELPER)
        waitUntil("they swapped keys") { priya.keyIds().isNotEmpty() }
        priya.moves.phone.updateSettings { it.copy(autoSync = false) }
        priya.engine.poke()
        waitUntil("Priya's phone stopped") { priya.engine.status.value.state == AutoSync.State.OFF }
        assertEquals("Sync by itself is off", priya.engine.status.value.line(0))
        priya.store.createItem("Lamp", null)
        holds("nothing went while the setting was off", 1_000) { !asha.hasItem("Lamp") }
        priya.moves.phone.updateSettings { it.copy(autoSync = true) }
        priya.engine.poke()
        waitUntil("the lamp went once the setting was on again") { asha.hasItem("Lamp") }
    }

    @Test
    fun theStatusWords() {
        val m = 60_000L
        val s = AutoSync.Status(AutoSync.State.UP_TO_DATE, lastWith = "Asha", lastAt = 1_000_000)
        assertEquals("Up to date with Asha's phone · just now", s.line(1_000_000 + 30_000))
        assertEquals("Up to date with Asha's phone · 2 min ago", s.line(1_000_000 + 2 * m + 5_000))
        assertEquals("Looking for the family's phones on this Wi-Fi", s.copy(state = AutoSync.State.LOOKING).line(0))
        assertEquals("Last synced with Asha's phone 3 hours ago.", s.copy(state = AutoSync.State.LOOKING).hint(1_000_000 + 180 * m))
        assertEquals("Last synced with Asha's phone 1 day ago.", s.copy(state = AutoSync.State.LOOKING).hint(1_000_000 + 1500 * m))
        assertEquals("Syncing with Priya's phone…", s.copy(state = AutoSync.State.SYNCING, syncingWith = "Priya").line(0))
        assertEquals("Syncing with another phone…", s.copy(state = AutoSync.State.SYNCING, syncingWith = " ").line(0))
        assertEquals("Not on Wi-Fi", s.copy(state = AutoSync.State.NO_WIFI).line(0))
        assertEquals("HouseMove may not use your Wi-Fi", s.copy(state = AutoSync.State.DENIED).line(0))
        assertEquals(null, s.hint(1_000_000))
    }
}
