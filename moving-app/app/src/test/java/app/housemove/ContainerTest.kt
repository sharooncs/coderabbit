package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Containers of any kind, one inside another, and catalogued items packed inside them. */
class ContainerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private var folders = 0
    private fun store() = Store(tmp.newFolder("s" + folders++)) { clock++ }

    private fun exchange(a: Store, b: Store) {
        b.mergeIn(a.mergeIn(b.data.value))
    }

    private fun Store.box(id: String) = data.value.box(id)!!
    private fun Store.item(id: String) = data.value.item(id)!!

    @Test
    fun kindsHaveLabelsVolumesAndSuitcasesTravelWithYou() {
        assertEquals(listOf("Carton", "Suitcase", "Bag", "Crate or tub", "Wardrobe box", "Other"), ContainerKind.entries.map { it.label })
        assertEquals(listOf(ContainerKind.SUITCASE), ContainerKind.entries.filter { it.travelsWithMe })
        assertTrue(ContainerKind.entries.all { it.m3 > 0 })

        val s = store()
        val carton = s.createBox("L001", null)
        assertEquals(ContainerKind.CARTON, carton.kind) // a box is a carton unless said otherwise
        assertFalse(carton.travelsWithMe)
        assertEquals("Box L001", carton.title())
        val suitcase = s.createBox("B1", null, ContainerKind.SUITCASE)
        assertTrue(suitcase.travelsWithMe)
        assertEquals("Suitcase B1", suitcase.title())
        assertEquals(ContainerKind.SUITCASE, suitcase.toMovable().kind)
        assertEquals("Crate L9 · tools", Box(id = "c", label = "L9", kind = ContainerKind.CRATE, contents = "tools").title())
        assertEquals("Kitchen box 4 (L004)", Box(id = "n", label = "L004", name = "Kitchen box 4").title())
        // It can be switched off again like any other box.
        s.put(suitcase.copy(travelsWithMe = false))
        assertFalse(s.box(suitcase.id).travelsWithMe)

        // A carton takes the space of its size; other kinds their typical volume, scaled by size.
        assertEquals(0.10, Box(id = "x", label = "x", size = BoxSize.L).volumeM3(), 1e-9)
        assertEquals(0.09, Box(id = "x", label = "x", kind = ContainerKind.SUITCASE).volumeM3(), 1e-9)
        assertTrue(Box(id = "x", label = "x", kind = ContainerKind.SUITCASE, size = BoxSize.L).volumeM3() > 0.09)
        assertEquals(BOX_CATEGORIES, listOf("Kitchen", "Clothes", "Books", "Documents", "Toys", "Bathroom", "Tools", "Electronics", "Decor", "Bedding", "Food", "Other"))
    }

    @Test
    fun onlyCartonsCountAsBoxesToBuy() {
        val s = store()
        s.createRoom(Home.ORIGIN, "Kitchen") // about 20 boxes
        fun need(what: String) = s.data.value.suggestSupplies().first { it.first.startsWith(what) }.second
        val medium = need("Medium boxes")
        assertEquals(8, medium)
        repeat(3) { s.createBox("S$it", null, ContainerKind.SUITCASE) }
        repeat(9) { s.createBox("G$it", null, ContainerKind.BAG) }
        var v = s.data.value.volume()
        assertEquals(0, v.cartons)
        assertEquals(12, v.otherContainers)
        assertEquals(medium, need("Medium boxes")) // however many bags and suitcases there are
        assertEquals(3 * 0.09 + 9 * 0.05, v.boxesM3, 1e-9) // they still take space on the van
        // Cartons packed beyond the estimate raise it.
        repeat(11) { s.createBox("L$it", null) }
        v = s.data.value.volume()
        assertEquals(11, v.cartons)
        assertEquals(11, need("Medium boxes"))
        assertEquals(8, need("Small boxes"))
        repeat(15) { s.put(Box(id = "big$it", label = "X$it", size = BoxSize.XL)) }
        assertEquals(15, need("Large boxes"))
    }

    @Test
    fun aContainerInsideAnotherTravelsWithIt() {
        val s = store()
        val trip = Trip(id = "t1", name = "Trip 1").also { s.put(it) }
        val suitcase = s.createBox("B1", null, ContainerKind.SUITCASE)
        val bag = s.createBox("B2", null, ContainerKind.BAG)
        val carton = s.createBox("L001", null)
        s.scanInto(suitcase.id, "", title = "Travel guide")

        assertTrue(s.nest(bag.id, suitcase.id).ok)
        var d = s.data.value
        assertEquals(suitcase.id, d.parentOf(d.box(bag.id)!!)!!.id)
        assertEquals(listOf(bag.id), d.childrenOf(suitcase.id).map { it.id })
        assertEquals(suitcase.id, d.carrierOf(d.box(bag.id)!!).id)
        // One level only, and no loops.
        assertEquals("A container cannot go inside itself.", s.nest(suitcase.id, suitcase.id).reason)
        assertTrue(s.nest(suitcase.id, bag.id).reason!!.contains("already inside"))
        assertTrue(s.nest(carton.id, bag.id).reason!!.contains("already inside"))
        assertTrue(s.nest(suitcase.id, carton.id).reason!!.contains("has containers inside"))
        assertNotNull(s.nest(bag.id, "missing").reason)
        // The plain save keeps to the same rule: this would put the suitcase inside its own bag.
        s.put(s.box(suitcase.id).copy(parentId = bag.id))
        assertNull(s.box(suitcase.id).parentId)

        // The bag is not a separate thing to load or unload, or to seal.
        d = s.data.value
        assertEquals(setOf("B1", "L001"), d.movables().map { it.label }.toSet())
        assertEquals(2, d.progress().sealed.total)
        assertEquals(2, d.progress().loaded.total)
        assertTrue(d.issues().none { it.text.startsWith("3 box") })
        assertTrue(d.search("bag").single().detail.startsWith("Inside Suitcase B1"))
        assertEquals("In Suitcase B1 · Old home: no room", d.whereIs(d.box(bag.id)!!.toMovable()))
        // Its volume is inside the suitcase's.
        assertEquals(0.09 + 0.06, d.volume().boxesM3, 1e-9)

        // What happens to the suitcase happens to the bag.
        s.updateBoxes(listOf(suitcase.id)) { it.copy(stage = Stage.LOADED, tripId = trip.id) }
        assertEquals(Stage.LOADED, s.box(bag.id).stage)
        assertEquals(trip.id, s.box(bag.id).tripId)
        assertEquals("In Suitcase B1 · On the van (Trip 1)", s.data.value.whereIs(s.box(bag.id).toMovable()))
        // Scanning the bag's own label at the other end delivers the suitcase it is in.
        s.updateBoxes(listOf(bag.id)) { it.copy(stage = Stage.DELIVERED) }
        assertEquals(Stage.DELIVERED, s.box(suitcase.id).stage)
        assertEquals(Stage.DELIVERED, s.box(bag.id).stage)
        assertEquals(1, s.data.value.progress().delivered.done)
        // Unpacking the suitcase unpacks the bag; undoing it brings both back.
        s.put(s.box(suitcase.id).copy(stage = Stage.DONE))
        assertEquals(Stage.DONE, s.box(bag.id).stage)
        s.updateBoxes(listOf(suitcase.id)) { it.copy(stage = Stage.DELIVERED) }
        assertEquals(Stage.DELIVERED, s.box(bag.id).stage)
        // Unpacking only the bag takes it out of the suitcase.
        s.updateBoxes(listOf(bag.id)) { it.copy(stage = Stage.DONE) }
        assertNull(s.box(bag.id).parentId)
        assertEquals(Stage.DONE, s.box(bag.id).stage)
        assertEquals(Stage.DELIVERED, s.box(suitcase.id).stage)
        assertEquals(3, s.data.value.movables().size)

        // Deleting a container lets go of what was in it; only its scanned list goes with it.
        s.updateBoxes(listOf(bag.id)) { it.copy(stage = Stage.DELIVERED) }
        assertTrue(s.nest(bag.id, suitcase.id).ok)
        val passport = s.createItem("Passport", null)
        assertTrue(s.putInto(passport.id, bag.id).ok)
        assertTrue(s.putInto(s.createItem("Camera", null).id, suitcase.id).ok)
        s.delete(s.box(suitcase.id))
        d = s.data.value
        assertNull(d.box(suitcase.id))
        assertNull(d.box(bag.id)!!.parentId)
        assertEquals(bag.id, d.item(passport.id)!!.boxId) // still in the bag, which is still there
        assertEquals(2, d.liveItems.size)
        assertTrue(d.liveItems.none { it.boxId == suitcase.id })
        assertTrue(d.entriesOf(suitcase.id).isEmpty())
        assertTrue(s.nest(bag.id, null).ok)
    }

    @Test
    fun anItemInAContainerFollowsTheContainer() {
        val s = store()
        val newLounge = s.createRoom(Home.DEST, "Lounge")
        val oldLounge = s.createRoom(Home.ORIGIN, "Lounge").copy(mapsTo = newLounge.id).also { s.put(it) }
        val trip = Trip(id = "t1", name = "Trip 1").also { s.put(it) }
        val suitcase = s.createBox("B1", oldLounge.id, ContainerKind.SUITCASE)
        val carton = s.createBox("L001", oldLounge.id)
        val camera = s.createItem("Camera", oldLounge.id)
        s.put(s.item(camera.id).copy(disposition = Disposition.MOVE, label = "L050", widthCm = 100.0, depthCm = 100.0, heightCm = 100.0))
        assertTrue(s.setDetails(camera.id) { it.copy(serial = "CAM-77") }.ok)
        val lamp = s.createItem("Lamp", oldLounge.id)
        s.put(s.item(lamp.id).copy(disposition = Disposition.MOVE))
        val before = s.data.value
        assertEquals(4, before.movables().size)
        assertEquals(1.0, before.volume().itemsM3 - DEFAULT_ITEM_M3, 1e-9)

        assertEquals(suitcase.id, s.putInto(camera.id, suitcase.id).valueOrNull()!!.boxId)
        assertNotNull(s.putInto(camera.id, "missing").reason)
        assertNotNull(s.putInto("missing", suitcase.id).reason)
        var d = s.data.value
        assertEquals(suitcase.id, d.containerOf(d.item(camera.id)!!)!!.id)
        assertEquals(listOf(camera.id), d.itemsIn(suitcase.id).map { it.id })
        // It is no longer a separate thing to load: counted once, inside the suitcase.
        assertEquals(setOf("B1", "L001", null), d.movables().map { it.label }.toSet())
        assertEquals(3, d.movables().size)
        assertEquals(listOf(lamp.id), d.looseItems().map { it.id })
        assertEquals(2, d.movingItems().size) // it is still an item that is moving
        assertEquals(DEFAULT_ITEM_M3, d.volume().itemsM3, 1e-9) // and takes no extra space
        assertEquals(3, d.progress().loaded.total)
        assertEquals(1, d.progress().destinations.total)
        // Nothing is lost: it is found by name, label and serial number, and says where it is packed.
        val hit = d.search("camera").single()
        assertEquals(camera.id, hit.movable.id)
        assertTrue(hit.detail, hit.detail.startsWith("In Suitcase B1"))
        assertEquals("In Suitcase B1 · Old home: Lounge", d.whereIs(hit.movable))
        assertEquals(camera.id, d.findByLabel("L050")!!.id)
        assertEquals(camera.id, d.findByAnyCode("cam-77")!!.id)
        assertEquals(suitcase.id, d.carrierOf(d.findByAnyCode("L050")!!).id)
        assertEquals(carton.id, d.carrierOf(d.findByLabel("L001")!!).id) // something not inside anything carries itself
        // The suitcase counts as having its contents recorded; the carton, still empty, does not.
        assertTrue(d.issues().any { it.text == "1 box with no contents recorded" })

        // Loading the suitcase loads the camera.
        s.updateBoxes(listOf(suitcase.id)) { it.copy(stage = Stage.LOADED, tripId = trip.id) }
        assertEquals(Stage.LOADED, s.item(camera.id).stage)
        assertEquals(trip.id, s.item(camera.id).tripId)
        assertEquals("In Suitcase B1 · On the van (Trip 1)", s.data.value.whereIs(s.item(camera.id).toMovable()))
        assertEquals(1, s.data.value.progress().loaded.done)
        // Scanning the camera's own label when unloading delivers the suitcase.
        s.updateItems(listOf(camera.id)) { it.copy(stage = Stage.DELIVERED) }
        assertEquals(Stage.DELIVERED, s.box(suitcase.id).stage)
        assertEquals(Stage.DELIVERED, s.item(camera.id).stage)
        // Unpacking the suitcase marks what was in it delivered and unpacked.
        s.updateBoxes(listOf(suitcase.id)) { it.copy(stage = Stage.DONE) }
        assertEquals(Stage.DONE, s.item(camera.id).stage)
        assertEquals("New home: Lounge", s.data.value.whereIs(s.item(camera.id).toMovable()))
        d = s.data.value
        assertEquals(1, d.progress().unpacked.done)
        assertEquals(3, d.progress().unpacked.total)

        // An item taken out of a delivered container and put in its place is done on its own.
        assertTrue(s.putInto(lamp.id, carton.id).ok)
        s.updateBoxes(listOf(carton.id)) { it.copy(stage = Stage.DELIVERED) }
        assertEquals(Stage.DELIVERED, s.item(lamp.id).stage)
        s.updateItems(listOf(lamp.id)) { it.copy(stage = Stage.DONE) }
        assertNull(s.item(lamp.id).boxId)
        assertEquals(Stage.DONE, s.item(lamp.id).stage)
        assertEquals(Stage.DELIVERED, s.box(carton.id).stage)
        // Taken out by hand it stays where the container was.
        assertNull(s.takeOut(camera.id).valueOrNull()!!.boxId)
        assertEquals(Stage.DONE, s.item(camera.id).stage)
        assertEquals(4, s.data.value.movables().size)

        // The log says what was loaded once, not once per thing inside.
        val log = s.data.value.latestActivity(50).map { it.text }
        assertEquals(1, log.count { it == "loaded suitcase B1" })
        assertTrue(log.none { it.contains("loaded Camera") })
    }

    @Test
    fun phonesThatPackDifferentlyAtTheSameTimeStillAgree() {
        val a = store()
        val b = store()
        val suitcase = a.createBox("B1", null, ContainerKind.SUITCASE)
        val bag = a.createBox("B2", null, ContainerKind.BAG)
        val camera = a.createItem("Camera", null)
        val lamp = a.createItem("Lamp", null)
        a.put(a.item(camera.id).copy(disposition = Disposition.MOVE))
        a.put(a.item(lamp.id).copy(disposition = Disposition.MOVE))
        exchange(a, b)

        // One phone puts the bag in the suitcase while the other puts the suitcase in the bag.
        assertTrue(a.nest(bag.id, suitcase.id).ok)
        assertTrue(b.nest(suitcase.id, bag.id).ok)
        exchange(a, b)
        for (s in listOf(a, b)) {
            val d = s.data.value
            // Neither is taken as being inside the other, so both are loaded and nothing goes round in circles.
            assertNull(d.parentOf(d.box(bag.id)!!))
            assertNull(d.parentOf(d.box(suitcase.id)!!))
            assertEquals(4, d.movables().size)
            assertEquals(suitcase.id, d.carrierOf(d.box(suitcase.id)!!).id)
        }
        assertEquals(a.data.value.boxes.toSet(), b.data.value.boxes.toSet())
        a.put(a.box(bag.id).copy(parentId = null))
        a.put(a.box(suitcase.id).copy(parentId = null))
        exchange(a, b)

        // One phone packs the camera into the suitcase while the other loads the suitcase.
        assertTrue(a.putInto(camera.id, suitcase.id).ok)
        b.updateBoxes(listOf(suitcase.id)) { it.copy(stage = Stage.LOADED) }
        exchange(a, b)
        for (s in listOf(a, b)) {
            assertEquals(Stage.LOADED, s.item(camera.id).stage)
            assertEquals(3, s.data.value.movables().size)
            assertEquals(1, s.data.value.progress().loaded.done)
        }
        assertEquals(a.item(camera.id), b.item(camera.id))

        // One phone deletes the bag while the other packs the lamp into it: the lamp is not lost.
        a.delete(a.box(bag.id))
        assertTrue(b.putInto(lamp.id, bag.id).ok)
        exchange(a, b)
        for (s in listOf(a, b)) {
            val d = s.data.value
            assertNull(d.box(bag.id))
            assertNull(d.containerOf(d.item(lamp.id)!!))
            assertTrue(d.movables().any { it.id == lamp.id })
            assertEquals(listOf(lamp.id), d.looseItems().map { it.id })
        }
    }
}
