package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Phones meeting over the network (localhost here): invites, who is let in, and what a Helper's phone is sent. */
class SyncPeopleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var folders = 0
    private fun store(name: String = "") = Store(tmp.newFolder("s" + folders++)).also { s -> if (name.isNotEmpty()) s.setDevice { it.copy(name = name) } }

    private val local = listOf("127.0.0.1")

    /** A phone showing its sync code, and what it reported after each visit. */
    private class Hosting(val host: WifiSync.Host, private val results: LinkedBlockingQueue<WifiSync.Result>) {
        fun result(): WifiSync.Result = results.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("the host reported nothing")
    }

    private val open = ArrayList<WifiSync.Host>()

    private fun hosting(store: Store, letNewPhonesIn: Boolean = true, clock: () -> Long = System::currentTimeMillis): Hosting {
        val results = LinkedBlockingQueue<WifiSync.Result>()
        val h = WifiSync.host(store, clock, letNewPhonesIn) { results.add(it) }
        open += h
        return Hosting(h, results)
    }

    @After
    fun stopHosts() = open.forEach { it.stop() }

    /** A plain sync: [joiner] scans the ordinary code on [host]'s phone. Returns what each side reported. */
    private fun sync(host: Store, joiner: Store): Pair<WifiSync.Result, WifiSync.Result> {
        val h = hosting(host)
        val r = WifiSync.join(joiner, h.host.invite(local))
        return h.result() to r
    }

    /** A phone brought into [host]'s move with an invite for a newly added person. */
    private fun invited(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val h = hosting(host)
        val phone = store()
        val r = WifiSync.join(phone, h.host.inviteFor(p.id, local).valueOrNull()!!)
        assertTrue(r.error, r.ok)
        assertTrue(h.result().ok)
        return phone
    }

    private fun Store.photo(ownerId: String, kind: PhotoKind, bytes: Int): File {
        val f = newPhotoFile().apply { writeBytes(ByteArray(bytes) { 7 }) }
        addPhoto(ownerId, kind, f)
        return f
    }

    // ---- invites -----------------------------------------------------------------

    @Test
    fun anInviteNamesThePersonAndReadsBackFromItsQrText() {
        val plain = WifiSync.Invite(listOf("192.168.1.5", "10.0.0.2"), 4567, "ABCD2345")
        assertEquals("HMSYNC:192.168.1.5,10.0.0.2:4567:ABCD2345", plain.encode())
        assertNull(WifiSync.Invite.parse(plain.encode())!!.personId)
        val forHal = plain.copy(personId = "3f0c-11", personName = "Hal: Asha's brother & co.", role = Role.HELPER)
        val back = WifiSync.Invite.parse(forHal.encode())!!
        assertEquals(forHal, back)
        assertEquals("Hal: Asha's brother & co.", back.personName)
        assertEquals(Role.HELPER, back.role)
        // The camera reads it as it reads the plain one.
        val decoder = Qr.Decoder()
        val size = 240
        val m = Qr.matrix(forHal.encode(), size)
        val w = 640
        val frame = ByteArray(w * 480) { 150.toByte() }
        for (y in 0 until size) for (x in 0 until size) frame[(y + 100) * w + (x + 200)] = (if (m.get(x, y)) 20 else 235).toByte()
        assertEquals(forHal.encode(), decoder.decode(frame, w, 480))
        assertNull(WifiSync.Invite.parse("HMSYNC:1.2.3.4:80:TOKEN:only-four"))
        assertNull(WifiSync.Invite.parse("HMSYNC:1.2.3.4:80:TOKEN::HELPER:Hal"))
    }

    @Test
    fun anOrganiserInvitesSomeoneAndTheirPhoneBecomesThatPerson() {
        val asha = store("Asha")
        asha.setDevice { it.copy(letter = "A") }
        asha.setMove { it.copy(name = "Maple Road", setupDone = true) }
        val box = asha.createBox("L001", null)
        asha.put(Task(id = "t1", title = "Book the van"))
        val hal = asha.addPerson("Hal", Role.HELPER).valueOrNull()!!
        assertEquals(0L, hal.joinedAt)

        // Hal's phone was tried out on its own first: it has a person, a box, a decision and a checklist of its own.
        val phone = store("My phone")
        val ownBox = phone.createBox("X1", null)
        val ownItem = phone.createItem("Bike", null)
        assertTrue(phone.decide(ownItem.id, Disposition.SELL, 300.0).ok)
        phone.put(Task(id = "own-task", title = "My own checklist"))
        phone.setMove { it.copy(name = "Trying it out", setupDone = true) }
        val started = phone.me!!.id

        var now = 1_000_000L
        val h = hosting(asha) { now }
        val invite = WifiSync.Invite.parse(h.host.inviteFor(hal.id, local).valueOrNull()!!.encode())!!
        assertEquals(hal.id, invite.personId)
        assertEquals("Hal", invite.personName)
        assertEquals(Role.HELPER, invite.role)
        assertNotEquals(h.host.token, invite.token)

        val r = WifiSync.join(phone, invite)
        assertTrue(r.error, r.ok)
        val hosted = h.result()
        assertTrue(hosted.error, hosted.ok)
        assertEquals(hal.id, r.joinedAs!!.id)
        assertEquals(hal.id, hosted.joinedAs!!.id)
        assertEquals("Asha", r.peerName)

        // The phone is Hal now: his name, his letter, his role.
        assertEquals(hal.id, phone.device.value.personId)
        assertEquals(hal.id, phone.me!!.id)
        assertEquals("Hal", phone.device.value.name)
        assertEquals(hal.letter, phone.device.value.letter)
        assertEquals("B", hal.letter)
        assertEquals(Role.HELPER, phone.myRole)
        assertTrue(phone.device.value.helper)
        assertTrue(phone.me!!.joinedAt > 0)
        assertTrue(asha.data.value.person(hal.id)!!.joinedAt > 0)
        // The person it started as is gone, on both phones.
        for (s in listOf(asha, phone)) {
            assertNull(s.data.value.person(started))
            assertEquals(setOf(asha.me!!.id, hal.id), s.data.value.people.map { it.id }.toSet())
            // The work it had done is kept, but what it decided or planned alone carries no weight in this move.
            assertEquals(setOf("L001", "X1"), s.data.value.liveBoxes.map { it.label }.toSet())
            assertEquals(Disposition.UNDECIDED, s.data.value.item(ownItem.id)!!.disposition)
            assertEquals(listOf("Book the van"), s.data.value.liveTasks.map { it.title })
            assertEquals("Maple Road", s.data.value.move.name)
        }
        assertNotNull(asha.data.value.box(ownBox.id))
        assertNotNull(phone.data.value.box(box.id))

        // The invite works once.
        val second = store("Someone else")
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(second, invite).error)
        assertEquals(WifiSync.INVITE_EXPIRED, h.result().error)
        assertTrue(second.data.value.liveBoxes.isEmpty())
        assertEquals(2, asha.data.value.people.size)

        // After that the two phones just sync, with the ordinary code and nobody to choose.
        phone.put(phone.data.value.box(box.id)!!.copy(sealed = true))
        val again = WifiSync.join(phone, h.host.invite(local))
        assertTrue(again.error, again.ok)
        assertNull(again.joinedAs)
        assertTrue(h.result().ok)
        assertTrue(asha.data.value.box(box.id)!!.sealed)

        // An invite is offered for ten minutes and no longer.
        val dee = asha.addPerson("Dee", Role.DECIDER).valueOrNull()!!
        val late = h.host.inviteFor(dee.id, local).valueOrNull()!!
        now += WifiSync.INVITE_MINUTES * 60_000L + 1
        val deePhone = store()
        assertEquals(WifiSync.INVITE_EXPIRED, WifiSync.join(deePhone, late).error)
        h.result()
        assertEquals(0L, asha.data.value.person(dee.id)!!.joinedAt)
        val fresh = h.host.inviteFor(dee.id, local).valueOrNull()!!
        now += 9 * 60_000L
        assertTrue(WifiSync.join(deePhone, fresh).ok)
        assertTrue(h.result().ok)
        assertEquals(Role.DECIDER, deePhone.myRole)
        // A personal invite cannot be used to come in as somebody else.
        val forSam = h.host.inviteFor(asha.addPerson("Sam", Role.HELPER).valueOrNull()!!.id, local).valueOrNull()!!
        assertFalse(WifiSync.join(store(), forSam.copy(personId = asha.me!!.id)).ok)
        h.result()

        // Only someone who may add people can make one, and only for someone in the move.
        assertEquals("Only an Organiser can add people, change roles or remove people.", hosting(phone).host.inviteFor(dee.id, local).reason)
        assertNotNull(h.host.inviteFor("nobody", local).reason)
    }

    // ---- who is let in --------------------------------------------------------------

    @Test
    fun aRemovedPersonsPhoneIsRefusedWithAClearMessage() {
        val asha = store("Asha")
        asha.createBox("L001", null)
        val hal = invited(asha, "Hal", Role.HELPER)
        assertTrue(sync(asha, hal).second.ok)

        assertTrue(asha.removePerson(hal.me!!.id).ok)
        hal.createBox("B9", null)
        val before = asha.data.value
        // Hal scans Asha's code.
        val (atAsha, atHal) = sync(asha, hal)
        assertEquals(WifiSync.REMOVED_SELF, atHal.error)
        assertEquals("You have been removed from this move, so this phone can no longer sync with it.", atHal.error)
        assertTrue(atAsha.error, atAsha.error!!.contains("Hal has been removed from this move"))
        assertEquals(before, asha.data.value)
        assertNull(asha.data.value.findByLabel("B9"))
        // Asha scans Hal's code: the same, the other way round.
        val (hosted, joined) = sync(hal, asha)
        assertTrue(joined.error, joined.error!!.contains("Hal has been removed from this move"))
        assertEquals(WifiSync.REMOVED_SELF, hosted.error)
        assertEquals(before, asha.data.value)
        // Another phone in the move refuses him too, once it has heard.
        val dee = invited(asha, "Dee", Role.DECIDER)
        assertEquals(WifiSync.REMOVED_SELF, sync(dee, hal).second.error)

        // Let back in, he syncs again and his box arrives.
        assertTrue(asha.restorePerson(hal.me!!.id).ok)
        assertTrue(sync(asha, hal).second.ok)
        assertNotNull(asha.data.value.findByLabel("B9"))
    }

    @Test
    fun aPhoneThatIsNotInTheMoveIsOnlyLetInByAnOrganiser() {
        val asha = store("Asha")
        asha.createBox("L001", null)
        val hal = invited(asha, "Hal", Role.HELPER)
        val stranger = store("Stranger")
        stranger.createBox("S1", null)

        // With a Helper's phone, either way round, nothing is exchanged.
        val (atHal, atStranger) = sync(hal, stranger)
        assertEquals(WifiSync.NOT_ADDED, atStranger.error)
        assertEquals(WifiSync.UNKNOWN_PHONE, atHal.error)
        val (hosted, joined) = sync(stranger, hal)
        assertEquals(WifiSync.UNKNOWN_PHONE, joined.error)
        assertEquals(WifiSync.NOT_ADDED, hosted.error)
        assertEquals(listOf("L001"), hal.data.value.liveBoxes.map { it.label })
        assertEquals(listOf("S1"), stranger.data.value.liveBoxes.map { it.label })
        assertEquals(1, stranger.data.value.people.size)

        // An Organiser can keep the ordinary code for phones already in the move, so that the only way in is an invite.
        val membersOnly = hosting(asha, letNewPhonesIn = false)
        assertEquals(WifiSync.NOT_ADDED, WifiSync.join(stranger, membersOnly.host.invite(local)).error)
        assertEquals(WifiSync.UNKNOWN_PHONE, membersOnly.result().error)
        assertTrue(WifiSync.join(hal, membersOnly.host.invite(local)).ok)
        assertTrue(membersOnly.result().ok)
        assertEquals(WifiSync.UNKNOWN_PHONE, WifiSync.join(asha, hosting(stranger).host.invite(local), letNewPhonesIn = false).error)
        assertEquals(2, asha.data.value.people.size)

        // Otherwise an Organiser showing their code is the one who may add a phone: it comes in as it is, as before 2.0.
        val (a, s) = sync(asha, stranger)
        assertTrue(a.error, a.ok)
        assertTrue(s.error, s.ok)
        assertEquals(setOf("L001", "S1"), asha.data.value.liveBoxes.map { it.label }.toSet())
        assertEquals(3, asha.data.value.people.size)
        assertEquals(stranger.me!!.id, a.peerPersonId)
        // Now it is in the move, and the Helper's phone syncs with it once it has heard so.
        assertTrue(sync(asha, hal).second.ok)
        assertTrue(sync(hal, stranger).second.ok)
    }

    @Test
    fun twoPeopleAddedByDifferentOrganisersCanSyncBeforeTheirPhonesHaveHeardOfEachOther() {
        val asha = store("Asha")
        val omar = invited(asha, "Omar", Role.ORGANISER)
        // Each Organiser adds someone on their own phone; the Organisers have not synced since.
        val dee = invited(asha, "Dee", Role.DECIDER)
        val hal = invited(omar, "Hal", Role.HELPER)
        assertNull(dee.data.value.person(hal.me!!.id))
        assertNull(hal.data.value.person(dee.me!!.id))
        hal.createBox("B1", null)
        val (hosted, joined) = sync(dee, hal)
        assertTrue(hosted.error, hosted.ok)
        assertTrue(joined.error, joined.ok)
        assertNotNull(dee.data.value.findByLabel("B1"))
        assertEquals(Role.HELPER, dee.data.value.person(hal.me!!.id)!!.role)
        assertEquals(Role.DECIDER, hal.data.value.person(dee.me!!.id)!!.role)
    }

    // ---- what a Helper's phone is sent ----------------------------------------------

    @Test
    fun aHelpersPhoneIsNotSentCostsReceiptsOrSerialLabelsAndSyncingBackRemovesNothing() {
        val asha = store("Asha")
        val tv = asha.createItem("TV", null)
        asha.put(asha.data.value.item(tv.id)!!.copy(disposition = Disposition.MOVE))
        assertTrue(asha.setDetails(tv.id) { it.copy(serial = "SN-123", make = "Sony") }.ok)
        val box = asha.createBox("L001", null)
        asha.put(Expense(id = "van", title = "Van hire", amount = 180.0, planned = false))
        val receipt = asha.photo("van", PhotoKind.RECEIPT, 3000)
        val warranty = asha.photo(tv.id, PhotoKind.RECEIPT, 3100)
        val serial = asha.photo(tv.id, PhotoKind.SERIAL, 3200)
        val contents = asha.photo(box.id, PhotoKind.CONTENTS, 3300)
        val dee = invited(asha, "Dee", Role.DECIDER)
        assertTrue(dee.suggestCost(Expense(id = "skip", title = "Skip", amount = 220.0, planned = true)).ok)
        assertTrue(sync(asha, dee).second.ok)

        val hal = invited(asha, "Hal", Role.HELPER)
        // Costs and suggested costs are not there; nor are the receipt and serial-label photos, records or files.
        val d = hal.data.value
        assertTrue(d.expenses.isEmpty())
        assertTrue(d.proposals.none { it.kind == ProposalKind.COST })
        assertEquals(listOf(PhotoKind.CONTENTS), d.photos.map { it.kind })
        assertEquals(listOf(contents.name), hal.photoDir.list()!!.toList())
        // Everything a Helper works with is: the item, the box and its photo. Its serial number and make are not (2.1).
        assertEquals(Disposition.MOVE, d.item(tv.id)!!.disposition)
        assertNull(d.hiddenOf(tv.id))
        assertEquals("", d.item(tv.id)!!.serial)
        assertNotNull(d.box(box.id))

        // The Decider's phone has all of it.
        for (f in listOf(receipt, warranty, serial, contents)) assertTrue(File(dee.photoDir, f.name).isFile)
        assertEquals(180.0, dee.data.value.expense("van")!!.amount, 0.0)
        assertEquals(1, dee.data.value.proposals.count { it.kind == ProposalKind.COST })

        // Hal works: edits the item and the box he was sent, adds a photo, then syncs back both ways round.
        hal.put(hal.data.value.item(tv.id)!!.copy(notes = "Screen wrapped", stage = Stage.LOADED))
        hal.put(hal.data.value.box(box.id)!!.copy(sealed = true))
        val damage = hal.photo(tv.id, PhotoKind.DAMAGE, 2000)
        val costsBefore = asha.data.value.expenses
        val proposalsBefore = asha.data.value.proposals
        val hiddenBefore = asha.data.value.photos.filter { it.kind in HIDDEN_FROM_HELPERS }
        assertEquals(3, hiddenBefore.size)
        assertTrue(sync(asha, hal).second.ok)
        assertTrue(sync(hal, asha).second.ok)
        // ...and with the Decider's phone, which then syncs with Asha's.
        assertTrue(sync(dee, hal).second.ok)
        assertTrue(sync(hal, dee).second.ok)
        assertTrue(sync(asha, dee).second.ok)

        for (s in listOf(asha, dee)) {
            val x = s.data.value
            assertEquals(costsBefore, x.expenses) // nothing removed, nothing blanked
            assertEquals(proposalsBefore.toSet(), x.proposals.toSet())
            assertEquals(hiddenBefore.toSet(), x.photos.filter { it.kind in HIDDEN_FROM_HELPERS }.toSet())
            assertEquals(3000L, File(s.photoDir, receipt.name).length())
            assertEquals(3100L, File(s.photoDir, warranty.name).length())
            assertEquals(3200L, File(s.photoDir, serial.name).length())
            assertEquals(2000L, File(s.photoDir, damage.name).length())
            // Hal's work arrived, and the fields of the records he edited are all still there.
            val item = x.item(tv.id)!!
            assertEquals("Screen wrapped", item.notes)
            assertEquals(Stage.LOADED, item.stage)
            assertEquals("SN-123", x.serialOf(tv.id))
            assertEquals(Disposition.MOVE, item.disposition)
            assertTrue(x.box(box.id)!!.sealed)
        }
        // His phone still has none of it, however often it syncs.
        assertTrue(hal.data.value.expenses.isEmpty())
        assertEquals(setOf(contents.name, damage.name), hal.photoDir.list()!!.toSet())

        // Someone who becomes a Helper keeps what their phone already had, is sent nothing new,
        // and their old copy never overwrites what changes afterwards.
        assertTrue(asha.setRole(dee.me!!.id, Role.HELPER).ok)
        assertTrue(sync(asha, dee).second.ok)
        assertEquals(Role.HELPER, dee.myRole)
        asha.put(Expense(id = "fuel", title = "Fuel", amount = 60.0))
        asha.put(asha.data.value.expense("van")!!.copy(amount = 195.0))
        val later = asha.photo("fuel", PhotoKind.RECEIPT, 1500)
        assertTrue(sync(asha, dee).second.ok)
        assertTrue(sync(dee, asha).second.ok)
        assertNull(dee.data.value.expense("fuel"))
        assertFalse(File(dee.photoDir, later.name).exists())
        assertEquals(180.0, dee.data.value.expense("van")!!.amount, 0.0) // her old copy
        assertEquals(195.0, asha.data.value.expense("van")!!.amount, 0.0) // the real one
        assertEquals(60.0, asha.data.value.expense("fuel")!!.amount, 0.0)
        assertTrue(File(asha.photoDir, later.name).isFile)
    }

    // ---- coming from 1.3 --------------------------------------------------------------

    @Test
    fun twoPhonesThatSharedAMoveIn13StillSyncAfterBothUpdate() {
        // The same move on two phones as 1.3 left it; each had made a change the other had not seen.
        fun saved(sofa: String, tick: String) = """{"version":3,"move":{"name":"Maple Road","setupDone":true,"updatedAt":100},
            "items":[{"id":"sofa","name":"Sofa",$sofa}],
            "boxes":[{"id":"b1","label":"L001","contents":"Plates","updatedAt":10}],
            "tasks":[{"id":"k1","title":"Book the van",$tick}],
            "expenses":[{"id":"e1","title":"Van hire","amount":180.0,"planned":true,"updatedAt":30}]}"""
        val dirA = tmp.newFolder("upgradeA")
        val dirB = tmp.newFolder("upgradeB")
        File(dirA, "data.json").writeText(saved(""""disposition":"SELL","price":120.0,"updatedAt":50""", """"done":true,"updatedAt":70"""))
        File(dirA, "device.json").writeText("""{"name":"Asha","letter":"A"}""")
        File(dirB, "data.json").writeText(saved(""""disposition":"MOVE","updatedAt":60""", """"done":false,"updatedAt":40"""))
        File(dirB, "device.json").writeText("""{"name":"Ben","letter":"B","helper":true}""")
        val a = Store(dirA)
        val b = Store(dirB)
        assertEquals(Role.ORGANISER, a.myRole)
        assertEquals(Role.HELPER, b.myRole)

        // The ordinary code, no invite: both phones were in this move before there were people.
        val (atA, atB) = sync(a, b)
        assertTrue(atA.error, atA.ok)
        assertTrue(atB.error, atB.ok)
        for (s in listOf(a, b)) {
            val d = s.data.value
            assertEquals(setOf("Asha", "Ben"), d.livePeople.map { it.name }.toSet())
            // As in 1.3, the newer copy of each record won.
            assertEquals(Disposition.MOVE, d.item("sofa")!!.disposition)
            assertTrue(d.task("k1")!!.done)
            assertEquals("Maple Road", d.move.name)
            assertEquals(1, d.liveBoxes.size)
        }
        assertEquals(Role.ORGANISER, b.data.value.person(a.me!!.id)!!.role)
        assertEquals(Role.HELPER, a.data.value.person(b.me!!.id)!!.role)
        // Ben's phone was in helper mode, so he is a Helper: from now on it is sent no costs, but keeps the one it had.
        a.put(Expense(id = "e2", title = "Fuel", amount = 40.0))
        assertTrue(sync(a, b).second.ok)
        assertEquals(listOf("e1"), b.data.value.expenses.map { it.id })
        assertEquals(2, a.data.value.liveExpenses.size)
        // And his phone no longer changes decisions, while Asha's does.
        b.put(b.data.value.item("sofa")!!.copy(disposition = Disposition.BIN))
        assertEquals(Disposition.MOVE, b.data.value.item("sofa")!!.disposition)
        assertTrue(a.decide("sofa", Disposition.STORE).ok)
        assertTrue(sync(b, a).second.ok)
        assertEquals(Disposition.STORE, b.data.value.item("sofa")!!.disposition)
    }

    // ---- other versions ---------------------------------------------------------------

    @Test
    fun aPhoneOn13IsToldTheVersionsDifferInItsOwnWords() {
        // A stand-in for a phone on 1.3 showing its code: it hangs up on a first line it does not know,
        // and answers its own kind of hello from a phone on another data layout with "VERSION".
        val server = ServerSocket(0)
        val said = LinkedBlockingQueue<String>()
        val old = thread {
            try {
                while (true) {
                    server.accept().use { s ->
                        val din = DataInputStream(s.getInputStream().buffered())
                        val out = DataOutputStream(s.getOutputStream().buffered())
                        val magic = din.readUTF()
                        if (magic != "HMSYNC2") {
                            said.add("Not a HouseMove phone")
                        } else {
                            repeat(3) { din.readUTF() }
                            if (din.readInt() != 3) {
                                out.writeUTF("VERSION"); out.flush()
                                said.add(WifiSync.VERSION_MISMATCH)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        val mine = store("New phone")
        mine.createBox("L001", null)
        val before = mine.data.value
        val r = WifiSync.join(mine, WifiSync.Invite(local, server.localPort, "TOKEN123"))
        assertEquals(WifiSync.VERSION_MISMATCH, r.error)
        assertEquals("Not a HouseMove phone", said.poll(5, TimeUnit.SECONDS))
        // The last thing the old phone shows is the message that helps.
        assertEquals(WifiSync.VERSION_MISMATCH, said.poll(5, TimeUnit.SECONDS))
        assertEquals(before, mine.data.value)
        server.close()
        old.join(2000)
    }
}
