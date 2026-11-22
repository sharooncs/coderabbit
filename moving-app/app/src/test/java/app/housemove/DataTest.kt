package app.housemove

import app.housemove.data.*
import app.housemove.sync.WifiSync
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1000L
    private fun store(name: String) = Store(tmp.newFolder(name)) { clock++ }

    @Test
    fun newerRecordWinsAndMergeIsSymmetric() {
        val old = Box(id = "b1", label = "L001", contents = "old", updatedAt = 10)
        val new = old.copy(contents = "new", updatedAt = 20)
        val a = AppData(boxes = listOf(old), items = listOf(Item(id = "i1", name = "Sofa", updatedAt = 5)))
        val b = AppData(boxes = listOf(new), items = listOf(Item(id = "i2", name = "Bed", updatedAt = 7)))
        val ab = merge(a, b)
        val ba = merge(b, a)
        assertEquals("new", ab.box("b1")!!.contents)
        assertEquals(2, ab.liveItems.size)
        assertEquals(ab.boxes.toSet(), ba.boxes.toSet())
        assertEquals(ab.items.toSet(), ba.items.toSet())
    }

    @Test
    fun deletionTravelsAsTombstoneAndTiesAreDeterministic() {
        val live = Box(id = "b1", label = "L001", updatedAt = 10)
        val dead = live.copy(deleted = true, updatedAt = 11)
        assertTrue(merge(AppData(boxes = listOf(live)), AppData(boxes = listOf(dead))).liveBoxes.isEmpty())
        val x = Box(id = "b2", label = "L002", contents = "x", updatedAt = 10)
        val y = x.copy(contents = "y")
        assertEquals(
            merge(AppData(boxes = listOf(x)), AppData(boxes = listOf(y))).boxes,
            merge(AppData(boxes = listOf(y)), AppData(boxes = listOf(x))).boxes,
        )
    }

    @Test
    fun storePersistsAndReloads() {
        val dir = tmp.newFolder("persist")
        val s = Store(dir)
        val room = s.createRoom(Home.DEST, "Kitchen")
        val origin = s.createRoom(Home.ORIGIN, "Kitchen")
        s.put(origin.copy(mapsTo = room.id))
        val box = s.createBox("L001", origin.id)
        assertEquals(room.id, box.destRoomId)
        s.flush()
        val again = Store(dir)
        assertEquals("L001", again.data.value.liveBoxes.single().label)
        assertEquals(2, again.data.value.liveRooms.size)
    }

    @Test
    fun handCodesSkipUsedLabelsAndRoomCodesAreUnique() {
        val s = store("codes")
        s.setDevice { it.copy(letter = "B", nextSeq = 1) }
        s.createBox("B1")
        assertEquals("B2", s.nextHandCode())
        assertEquals("B3", s.nextHandCode())
        val a = s.createRoom(Home.DEST, "Bedroom")
        val b = s.createRoom(Home.DEST, "Bedroom")
        assertNotEquals(a.code, b.code)
        assertNotEquals(a.color, b.color)
    }

    @Test
    fun fitCheck() {
        val wardrobe = Item(id = "w", widthCm = 120.0, depthCm = 60.0, heightCm = 210.0)
        assertEquals(Fit.OK, fitThrough(wardrobe, 76.0))          // 60 through a 76 door, 120 under the lintel
        assertEquals(Fit.TIGHT, fitThrough(wardrobe, 63.0))
        assertEquals(Fit.NO, fitThrough(wardrobe, 55.0))
        val sofa = Item(id = "s", widthCm = 220.0, depthCm = 95.0, heightCm = 90.0)
        assertEquals(Fit.NO, fitThrough(sofa, 76.0))
        assertEquals(Fit.UNKNOWN, fitThrough(Item(id = "x", widthCm = 10.0), 76.0))
    }

    @Test
    fun volumeSearchAndIssues() {
        val s = store("logic")
        val kitchen = s.createRoom(Home.DEST, "Kitchen")
        s.put(Item(id = "i1", name = "Fridge", disposition = Disposition.MOVE, widthCm = 60.0, depthCm = 65.0, heightCm = 180.0))
        s.put(Item(id = "i2", name = "Old chair", disposition = Disposition.BIN))
        s.put(Item(id = "i3", name = "Lamp"))
        s.put(Box(id = "b1", label = "L007", contents = "kettle, mugs, tea", destRoomId = kitchen.id, size = BoxSize.M))
        val d = s.data.value
        val v = d.volume()
        assertEquals(0.702, v.itemsM3, 0.001)
        assertEquals(1, v.trips)
        assertEquals("L007", d.search("kettle").single().movable.label)
        assertEquals("L007", d.search("kitchen mugs").single().movable.label)
        assertTrue(d.search("passport").isEmpty())
        assertEquals("L007", d.findByLabel("l007")!!.label)
        assertEquals("L007", labelFromQr("HM:L007"))
        val issues = d.issues().map { it.route }
        assertTrue("triage" in issues && "plan" in issues && "disposal" in issues)
        assertEquals(2, d.movables().size)
    }

    @Test
    fun duplicateLabelsAreReported() {
        val d = AppData(
            boxes = listOf(Box(id = "1", label = "A1"), Box(id = "2", label = "a1"), Box(id = "3", label = "A2")),
        )
        assertEquals(listOf("A1"), d.duplicateLabels())
    }

    @Test
    fun taskDueDatesFollowMoveDay() {
        val d = AppData(move = MoveInfo(moveDay = 20_000), tasks = seedTasks())
        val first = d.liveTasks.minOf { d.dueDay(it)!! }
        assertEquals(20_000L - 42, first)
        assertNull(AppData(tasks = seedTasks()).let { it.dueDay(it.tasks.first()) })
    }

    @Test
    fun backupRoundTripMergesAndRejectsUnsafeNames() {
        val a = store("backupA")
        val item = a.createItem("Piano", null)
        val f = a.newPhotoFile().apply { writeBytes(ByteArray(2048) { 7 }) }
        a.addPhoto(item.id, PhotoKind.CONDITION, f)
        val bytes = ByteArrayOutputStream().also { Backup.export(a, it) }.toByteArray()

        val b = store("backupB")
        b.createItem("Desk", null)
        val r = Backup.import(b, ByteArrayInputStream(bytes))
        assertEquals(1, r.photos)
        assertEquals(setOf("Piano", "Desk"), b.data.value.liveItems.map { it.name }.toSet())
        assertTrue(File(b.photoDir, f.name).isFile)
        assertFalse(isSafeFileName("../evil.jpg"))
        assertFalse(isSafeFileName("a/b.jpg"))
        assertTrue(isSafeFileName("3f0c.jpg"))
    }

    @Test
    fun twoPhonesSyncDataAndPhotosBothWays() {
        val a = store("phoneA")
        val b = store("phoneB")
        a.setDevice { it.copy(name = "Asha", letter = "A") }
        b.setDevice { it.copy(name = "Ben", letter = "B") }

        val shared = a.createRoom(Home.DEST, "Kitchen")
        val boxA = a.createBox("L001", null)
        val pa = a.newPhotoFile().apply { writeBytes(ByteArray(5000) { 1 }) }
        a.addPhoto(boxA.id, PhotoKind.CONTENTS, pa)

        val boxB = b.createBox("B1", null)
        val pb = b.newPhotoFile().apply { writeBytes(ByteArray(70_000) { 2 }) }
        b.addPhoto(boxB.id, PhotoKind.CONTENTS, pb)

        val done = CountDownLatch(1)
        var hostResult: WifiSync.Result? = null
        val host = WifiSync.host(a) { hostResult = it; done.countDown() }
        try {
            // Ben's phone comes into Asha's move with an invite; what it had on its own comes along, as when joining.
            // (Since 2.1 two moves are never mixed by the plain code: see JoiningTest.)
            val ben = a.addPerson("Ben", Role.ORGANISER).valueOrNull()!!
            val first = WifiSync.Invite.parse(host.inviteFor(ben.id, listOf("127.0.0.1")).valueOrNull()!!.encode())!!
            val invite = WifiSync.Invite.parse(host.invite(listOf("127.0.0.1")).encode())!!
            val r = WifiSync.join(b, first)
            assertTrue(r.error, r.ok)
            assertEquals("Asha", r.peerName)
            assertEquals(1, r.photosSent)
            assertEquals(1, r.photosReceived)
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertTrue(hostResult!!.ok)
            assertEquals("Ben", hostResult!!.peerName)

            for (s in listOf(a, b)) {
                assertEquals(setOf("L001", "B1"), s.data.value.liveBoxes.map { it.label }.toSet())
                assertEquals(shared.id, s.data.value.roomsOf(Home.DEST).single().id)
                assertEquals(5000L, File(s.photoDir, pa.name).length())
                assertEquals(70_000L, File(s.photoDir, pb.name).length())
            }
            assertEquals(a.data.value.boxes.toSet(), b.data.value.boxes.toSet())

            // A later edit and a deletion travel on the next sync; a wrong code is refused.
            b.put(b.data.value.box(boxA.id)!!.copy(contents = "plates", sealed = true))
            a.delete(a.data.value.box(boxB.id)!!)
            assertFalse(WifiSync.join(b, invite.copy(token = "WRONG")).ok)
            assertTrue(WifiSync.join(b, invite).ok)
            assertEquals("plates", a.data.value.box(boxA.id)!!.contents)
            assertNull(b.data.value.box(boxB.id))
        } finally {
            host.stop()
        }
    }

    @Test
    fun inviteParsingRejectsGarbage() {
        assertNull(WifiSync.Invite.parse("HM:L001"))
        assertNull(WifiSync.Invite.parse("HMSYNC:1.2.3.4:notaport:TOKEN"))
        val i = WifiSync.Invite.parse("HMSYNC:192.168.1.5,10.0.0.2:4567:ABCD2345")!!
        assertEquals(listOf("192.168.1.5", "10.0.0.2"), i.hosts)
        assertEquals(4567, i.port)
    }

    /** A label as the camera sees it: small in a larger grey frame, normal and inverted. */
    @Test
    fun qrLabelRoundTrip() {
        val decoder = Qr.Decoder()
        for (text in listOf(QR_PREFIX + "L042", "HMSYNC:192.168.1.5,10.0.0.2:45678:ABCD2345")) {
            val size = 180
            val m = Qr.matrix(text, size)
            val w = 640
            val h = 480
            for (inverted in listOf(false, true)) {
                val frame = ByteArray(w * h) { 150.toByte() }
                for (y in 0 until size) for (x in 0 until size) {
                    val dark = m.get(x, y) != inverted
                    frame[(y + 120) * w + (x + 200)] = (if (dark) 20 else 235).toByte()
                }
                assertEquals(text, decoder.decode(frame, w, h))
            }
        }
        assertNull(decoder.decode(ByteArray(640 * 480) { 128.toByte() }, 640, 480))
        assertEquals("L042", labelFromQr(QR_PREFIX + "L042"))
    }
}
