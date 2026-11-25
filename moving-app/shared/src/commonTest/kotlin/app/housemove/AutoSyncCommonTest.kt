package app.housemove

import app.housemove.data.Expense
import app.housemove.data.Role
import app.housemove.data.deleteRecursively
import app.housemove.data.newId
import app.housemove.sync.AutoSync
import app.housemove.sync.LocalLan
import app.housemove.sync.Pin
import app.housemove.sync.WifiSync
import app.housemove.sync.memberPhone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Syncing by itself (design/auto-sync.md, encrypted from 2.4: design/encryption.md, Part 2), two to four phones in one
 * process: each keeps its moves as the app does, has its own engine, finds the others through an in-process stand-in for
 * Bonjour, and syncs with them over real sockets on 127.0.0.1, with no code shown or scanned after joining. On the JVM in
 * every test run, and on the iPhone simulator in CI.
 */
class AutoSyncCommonTest {
    private val undoCrypto = useTestCryptoIfNone()
    private val root = newTempFolder()
    private val lan = LocalLan()
    private val phones = ArrayList<AutoPhone>()

    private fun phone(name: String, engineOn: Boolean = true) = AutoPhone(root, name, lan, engineOn).also { phones += it }

    init {
        explainTimeout = {
            phones.joinToString("; ") { p ->
                val st = p.engine.status.value
                "${p.name}: ${st.state}, ${st.phones} phones seen, last dial ${p.engine.lastDial.ifEmpty { "none" }}" +
                    (st.newKey?.let { ", new key $it" } ?: "") + (st.older?.let { ", older $it" } ?: "")
            }
        }
    }

    @AfterTest
    fun cleanUp() {
        try {
            phones.forEach { it.close() }
            root.deleteRecursively()
        } finally {
            WifiSync.olderPhone = { false }
            explainTimeout = null
            undoCrypto()
        }
    }

    private fun AutoPhone.hasItem(name: String) = store.data.value.liveItems.any { it.name == name }

    private fun AutoPhone.pinOf(other: AutoPhone) = store.pins.pinOf(other.store.phone.id)

    @Test
    fun twoPhonesFindEachOtherAndSyncWithNoCode() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        // The join was encrypted, and each phone pinned the other's key as met.
        assertEquals(Pin.MET, asha.pinOf(priya)?.how)
        assertEquals(Pin.MET, priya.pinOf(asha)?.how)
        assertEquals(priya.store.phone.keys.publicText, asha.pinOf(priya)?.key)

        priya.store.createItem("Lamp", null)
        waitUntil("Asha's phone has Priya's lamp") { asha.hasItem("Lamp") }
        asha.store.createItem("Sofa", null)
        waitUntil("Priya's phone has Asha's sofa") { priya.hasItem("Sofa") }

        waitUntil("Priya's phone says it is up to date with Asha's") { priya.engine.status.value.state == AutoSync.State.UP_TO_DATE }
        val now = app.housemove.data.currentTimeMillis()
        assertTrue(priya.engine.status.value.line(now).startsWith("Up to date with Asha's phone · "), priya.engine.status.value.line(now))
        assertTrue(asha.engine.status.value.line(now).startsWith("Up to date with Priya's phone · "), asha.engine.status.value.line(now))
        assertFalse(priya.engine.status.value.needsCode)
        assertNull(priya.engine.status.value.hint(now))
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

        // Priya and Omar never met: each pins the other's key from the move, and they sync by themselves all the same.
        omar.store.createItem("Bike", null)
        hal.store.createItem("Toolbox", null)
        assertTrue(omar.store.saveCost(Expense(id = newId(), title = "Van hire", amount = 180.0)).ok)
        waitUntil("everyone has the bike and the toolbox") { all.all { it.hasItem("Bike") && it.hasItem("Toolbox") } }
        waitUntil("the Decider has the cost") { priya.store.data.value.expenses.any { it.title == "Van hire" } }
        // A Helper's phone is still never sent costs: the rules of the sync are the same.
        holds("Hal's phone, a Helper's, was sent a cost", 800) { hal.store.data.value.expenses.isEmpty() }
        waitUntil("Priya's and Omar's phones hold each other's keys") { priya.pinOf(omar) != null && omar.pinOf(priya) != null }
        assertEquals(omar.store.phone.keys.publicText, priya.pinOf(omar)!!.key)
    }

    @Test
    fun aNewKeyIsTakenOnlyFromAMeetingOrAnOrganisersOrDecidersVouch() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        val omar = phone("Omar")
        asha.found("Maple Road")
        asha.invite(priya, Role.HELPER)
        asha.invite(omar, Role.DECIDER)
        waitUntil("Asha's phone has synced with Priya's") { asha.store.pins.speaksV2(priya.store.phone.id) }
        // Priya's phone lost its key file: it makes a new key, and says so.
        priya.restart(newKey = true)
        val newKey = priya.store.phone.keys.publicText
        waitUntil("Priya's phone says it has a new key") { priya.engine.status.value.newKey == AutoSync.Status.THIS_PHONE }
        assertEquals("This phone has a new key. Sync once with a code to go on syncing by itself.", priya.engine.status.value.hint(0))
        priya.store.createItem("Lamp", null)
        holds("Priya's lamp reached a phone by itself, with a key nobody has met", 1_200) { !asha.hasItem("Lamp") && !omar.hasItem("Lamp") }

        // Omar's phone meets it (a code sync): Omar's pins the new key, and, a Decider's phone, vouches for it to Asha's.
        assertTrue(omar.codeSync(priya).ok)
        assertEquals(newKey, omar.pinOf(priya)!!.key)
        assertEquals(Pin.MET, omar.pinOf(priya)!!.how)
        waitUntil("Asha's phone took Priya's new key from Omar's") { asha.pinOf(priya)?.key == newKey }
        waitUntil("Priya's lamp reached Asha's phone by itself") { asha.hasItem("Lamp") }
        waitUntil("Priya's phone no longer says so") { priya.engine.status.value.newKey == null }
        assertTrue(asha.pinOf(priya)!!.how == Pin.MET)
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
        hal.store.createItem("Hal's first crate", null)
        waitUntil("everyone has Hal's first crate") { all.all { it.hasItem("Hal's first crate") } }

        assertTrue(asha.store.removePerson(halPerson.id).ok)
        // Asha's phone knows Hal is out: Hal's phone is unpinned at once. The others hear of it at their next sync.
        assertNull(asha.store.pins.keyOf(hal.store.phone.id, asha.store.data.value, asha.store.phone.id))
        waitUntil("every phone that stays knows Hal is out, and has unpinned Hal's phone") {
            listOf(asha, priya, omar).all { it.store.data.value.memberPhone(hal.store.phone.id) == null && it.pinOf(hal) == null }
        }
        // They carry on by themselves; Hal's phone, with the keys it knew, can no longer sync by itself with any of them.
        omar.store.createItem("Omar's rug", null)
        waitUntil("Priya's phone has Omar's rug") { priya.hasItem("Omar's rug") && asha.hasItem("Omar's rug") }
        hal.store.createItem("Hal's crate", null)
        holds("Hal's crate reached a phone of the move", 1_500) { listOf(asha, priya, omar).none { it.hasItem("Hal's crate") } }
        assertFalse(hal.hasItem("Omar's rug"))
    }

    @Test
    fun aPhoneWithAnOlderHouseMoveIsSyncedByCodeAndNamed() {
        val asha = phone("Asha")
        val priya = phone("Priya", engineOn = false)
        // Priya's phone is on 2.3 (it stands in for one): it shows eight-character codes and speaks plain.
        val older = priya.moves.phone.id
        WifiSync.olderPhone = { it == older }
        asha.found("Maple Road")
        asha.invite(priya, Role.DECIDER)
        val r = asha.codeSync(priya)
        assertTrue(r.ok && r.plain, "a plain sync: ${r.error}")
        waitUntil("Asha's phone says Priya's needs 2.4") { asha.engine.status.value.older == "Priya" }
        assertEquals("Syncing by itself needs HouseMove 2.4 on Priya's phone too.", asha.engine.status.value.hint(0))
    }

    @Test
    fun theSettingStopsItAndTheStatusSaysSo() {
        val asha = phone("Asha")
        val priya = phone("Priya")
        asha.found("Maple Road")
        asha.invite(priya, Role.HELPER)
        waitUntil("they synced by themselves") { asha.store.pins.speaksV2(priya.store.phone.id) && asha.engine.status.value.state == AutoSync.State.UP_TO_DATE }
        priya.moves.phone.updateSettings { it.copy(autoSync = false) }
        priya.engine.poke()
        waitUntil("Priya's phone stopped") { priya.engine.status.value.state == AutoSync.State.OFF }
        assertEquals("Sync by itself is off", priya.engine.status.value.line(0))
        // Switching off stops new syncs; one already under way (its handshake perhaps not yet done) may finish. Wait until
        // none has been under way for a while.
        var last = -1L
        var calmSince = 0L
        waitUntil("no sync under way") {
            val now = app.housemove.data.currentTimeMillis()
            val at = asha.store.device.value.lastSyncAt
            if (asha.engine.status.value.state == AutoSync.State.SYNCING || at != last) {
                last = at
                calmSince = now
            }
            now - calmSince > 1_500
        }
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
        // 2.4's: a new key, here or there, and a phone with an older HouseMove.
        assertEquals("Priya's phone has a new key. Sync once with a code to go on syncing by itself.", s.copy(newKey = "Priya").hint(0))
        assertEquals("This phone has a new key. Sync once with a code to go on syncing by itself.", s.copy(newKey = AutoSync.Status.THIS_PHONE).hint(0))
        assertEquals("Syncing by itself needs HouseMove 2.4 on Priya's phone too.", s.copy(older = "Priya").hint(0))
        assertEquals("Syncing by itself needs HouseMove 2.4 on the other phone too.", s.copy(older = AutoSync.Status.ANOTHER).hint(0))
        assertEquals("Sync once with a code to start syncing by itself.", s.copy(needsCode = true, older = "Priya").hint(0))
    }
}
