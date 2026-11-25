package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/** The awkward situations: clocks that disagree, conflicts, damaged files, cut-off transfers, big moves. */
class EdgeCaseTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(name: String, clock: () -> Long = System::currentTimeMillis) = Store(tmp.newFolder(name), clock)

    /** Exchange both ways without the network, as a sync does. */
    /**
     * A phone that has joined [host]'s move as [name], as an invite brings it in. Since 2.1 two phones
     * each with a move of its own never mix them, so phones that sync here are of one move.
     */
    private fun joinedTo(host: Store, name: String, clock: () -> Long = System::currentTimeMillis): Store {
        val p = host.addPerson(name, Role.ORGANISER).valueOrNull()!!
        return store(name, clock).also { assertTrue(it.joinAs(p.id, host.data.value).ok) }
    }

    private fun exchange(a: Store, b: Store) {
        val merged = a.mergeIn(b.data.value)
        b.mergeIn(merged)
    }

    private fun realSync(host: Store, joiner: Store): WifiSync.Result {
        val done = CountDownLatch(1)
        val h = WifiSync.host(host) { done.countDown() }
        try {
            val r = WifiSync.join(joiner, h.invite(listOf("127.0.0.1")))
            done.await(10, TimeUnit.SECONDS)
            return r
        } finally {
            h.stop()
        }
    }

    // ---- clocks and conflicts ----------------------------------------------

    @Test
    fun editMadeAfterSyncWinsEvenWhenThatPhonesClockIsHoursBehind() {
        var ta = 10_000_000L
        var tb = 5_000L // B's clock is far behind A's
        val a = store("a") { ta++ }
        val b = store("b") { tb++ }
        val box = a.createBox("L001", null)
        a.put(a.data.value.box(box.id)!!.copy(contents = "from A"))
        exchange(a, b)
        b.put(b.data.value.box(box.id)!!.copy(contents = "B fixed it later"))
        exchange(a, b)
        assertEquals("B fixed it later", a.data.value.box(box.id)!!.contents)
        assertEquals("B fixed it later", b.data.value.box(box.id)!!.contents)
    }

    @Test
    fun stampsNeverGoBackwardsEvenIfTheClockDoes() {
        var t = 1_000L
        val s = store("mono") { t }
        val first = s.now()
        t = 10 // someone set the phone's clock back
        val second = s.now()
        assertTrue(second > first)
    }

    @Test
    fun sameBoxEditedOnBothPhonesEndsUpIdenticalEverywhere() {
        var ta = 1_000L
        var tb = 1_000L
        val a = store("a") { ta += 10; ta }
        val b = store("b") { tb += 7; tb }
        val box = a.createBox("L001", null)
        exchange(a, b)
        a.put(a.data.value.box(box.id)!!.copy(contents = "plates"))
        b.put(b.data.value.box(box.id)!!.copy(contents = "bowls", fragile = true))
        exchange(a, b)
        assertEquals(a.data.value.box(box.id), b.data.value.box(box.id))
    }

    @Test
    fun laterDeleteRemovesAndLaterEditRestores() {
        var t = 1_000L
        val a = store("a") { t++ }
        val b = joinedTo(a, "b") { t++ }
        val one = a.createBox("L001", null)
        val two = a.createBox("L002", null)
        exchange(a, b)
        // Box one: edited on A, then deleted on B -> gone everywhere.
        a.put(a.data.value.box(one.id)!!.copy(contents = "x"))
        b.delete(b.data.value.box(one.id)!!)
        // Box two: deleted on A, then edited on B -> the later edit brings it back.
        a.delete(a.data.value.box(two.id)!!)
        b.put(b.data.value.box(two.id)!!.copy(contents = "still needed"))
        exchange(a, b)
        for (s in listOf(a, b)) {
            assertNull(s.data.value.box(one.id))
            assertEquals("still needed", s.data.value.box(two.id)!!.contents)
        }
    }

    @Test
    fun twoPhonesUsingTheSameHandwrittenCodeBothSurviveAndAreFlagged() {
        var ta = 1_000L
        var tb = 2_000L
        val a = store("a") { ta++ }
        a.setDevice { it.copy(letter = "A", nextSeq = 1) }
        val b = joinedTo(a, "b") { tb++ }
        // Apart, each phone takes the letter Q for itself: neither knows the other has.
        a.setDevice { it.copy(letter = "Q", nextSeq = 1) }
        b.setDevice { it.copy(letter = "Q", nextSeq = 1) }
        a.createBox(a.nextHandCode(), null)
        b.createBox(b.nextHandCode(), null)
        exchange(a, b)
        assertEquals(2, a.data.value.liveBoxes.size)
        assertEquals(listOf("Q1"), a.data.value.duplicateLabels())
        assertTrue(a.data.value.issues().any { it.severe && it.text.contains("Q1") })
        // After the clash is known, the next code on either phone avoids it.
        assertEquals("Q2", a.nextHandCode())
        // And it cannot happen again: the phone that came to the letter second has taken another.
        assertEquals("Q", a.device.value.letter)
        assertNotEquals("Q", b.device.value.letter)
        assertTrue(b.device.value.letter.startsWith("Q"))
        exchange(a, b)
        assertTrue(a.data.value.duplicateLetters().isEmpty())
        assertNotEquals(a.nextHandCode(), b.nextHandCode())
    }

    @Test
    fun labelIsFreeAgainOnceItsBoxIsDeleted() {
        val s = store("reuse")
        val box = s.createBox("L009", null)
        assertNotNull(s.data.value.findByLabel("L009"))
        s.delete(s.data.value.box(box.id)!!)
        assertNull(s.data.value.findByLabel("L009"))
        s.createBox("L009", null)
        assertTrue(s.data.value.duplicateLabels().isEmpty())
    }

    // ---- photos over the network -------------------------------------------

    @Test
    fun photoDeletedOnOnePhoneDisappearsFromTheOther() {
        val a = store("a")
        val b = joinedTo(a, "b")
        val box = a.createBox("L001", null)
        val f = a.newPhotoFile().apply { writeBytes(ByteArray(3000) { 3 }) }
        val photo = a.addPhoto(box.id, PhotoKind.CONTENTS, f)
        assertTrue(realSync(a, b).ok)
        assertTrue(File(b.photoDir, f.name).isFile)

        a.delete(a.data.value.photos.first { it.id == photo.id })
        val r = realSync(a, b)
        assertTrue(r.ok)
        assertEquals(0, r.photosSent + r.photosReceived)
        assertFalse(File(b.photoDir, f.name).exists())
        assertTrue(b.data.value.photosOf(box.id).isEmpty())
    }

    @Test
    fun missingPhotoFileIsFetchedOnTheNextSyncAndLostOnesDoNotBreakIt() {
        val a = store("a")
        val b = joinedTo(a, "b")
        val box = a.createBox("L001", null)
        val good = a.newPhotoFile().apply { writeBytes(ByteArray(4000) { 4 }) }
        a.addPhoto(box.id, PhotoKind.CONTENTS, good)
        val lost = a.newPhotoFile() // record exists but the file was never written
        a.addPhoto(box.id, PhotoKind.CONTENTS, lost)
        b.mergeIn(a.data.value) // B knows the records but has no files yet
        val r = realSync(a, b)
        assertTrue(r.error, r.ok)
        assertEquals(4000L, File(b.photoDir, good.name).length())
        assertFalse(File(b.photoDir, lost.name).exists())
    }

    @Test
    fun transferCutOffHalfWayLeavesNoBrokenPhotoAndTheNextSyncRepairsIt() {
        val joiner = store("joiner")
        joiner.createBox("L001", null)
        // A fake phone that follows the protocol, then drops the connection in the middle of a photo.
        val server = ServerSocket(0)
        val fake = thread {
            server.accept().use { s ->
                val din = DataInputStream(s.getInputStream().buffered())
                val out = DataOutputStream(s.getOutputStream().buffered())
                // Hello: magic, code, name, letter, schema, who they are, who the invite is for.
                repeat(4) { din.readUTF() }; din.readInt(); repeat(2) { din.readUTF() }
                out.writeUTF("OK"); out.writeUTF("Flaky"); out.writeUTF("F"); out.writeUTF(""); out.flush()
                assertEquals("GO", din.readUTF())
                val data = ByteArray(din.readInt()).also { din.readFully(it) }
                out.writeInt(data.size); out.write(data); out.flush()
                repeat(din.readInt()) { din.readUTF() }
                out.writeInt(0); out.flush()
                while (din.readUTF().isNotEmpty()) din.skipBytes(din.readLong().toInt())
                out.writeUTF("ghost.jpg"); out.writeLong(5000); out.write(ByteArray(100)); out.flush()
            }
        }
        val r = WifiSync.join(joiner, WifiSync.Invite(listOf("127.0.0.1"), server.localPort, "ANY"))
        fake.join(5000)
        server.close()
        assertFalse(r.ok)
        assertTrue(joiner.photoDir.listFiles()!!.isEmpty())
        assertEquals(1, joiner.data.value.liveBoxes.size)

        // Leftovers from a crash mid-transfer are cleared when the app next starts.
        File(joiner.photoDir, "old.jpg.part").writeBytes(ByteArray(10))
        joiner.flush()
        val reopened = Store(tmp.root.resolve("joiner"))
        assertTrue(reopened.photoDir.listFiles()!!.isEmpty())
    }

    @Test
    fun strangerOnTheNetworkCannotBreakTheHost() {
        val a = store("a")
        a.createBox("L001", null)
        val b = joinedTo(a, "b")
        val results = ArrayList<WifiSync.Result>()
        val host = WifiSync.host(a) { synchronized(results) { results.add(it) } }
        try {
            Socket("127.0.0.1", host.port).use { it.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) }
            Socket("127.0.0.1", host.port).close()
            val r = WifiSync.join(b, host.invite(listOf("127.0.0.1")))
            assertTrue(r.error, r.ok)
            assertEquals(1, b.data.value.liveBoxes.size)
            assertEquals(1, a.data.value.liveBoxes.size)
        } finally {
            host.stop()
        }
    }

    @Test
    fun unreachablePhoneGivesAnErrorQuicklyInsteadOfHanging() {
        val s = store("alone")
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val started = System.currentTimeMillis()
        val r = WifiSync.join(s, WifiSync.Invite(listOf("127.0.0.1"), closed, "X"), connectTimeoutMs = 1500)
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("same Wi-Fi"))
        assertTrue(System.currentTimeMillis() - started < 5000)
    }

    // ---- damaged files and backups ------------------------------------------

    @Test
    fun damagedDataFileFallsBackToThePreviousCopyAndIsKept() {
        val dir = tmp.newFolder("damaged")
        Store(dir).apply { createBox("L001", null); flush(); createBox("L002", null); close() }
        File(dir, "data.json").writeText("{ this is not json")
        val s = Store(dir)
        assertEquals(listOf("L001"), s.data.value.liveBoxes.map { it.label })
        assertNotNull(s.loadWarning)
        assertTrue(File(dir, "data.json.damaged").isFile)

        File(dir, "data.json").writeText("garbage")
        File(dir, "data.json.bak").writeText("garbage too")
        val empty = Store(dir)
        assertTrue(empty.data.value.liveBoxes.isEmpty())
        assertTrue(empty.loadWarning!!.contains("backup"))
        assertEquals("garbage", File(dir, "data.json.damaged").readText())
    }

    @Test
    fun dataWrittenByANewerVersionStillLoads() {
        val dir = tmp.newFolder("newer")
        File(dir, "data.json").writeText(
            """{"version":9,"futureThing":{"a":1},"move":{"name":"M","setupDone":true,"newField":3},
               "boxes":[{"id":"b1","label":"L001","stage":"TELEPORTED","priority":"ASAP","size":"HUGE","extra":true}],
               "items":[{"id":"i1","name":"Sofa","disposition":"GIFT"}]}"""
        )
        val d = Store(dir).data.value
        assertTrue(d.move.setupDone)
        assertEquals(Stage.AT_ORIGIN, d.box("b1")!!.stage)
        assertEquals(Priority.WHENEVER, d.box("b1")!!.priority)
        assertEquals(Disposition.UNDECIDED, d.item("i1")!!.disposition)
    }

    @Test
    fun filesThatAreNotBackupsAreRejectedAndChangeNothing() {
        val s = store("reject")
        s.createBox("L001", null)
        val before = s.data.value
        for (bytes in listOf(ByteArray(0), "just some text".toByteArray(), ByteArray(5000) { (it * 7).toByte() })) {
            try {
                Backup.import(s, ByteArrayInputStream(bytes))
                fail("should have been rejected")
            } catch (_: Exception) {
            }
        }
        val zipWithoutData = ByteArrayOutputStream().also { o ->
            ZipOutputStream(o).use { z -> z.putNextEntry(ZipEntry("holiday.txt")); z.write(1); z.closeEntry() }
        }.toByteArray()
        try {
            Backup.import(s, ByteArrayInputStream(zipWithoutData))
            fail("should have been rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("backup"))
        }
        assertEquals(before, s.data.value)
    }

    @Test
    fun backupRestoresOntoAnEmptyPhoneAndAnOldBackupNeverUndoesNewerWork() {
        var t = 1_000L
        val a = store("a") { t++ }
        a.setMove { it.copy(name = "Our move", setupDone = true) }
        val room = a.createRoom(Home.DEST, "Kitchen")
        val box = a.createBox("L001", null)
        a.put(a.data.value.box(box.id)!!.copy(destRoomId = room.id, contents = "old contents"))
        val f = a.newPhotoFile().apply { writeBytes(ByteArray(2500) { 9 }) }
        a.addPhoto(box.id, PhotoKind.CONTENTS, f)
        val backup = ByteArrayOutputStream().also { Backup.export(a, it) }.toByteArray()

        val fresh = store("fresh") { t++ }
        Backup.import(fresh, ByteArrayInputStream(backup))
        assertTrue(fresh.data.value.move.setupDone)
        assertEquals("Kitchen", fresh.data.value.room(fresh.data.value.box(box.id)!!.destRoomId)!!.name)
        assertEquals(2500L, File(fresh.photoDir, f.name).length())

        a.put(a.data.value.box(box.id)!!.copy(contents = "newer contents", sealed = true))
        val added = a.createBox("L002", null)
        Backup.import(a, ByteArrayInputStream(backup))
        assertEquals("newer contents", a.data.value.box(box.id)!!.contents)
        assertNotNull(a.data.value.box(added.id))
    }

    // ---- changing your mind -------------------------------------------------

    @Test
    fun movingTheDateMovesTheTimelineButNotFixedDates() {
        val s = store("dates")
        s.setMove { it.copy(moveDay = 20_000) }
        s.put(Task(id = "rel", title = "Book van", offsetDays = -28))
        s.put(Task(id = "fixed", title = "Dentist", dueDay = 19_990))
        s.put(Task(id = "none", title = "Sometime"))
        fun due(id: String) = s.data.value.let { d -> d.dueDay(d.liveTasks.first { it.id == id }) }
        assertEquals(19_972L, due("rel"))
        s.setMove { it.copy(moveDay = 20_014) }
        assertEquals(19_986L, due("rel"))
        assertEquals(19_990L, due("fixed"))
        assertNull(due("none"))
        s.setMove { it.copy(moveDay = null) }
        assertNull(due("rel"))
        assertTrue(s.data.value.issues(now = 30_000).any { it.route == "tasks" }) // the fixed one is overdue
    }

    @Test
    fun changingADecisionAfterLoadingAndDeletingARoomKeepCountsHonest() {
        val s = store("mind")
        val kitchen = s.createRoom(Home.DEST, "Kitchen")
        val old = s.createRoom(Home.ORIGIN, "Kitchen")
        s.put(old.copy(mapsTo = kitchen.id))
        val chair = s.createItem("Chair", old.id)
        s.put(s.data.value.item(chair.id)!!.copy(disposition = Disposition.MOVE, stage = Stage.LOADED))
        val box = s.createBox("L001", old.id)
        assertEquals(2, s.data.value.movables().size)
        assertEquals(1, s.data.value.progress().loaded.done)

        // Decided to leave the chair behind after all.
        s.put(s.data.value.item(chair.id)!!.copy(disposition = Disposition.DONATE))
        assertEquals(1, s.data.value.movables().size)
        assertEquals(0, s.data.value.progress().loaded.done)

        // The new kitchen is removed from the plan: its box must show up as needing a room.
        s.delete(s.data.value.room(kitchen.id)!!)
        val d = s.data.value
        assertNull(d.room(d.box(box.id)!!.destRoomId))
        assertTrue(d.issues().any { it.text.contains("box with no destination") })
        assertTrue(d.whereIs(d.box(box.id)!!.toMovable()).contains("Kitchen")) // still at the old kitchen
        assertNull(s.createBox("L002", old.id).destRoomId?.let { d.room(it) })
    }

    @Test
    fun emptyMoveAndOddInputsDoNotBreakTheMaths() {
        val d = AppData()
        assertEquals(0, d.volume().trips)
        assertEquals(0f, d.progress().loaded.fraction)
        assertTrue(d.issues().isEmpty())
        assertTrue(d.search("   ").isEmpty())
        assertTrue(d.search("(.*)[").isEmpty())
        val odd = AppData(
            move = MoveInfo(vanCapacityM3 = 0.0),
            items = listOf(Item(id = "i", name = "Thing", disposition = Disposition.MOVE, widthCm = -5.0, depthCm = 0.0, heightCm = 1e6)),
        )
        assertTrue(odd.volume().trips >= 0)
        assertEquals(Fit.UNKNOWN, fitThrough(odd.items[0], 0.0))
        assertEquals("L", labelFromQr("HM:L"))
        assertEquals("", labelFromQr("   "))
        assertEquals(24, labelFromQr("x".repeat(500)).length)
        assertEquals("RM", suggestRoomCode("!!!"))
    }

    // ---- a big move ---------------------------------------------------------

    @Test
    fun aVeryLargeMoveStaysFast() {
        val dir = tmp.newFolder("big")
        val s = Store(dir)
        val rooms = (1..12).map { s.createRoom(Home.DEST, "Room $it") }
        val boxes = (1..600).map { n ->
            Box(id = "b$n", label = "L$n", contents = "books plates cables item$n", destRoomId = rooms[n % 12].id, updatedAt = n.toLong())
        }
        val items = (1..300).map { n -> Item(id = "i$n", name = "Furniture $n", disposition = Disposition.MOVE, updatedAt = n.toLong()) }
        val photos = (1..3000).map { n -> Photo(id = "p$n", ownerId = "b${n % 600 + 1}", file = "p$n.jpg", updatedAt = n.toLong()) }
        s.replaceAll(s.data.value.copy(boxes = boxes, items = items, photos = photos, tasks = seedTasks()))

        fun timed(label: String, limitMs: Long, block: () -> Unit) {
            val t0 = System.nanoTime()
            block()
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("$label took $ms ms", ms < limitMs)
        }
        timed("save", 3000) { s.flush() }
        timed("one edit", 300) { s.put(s.data.value.box("b300")!!.copy(contents = "edited")) }
        timed("search", 500) { assertEquals(1, s.data.value.search("item599").size) }
        timed("dashboard numbers", 500) { s.data.value.progress(); s.data.value.issues(); s.data.value.volume() }
        timed("merge", 1500) { s.mergeIn(s.data.value.copy(boxes = boxes.map { it.copy(sealed = true, updatedAt = it.updatedAt + 4_000_000_000_000) })) }
        s.flush()
        timed("reopen", 3000) { assertEquals(600, Store(dir).data.value.liveBoxes.count { it.sealed }) }
        assertTrue(File(dir, "data.json").length() < 3_000_000)
    }
}
