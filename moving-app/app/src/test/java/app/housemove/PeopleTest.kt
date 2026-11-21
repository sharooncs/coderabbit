package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/** People and roles: who is who, what each role may do, and how data from 1.3 becomes a move with people. */
class PeopleTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private fun store(name: String) = Store(tmp.newFolder(name)) { clock++ }

    /** A phone that has joined [host]'s move as a new person with [role]. */
    private fun joined(host: Store, name: String, role: Role): Store {
        val p = host.addPerson(name, role).valueOrNull()!!
        val phone = store("phone-" + name)
        assertTrue(phone.joinAs(p.id, host.data.value).ok)
        host.mergeIn(phone.data.value)
        return phone
    }

    // ---- the table ------------------------------------------------------------

    @Test
    fun permissionTable() {
        val d = AppData(people = listOf(Person(id = "o", role = Role.ORGANISER, grants = listOf(Grant(Role.ORGANISER)))))
        val organiserOnly = listOf(Action.MANAGE_PEOPLE, Action.MOVE_SETTINGS, Action.ERASE, Action.RESTORE_BACKUP, Action.DECIDE, Action.APPROVE)
        for (a in Action.entries) assertTrue("Organiser $a", d.can(Role.ORGANISER, a))
        for (a in organiserOnly) {
            assertFalse("Decider $a", d.can(Role.DECIDER, a))
            assertFalse("Helper $a", d.can(Role.HELPER, a))
        }
        // A Decider suggests, and carries out once approved; a Helper does neither.
        assertTrue(d.can(Role.DECIDER, Action.SUGGEST))
        assertTrue(d.can(Role.DECIDER, Action.CARRY_OUT))
        assertFalse(d.can(Role.HELPER, Action.SUGGEST))
        assertFalse(d.can(Role.HELPER, Action.CARRY_OUT))
        // Everyone does the work and may ask for a decision.
        for (r in Role.entries) {
            assertTrue(d.can(r, Action.WORK))
            assertTrue(d.can(r, Action.ASK))
        }
        assertEquals(listOf("Organiser", "Decider", "Helper"), Role.entries.map { it.label })
        // A move with nobody to run it is not restricted, so it cannot get stuck.
        assertTrue(AppData().can(Role.HELPER, Action.MOVE_SETTINGS))
        // Every refusal comes with words to show.
        for (a in organiserOnly) assertNotNull(d.refusal(Person(id = "h", role = Role.HELPER), a))
        assertNull(d.refusal(Person(id = "o", role = Role.ORGANISER), Action.ERASE))

        // The phone's side of it: its person's role, or the least rights when its person is not in the move at all.
        assertEquals(Role.ORGANISER, d.roleOf(DeviceInfo(personId = "o")))
        assertTrue(d.can(DeviceInfo(personId = "o"), Action.DECIDE))
        assertEquals(Role.HELPER, d.roleOf(DeviceInfo(personId = "gone")))
        assertFalse(d.can(DeviceInfo(personId = "gone"), Action.DECIDE))
        assertNotNull(d.refusal(null, Action.DECIDE))
        // Someone removed can still use what is on their phone, and nothing more.
        val removed = d.copy(people = d.people + Person(id = "x", role = Role.ORGANISER, removed = true))
        assertFalse(removed.can(DeviceInfo(personId = "x"), Action.MANAGE_PEOPLE))
        assertTrue(removed.can(DeviceInfo(personId = "x"), Action.WORK))
        assertEquals("You have been removed from this move.", removed.refusal(removed.person("x"), Action.DECIDE))
        // Data from before 2.0, looked at without a store: the old helper switch decides.
        assertEquals(Role.ORGANISER, AppData().roleOf(DeviceInfo()))
        assertEquals(Role.HELPER, AppData().roleOf(DeviceInfo(helper = true)))
    }

    // ---- coming from 1.3 ------------------------------------------------------

    private val saved13 = """{"version":3,
        "move":{"name":"Maple Road","moveDay":20500,"originName":"Flat","destName":"House","vanCapacityM3":12.5,"currency":"£",
                "labelsPrinted":42,"kind":"MOVERS","off":["BUDGET"],"setupDone":true,"updatedAt":900},
        "rooms":[{"id":"r1","home":"ORIGIN","name":"Kitchen","code":"KIT","color":2,"mapsTo":"r2","packed":true,"sort":1,"updatedAt":10},
                 {"id":"r2","home":"DEST","name":"Kitchen","code":"KIT","doorWidthCm":76.0,"sort":1,"updatedAt":11}],
        "items":[{"id":"i1","name":"Sofa","label":"L003","originRoomId":"r1","destRoomId":"r2","disposition":"SELL","listed":true,"gone":true,
                  "price":120.0,"deadlineDay":20490,"widthCm":200.0,"fragile":true,"stage":"AT_ORIGIN","notes":"blue","createdAt":20,"updatedAt":30},
                 {"id":"i2","name":"Fridge","disposition":"MOVE","stage":"LOADED","tripId":"t1","serial":"SN1","keepUpright":true,"updatedAt":31},
                 {"id":"i3","name":"Gone lamp","deleted":true,"updatedAt":32}],
        "boxes":[{"id":"b1","label":"L001","originRoomId":"r1","destRoomId":"r2","contents":"Plates","size":"L","fragile":true,"priority":"DAY_1",
                  "sealed":true,"stage":"DELIVERED","tripId":"t1","packedBy":"Asha","createdAt":40,"updatedAt":41}],
        "photos":[{"id":"p1","ownerId":"e1","kind":"RECEIPT","file":"p1.jpg","createdAt":50,"updatedAt":50},
                  {"id":"p2","ownerId":"b1","kind":"CONTENTS","file":"p2.jpg","note":"top layer","updatedAt":51}],
        "tasks":[{"id":"k1","title":"Book the van","offsetDays":-28,"category":"Van","done":true,"notes":"done online","updatedAt":60},
                 {"id":"k2","title":"Dentist","dueDay":20480,"updatedAt":61}],
        "expenses":[{"id":"e1","title":"Van hire","amount":180.5,"category":"Van","day":20499,"planned":true,"updatedAt":70}],
        "supplies":[{"id":"s1","name":"Tape","need":4,"have":1,"updatedAt":80}],
        "trips":[{"id":"t1","name":"Trip 1","sort":1,"updatedAt":81}],
        "readings":[{"id":"m1","home":"DEST","kind":"Gas","value":"0042","day":20500,"updatedAt":82}],
        "entries":[{"id":"n1","boxId":"b1","code":"9780140328721","title":"Fantastic Mr. Fox","author":"Roald Dahl","createdAt":83,"updatedAt":83}]}"""

    @Test
    fun dataSavedBy13LoadsUnchangedAndThisPhoneBecomesAnOrganiser() {
        val dir = tmp.newFolder("from13")
        File(dir, "data.json").writeText(saved13)
        File(dir, "device.json").writeText("""{"name":"Asha","letter":"Q","nextSeq":7,"currentRoomId":"r1","lastSyncAt":5,"bookLookup":true}""")
        val old = AppJson.decodeFromString<AppData>(saved13)
        val s = Store(dir) { clock++ }
        val d = s.data.value

        assertEquals(SCHEMA, d.version)
        assertEquals(4, SCHEMA)
        // Every value 1.3 saved is still there: compare with the stamps (which are new) taken off.
        assertEquals(old.move, d.move.copy(changedBy = Stamp()))
        assertEquals(old.rooms, d.rooms)
        assertEquals(old.items, d.items.map { it.copy(decisionBy = Stamp(), roomBy = Stamp(), outcomeBy = Stamp(), deletedBy = Stamp()) })
        assertEquals(old.boxes, d.boxes)
        assertEquals(old.photos, d.photos)
        assertEquals(old.tasks, d.tasks.map { it.copy(planBy = Stamp(), doneBy = Stamp()) })
        assertEquals(old.expenses, d.expenses.map { it.copy(costBy = Stamp(), paidBy = Stamp()) })
        assertEquals(old.supplies, d.supplies)
        assertEquals(old.trips, d.trips)
        assertEquals(old.readings, d.readings)
        assertEquals(old.entries, d.entries)

        // The new fields have their quiet defaults.
        val box = d.box("b1")!!
        assertEquals(ContainerKind.CARTON, box.kind)
        assertEquals("", box.category)
        assertNull(box.parentId)
        assertNull(d.item("i1")!!.boxId)
        assertNull(d.item("i1")!!.soldFor)
        assertNull(d.task("k1")!!.assignedTo)
        assertTrue(d.proposals.isEmpty() && d.activity.isEmpty())
        // What was decided in 1.3 has no author, and still counts.
        assertEquals("", d.item("i1")!!.decisionBy.by)
        assertEquals(Disposition.SELL, d.item("i1")!!.disposition)
        assertEquals(1, d.movables().count { !it.isBox }) // the fridge; the sofa is being sold

        // The phone gets a person of its own, an Organiser, named and lettered as the phone was.
        val me = s.me!!
        assertEquals(listOf(me), d.people)
        assertEquals("Asha", me.name)
        assertEquals("Q", me.letter)
        assertEquals(Role.ORGANISER, me.role)
        assertTrue(me.joinedAt > 0)
        assertEquals(me.id, s.device.value.personId)
        assertEquals(me, d.me(s.device.value))
        assertEquals(Role.ORGANISER, d.roleOf(s.device.value))
        assertFalse(s.device.value.helper)
        assertEquals(7, s.device.value.nextSeq)
        assertEquals("Q7", s.nextHandCode())
        // ...and can go on working exactly as before.
        s.put(d.item("i2")!!.copy(disposition = Disposition.STORE))
        s.setMove { it.copy(name = "Maple Road move") }
        assertEquals(Disposition.STORE, s.data.value.item("i2")!!.disposition)
        assertEquals("Maple Road move", s.data.value.move.name)
        assertEquals(42, s.data.value.move.labelsPrinted)

        // Opening it again changes nothing and does not make a second person.
        s.close()
        val again = Store(dir) { clock++ }
        assertEquals(s.data.value, again.data.value)
        assertEquals(me.id, again.device.value.personId)
        assertEquals(1, again.data.value.people.size)
    }

    @Test
    fun aPhoneThatWasInHelperModeBecomesAHelper() {
        val dir = tmp.newFolder("helper13")
        File(dir, "data.json").writeText(saved13)
        File(dir, "device.json").writeText("""{"name":"Ben","letter":"B","helper":true}""")
        val s = Store(dir) { clock++ }
        assertEquals(Role.HELPER, s.myRole)
        assertTrue(s.device.value.helper)
        // It is the only phone, so there is no Organiser to ask and nothing is held back.
        assertTrue(s.can(Action.DECIDE))
        s.put(s.data.value.item("i2")!!.copy(disposition = Disposition.DONATE))
        assertEquals(Disposition.DONATE, s.data.value.item("i2")!!.disposition)
        assertEquals("", s.data.value.item("i2")!!.decisionBy.by)
    }

    @Test
    fun aBackupFrom13IsRestoredInFull() {
        val zip = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("data.json")); z.write(saved13.toByteArray()); z.closeEntry()
            }
        }.toByteArray()
        val s = store("restore13")
        Backup.import(s, ByteArrayInputStream(zip))
        val d = s.data.value
        assertEquals(Disposition.SELL, d.item("i1")!!.disposition)
        assertEquals(120.0, d.item("i1")!!.price!!, 0.0)
        assertTrue(d.item("i1")!!.gone)
        assertEquals("Van hire", d.expense("e1")!!.title)
        assertTrue(d.task("k1")!!.done)
        assertEquals("Maple Road", d.move.name)
        assertEquals(SCHEMA, d.version)
    }

    // ---- adding, changing, removing ------------------------------------------

    @Test
    fun organiserAddsPeopleWithLettersNobodyElseHas() {
        val o = store("o")
        o.setDevice { it.copy(name = "Asha", letter = "A") }
        val names = (1..30).map { "Person $it" }
        val people = names.map { o.addPerson(it, Role.HELPER).valueOrNull()!! }
        val letters = o.data.value.people.map { it.letter }
        assertEquals(letters.size, letters.toSet().size)
        assertEquals("B", people[0].letter) // A is the organiser's own
        assertEquals("C", people[1].letter)
        assertEquals("AA", people[25].letter) // after Z
        assertTrue(people.all { it.joinedAt == 0L && !it.removed && it.role == Role.HELPER })
        assertTrue(o.data.value.duplicateLetters().isEmpty())

        // No name typed: one is made up.
        assertEquals("Decider 1", o.addPerson("  ", Role.DECIDER).valueOrNull()!!.name)
        // Any number of Organisers and Deciders.
        assertTrue(o.addPerson("Second organiser", Role.ORGANISER).ok)
        assertTrue(o.addPerson("Another decider", Role.DECIDER).ok)
        assertEquals(2, o.data.value.livePeople.count { it.role == Role.ORGANISER })
        assertEquals(Role.ORGANISER, o.data.value.livePeople.first().role) // Organisers are listed first

        // A letter can be changed, but not to one in use.
        assertFalse(o.setLetter(people[0].id, "c").ok)
        assertEquals("ZZ", o.setLetter(people[0].id, "zz").valueOrNull()!!.letter)
        // The phone's own name and letter are its person's.
        o.setDevice { it.copy(name = "Asha K", letter = "C") } // C is taken
        assertEquals("Asha K", o.me!!.name)
        assertEquals("A", o.me!!.letter)
        assertEquals("A", o.device.value.letter)
        o.setDevice { it.copy(letter = "B") } // free again since Person 1 moved to ZZ
        assertEquals("B", o.me!!.letter)
        assertEquals("B1", o.nextHandCode())
    }

    @Test
    fun onlyAnOrganiserManagesPeopleAndTheLastOrganiserStays() {
        val o = store("o")
        o.setDevice { it.copy(name = "Asha") }
        val dee = joined(o, "Dee", Role.DECIDER)
        val hal = joined(o, "Hal", Role.HELPER)
        assertEquals(Role.DECIDER, dee.myRole)
        assertEquals(Role.HELPER, hal.myRole)
        assertTrue(hal.device.value.helper)
        assertFalse(dee.device.value.helper)

        for (phone in listOf(dee, hal)) {
            val before = phone.data.value.people
            assertEquals("Only an Organiser can add people, change roles or remove people.", phone.addPerson("Mate", Role.HELPER).reason)
            assertFalse(phone.setRole(phone.me!!.id, Role.ORGANISER).ok)
            assertFalse(phone.removePerson(o.me!!.id).ok)
            assertEquals(before, phone.data.value.people)
            // Anyone may correct their own name.
            assertTrue(phone.renamePerson(phone.me!!.id, phone.me!!.name + "!").ok)
            assertFalse(phone.renamePerson(o.me!!.id, "Boss").ok)
        }

        // The only Organiser cannot step down or be removed.
        assertEquals("A move needs at least one Organiser. Make someone else an Organiser first.", o.setRole(o.me!!.id, Role.HELPER).reason)
        assertFalse(o.removePerson(o.me!!.id).ok)
        // With a second Organiser they can.
        assertEquals(Role.ORGANISER, o.setRole(dee.me!!.id, Role.ORGANISER).valueOrNull()!!.role)
        assertEquals(Role.DECIDER, o.setRole(o.me!!.id, Role.DECIDER).valueOrNull()!!.role)
        assertFalse(o.can(Action.MANAGE_PEOPLE))
        // The old helper switch does nothing once there are other people.
        o.setDevice { it.copy(helper = true) }
        assertEquals(Role.DECIDER, o.myRole)
        assertFalse(o.device.value.helper)

        // Dee's phone learns of her new role at the next sync and can then remove and restore people.
        dee.mergeIn(o.data.value)
        assertEquals(Role.ORGANISER, dee.myRole)
        val halId = hal.me!!.id
        assertTrue(dee.removePerson(halId).valueOrNull()!!.removed)
        assertTrue(dee.data.value.livePeople.none { it.id == halId })
        assertNotNull(dee.data.value.person(halId)) // still on record
        assertFalse(dee.restorePerson(halId).valueOrNull()!!.removed)

        // Who did what is in the log.
        val log = dee.data.value.latestActivity(20).map { dee.data.value.line(it) }
        assertTrue(log.toString(), log.any { it == "Asha added Dee as a Decider" })
        assertTrue(log.toString(), log.any { it == "Asha made Dee an Organiser" })
        assertTrue(log.toString(), log.any { it.endsWith("removed Hal") })
    }

    @Test
    fun theOldHelperSwitchStillWorksOnAPhoneOnItsOwn() {
        val s = store("solo")
        s.setDevice { it.copy(name = "Sam") }
        assertEquals(Role.ORGANISER, s.myRole)
        s.setDevice { it.copy(helper = true) }
        assertTrue(s.device.value.helper)
        assertEquals(Role.HELPER, s.myRole)
        // Nothing is locked while it is the only phone.
        s.put(Task(id = "t", title = "Book van"))
        assertEquals(1, s.data.value.liveTasks.size)
        s.setDevice { it.copy(helper = false) }
        assertFalse(s.device.value.helper)
        assertEquals(Role.ORGANISER, s.myRole)
        // The flag always says "my role is Helper", also after a restart.
        s.setDevice { it.copy(helper = true) }
        s.close()
        val again = Store(tmp.root.resolve("solo")) { clock++ }
        assertTrue(again.device.value.helper)
        assertEquals(Role.HELPER, again.myRole)
    }

    // ---- the store refuses what a role may not do ---------------------------------

    @Test
    fun deciderAndHelperCannotChangeWhatIsProtected() {
        val o = store("o")
        o.setDevice { it.copy(name = "Asha") }
        o.setMove { it.copy(name = "Our move", setupDone = true) }
        val newKitchen = o.createRoom(Home.DEST, "Kitchen")
        val newHall = o.createRoom(Home.DEST, "Hall")
        val oldKitchen = o.createRoom(Home.ORIGIN, "Kitchen").copy(mapsTo = newKitchen.id).also { o.put(it) }
        val sofa = o.createItem("Sofa", oldKitchen.id)
        o.put(o.data.value.item(sofa.id)!!.copy(disposition = Disposition.SELL, price = 50.0))
        o.put(Task(id = "task", title = "Book the van"))
        o.put(Expense(id = "cost", title = "Van", amount = 100.0, planned = true))
        val dee = joined(o, "Dee", Role.DECIDER)
        val hal = joined(o, "Hal", Role.HELPER)

        for (phone in listOf(dee, hal)) {
            val who = phone.me!!.name
            val item = phone.data.value.item(sofa.id)!!
            // The plain save keeps the protected parts as they were and takes the rest.
            phone.put(item.copy(disposition = Disposition.BIN, price = 1.0, destRoomId = newHall.id, gone = true, listed = true, soldFor = 9.0, notes = "scratched", fragile = true))
            val after = phone.data.value.item(sofa.id)!!
            assertEquals(who, Disposition.SELL, after.disposition)
            assertEquals(who, 50.0, after.price!!, 0.0)
            assertEquals(who, newKitchen.id, after.destRoomId)
            assertFalse(who, after.gone || after.listed)
            assertNull(who, after.soldFor)
            assertEquals(who, "scratched", after.notes)
            assertTrue(who, after.fragile)
            phone.updateItems(listOf(sofa.id)) { it.copy(disposition = Disposition.DONATE, stage = Stage.LOADED) }
            assertEquals(who, Disposition.SELL, phone.data.value.item(sofa.id)!!.disposition)
            assertEquals(who, Stage.LOADED, phone.data.value.item(sofa.id)!!.stage) // loading is anyone's work

            // The calls that answer say why.
            assertNotNull(who, phone.decide(sofa.id, Disposition.BIN).reason)
            assertNotNull(who, phone.planRoom(sofa.id, newHall.id).reason)
            assertNotNull(who, phone.carryOut(sofa.id, gone = true).reason)
            assertNotNull(who, phone.changeMove { it.copy(name = "Mine now") }.reason)
            assertNotNull(who, phone.erase().reason)
            assertNotNull(who, phone.assign("task", phone.me!!.id).reason)
            assertNotNull(who, phone.tick("task").reason)
            assertNotNull(who, phone.approve("nothing").reason)

            // Settings, tasks and costs: not theirs to change. Printing more labels is fine.
            phone.setMove { it.copy(name = "Mine now", labelsPrinted = 30) }
            assertEquals(who, "Our move", phone.data.value.move.name)
            assertEquals(who, 30, phone.data.value.move.labelsPrinted)
            phone.put(Task(id = "new-$who", title = "Sneaky"))
            phone.put(phone.data.value.task("task")!!.copy(title = "Changed", done = true))
            phone.delete(phone.data.value.task("task")!!)
            assertEquals(who, listOf("Book the van"), phone.data.value.liveTasks.map { it.title })
            assertFalse(who, phone.data.value.task("task")!!.done)

            // A new item of their own gets the room its old room maps to, and no decision.
            val added = phone.createItem("Lamp", oldKitchen.id)
            assertEquals(who, newKitchen.id, phone.data.value.item(added.id)!!.destRoomId)
            phone.put(phone.data.value.item(added.id)!!.copy(disposition = Disposition.MOVE))
            assertEquals(who, Disposition.UNDECIDED, phone.data.value.item(added.id)!!.disposition)

            // A backup cannot be restored by them.
            val zip = ByteArrayOutputStream().also { Backup.export(o, it) }.toByteArray()
            try {
                Backup.import(phone, ByteArrayInputStream(zip))
                fail("$who restored a backup")
            } catch (e: IllegalArgumentException) {
                assertEquals("Only an Organiser can restore a backup.", e.message)
            }
        }
        // The Decider has the cost on their phone (the Helper would not be sent it) and cannot change or pay it.
        dee.put(dee.data.value.expense("cost")!!.copy(amount = 1.0, planned = false))
        dee.put(Expense(id = "extra", title = "Pizza", amount = 30.0))
        assertEquals(100.0, dee.data.value.expense("cost")!!.amount, 0.0)
        assertTrue(dee.data.value.expense("cost")!!.planned)
        assertNull(dee.data.value.expense("extra"))
        assertNotNull(dee.markPaid("cost").reason)
        val receipt = dee.newPhotoFile().apply { writeBytes(ByteArray(10)) }
        assertFalse(dee.attachReceipt("cost", receipt).ok)
        assertFalse(receipt.exists())
        val again = dee.newPhotoFile().apply { writeBytes(ByteArray(10)) }
        dee.addPhoto("cost", PhotoKind.RECEIPT, again)
        assertTrue(dee.data.value.photosOf("cost").isEmpty())

        // The Organiser does all of it.
        assertTrue(o.decide(sofa.id, Disposition.DONATE).ok)
        assertTrue(o.planRoom(sofa.id, newHall.id).ok)
        assertTrue(o.carryOut(sofa.id, gone = true).ok)
        assertTrue(o.markPaid("cost").ok)
        assertTrue(o.changeMove { it.copy(name = "Renamed") }.ok)
        assertTrue(o.assign("task", hal.me!!.id).ok)
        // A task given to the Helper is theirs to tick off, and only that.
        hal.mergeIn(o.data.value)
        assertTrue(hal.tick("task").ok)
        hal.put(hal.data.value.task("task")!!.copy(done = false, title = "Mine"))
        assertFalse(hal.data.value.task("task")!!.done)
        assertEquals("Book the van", hal.data.value.task("task")!!.title)
        assertNotNull(dee.tick("task").reason)
        assertTrue(o.erase().ok)
        assertTrue(o.data.value.liveItems.isEmpty())
    }

    @Test
    fun aNewPhoneCanTakeOverAPersonAfterRestoringABackup() {
        val o = store("o")
        o.setDevice { it.copy(name = "Asha", letter = "A") }
        o.createBox("L001", null)
        val dee = joined(o, "Dee", Role.DECIDER)
        val backup = ByteArrayOutputStream().also { Backup.export(o, it) }.toByteArray()

        // Asha's phone is lost. A new one starts as its own person, restores the backup, and says who it is.
        val replacement = store("replacement")
        val started = replacement.me!!.id
        Backup.import(replacement, ByteArrayInputStream(backup))
        assertEquals(3, replacement.data.value.people.size)
        assertNotNull(replacement.becomePerson("nobody").reason)
        val me = replacement.becomePerson(o.me!!.id).valueOrNull()!!
        assertEquals("Asha", me.name)
        assertEquals(o.me!!.id, replacement.device.value.personId)
        assertEquals("Asha", replacement.device.value.name)
        assertEquals("A", replacement.device.value.letter)
        assertEquals(Role.ORGANISER, replacement.myRole)
        // The person it started as steps out, so the move does not gain an Organiser nobody knows.
        assertTrue(replacement.data.value.person(started)!!.removed)
        assertEquals(setOf("Asha", "Dee"), replacement.data.value.livePeople.map { it.name }.toSet())
        // It carries on as Asha, and the others' phones accept what it does.
        assertTrue(replacement.setRole(dee.me!!.id, Role.ORGANISER).ok)
        dee.mergeIn(replacement.data.value)
        assertEquals(Role.ORGANISER, dee.myRole)
        // Someone who has been removed cannot be taken over.
        val hal = replacement.addPerson("Hal", Role.HELPER).valueOrNull()!!
        replacement.removePerson(hal.id)
        assertNotNull(store("another").apply { mergeIn(replacement.data.value) }.becomePerson(hal.id).reason)
    }

    @Test
    fun erasingStartsAFreshPersonSoAnErasedPhoneCannotComeBackWithMoreRights() {
        val o = store("o")
        val hal = joined(o, "Hal", Role.HELPER)
        val was = hal.me!!.id
        // The old screen erases with replaceAll, whoever is asking.
        hal.replaceAll(AppData())
        assertNotEquals(was, hal.me!!.id)
        assertTrue(hal.can(Action.DECIDE)) // in its own, empty move there is nobody to ask
        // Back in the real move it is a stranger, and Hal is still a Helper there.
        o.mergeIn(hal.data.value)
        assertEquals(Role.HELPER, o.data.value.person(was)!!.role)
        assertEquals(1, o.data.value.people.count { it.role == Role.ORGANISER })
    }
}
