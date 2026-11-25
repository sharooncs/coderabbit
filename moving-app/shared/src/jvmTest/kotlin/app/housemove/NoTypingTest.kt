package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the data layer offers so that the person using the app has to type as little as possible. */
class NoTypingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var folders = 0
    private fun store() = Store(tmp.newFolder("s" + folders++))

    @Test
    fun aCategoryIsSuggestedFromTheContentsAndFailingThatTheRoom() {
        // From the room alone.
        assertEquals("Kitchen", suggestCategory("Kitchen"))
        assertEquals("Kitchen", suggestCategory("Dining room"))
        assertEquals("Clothes", suggestCategory("Bedroom 2"))
        assertEquals("Bathroom", suggestCategory("Upstairs bathroom"))
        assertEquals("Documents", suggestCategory("Office"))
        assertEquals("Tools", suggestCategory("Garage"))
        assertEquals("Toys", suggestCategory("Playroom"))
        assertEquals("Decor", suggestCategory("Living room"))
        assertEquals("Other", suggestCategory("Loft"))
        assertEquals("Other", suggestCategory(null))
        // What is in it says more than where it was packed.
        assertEquals("Books", suggestCategory("Kitchen", "Novels, cookbooks"))
        assertEquals("Kitchen", suggestCategory(null, "plates and mugs"))
        assertEquals("Bedding", suggestCategory("Bedroom 2", "Duvet, 4 pillows"))
        assertEquals("Electronics", suggestCategory("Office", "chargers and cables"))
        assertEquals("Tools", suggestCategory("Hall", "Drill, screwdrivers"))
        assertEquals("Food", suggestCategory("Kitchen", "tins, pasta, rice"))
        // Words are matched whole: a teapot is not tea, and "steak knives" is not "tea".
        assertEquals("Kitchen", suggestCategory("Kitchen", "teapot, steak knives"))
        // Things scanned in count too: books are known by their barcode.
        val scanned = listOf(BoxEntry(id = "1", boxId = "b", code = "9780140328721"), BoxEntry(id = "2", boxId = "b", title = "Board games"))
        assertEquals("Books", suggestCategory("Bedroom 2", "", scanned))
        for (room in COMMON_ROOMS + listOf("", "Zzz")) assertTrue(suggestCategory(room) in BOX_CATEGORIES)

        val s = store()
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen")
        val box = s.createBox("L001", kitchen.id)
        assertEquals("", box.category) // nothing is filled in behind the person's back
        assertEquals("Kitchen", s.data.value.suggestCategory(box))
        s.scanInto(box.id, "9780140328721", title = "Fantastic Mr. Fox")
        assertEquals("Books", s.data.value.suggestCategory(s.data.value.box(box.id)!!))
    }

    @Test
    fun aDestinationRoomIsSuggestedFromTheRoomSomethingComesFrom() {
        val s = store()
        val newKitchen = s.createRoom(Home.DEST, "Kitchen / diner")
        val living = s.createRoom(Home.DEST, "Living room")
        val bed2 = s.createRoom(Home.DEST, "Bedroom 2 (back)")
        val bed3 = s.createRoom(Home.DEST, "Bedroom 3")
        val office = s.createRoom(Home.DEST, "Office")
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen")
        val lounge = s.createRoom(Home.ORIGIN, "Lounge")
        val oldBed2 = s.createRoom(Home.ORIGIN, "Bedroom 2")
        val study = s.createRoom(Home.ORIGIN, "Study")
        val conservatory = s.createRoom(Home.ORIGIN, "Conservatory")
        val sameName = s.createRoom(Home.ORIGIN, "bedroom 3")
        fun to(r: Room) = s.data.value.suggestDestRoom(r.id)?.name
        assertEquals("Kitchen / diner", to(kitchen))
        assertEquals("Living room", to(lounge)) // a different word for the same room
        assertEquals("Bedroom 2 (back)", to(oldBed2)) // the closest of several bedrooms
        assertEquals("Bedroom 3", to(sameName)) // the same name, whatever the capitals
        assertEquals("Office", to(study))
        assertNull(to(conservatory)) // nothing like it: no guess
        assertNull(s.data.value.suggestDestRoom(null))
        // Only ever a room of the new home.
        for (r in listOf(kitchen, lounge, oldBed2, study, sameName)) assertEquals(Home.DEST, s.data.value.suggestDestRoom(r.id)!!.home)
        assertEquals(setOf(newKitchen.id, living.id, bed2.id, bed3.id, office.id), listOf(kitchen, lounge, oldBed2, sameName, study).map { s.data.value.suggestDestRoom(it.id)!!.id }.toSet())
        // A room that has been linked by hand goes where it was linked.
        s.put(s.data.value.room(lounge.id)!!.copy(mapsTo = office.id))
        assertEquals("Office", to(lounge))
    }

    @Test
    fun newThingsGetANameWhenNoneIsTyped() {
        val s = store()
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen")
        val bedroom = s.createRoom(Home.ORIGIN, "Bedroom")
        assertEquals("Kitchen box 1", s.data.value.defaultBoxName(ContainerKind.CARTON, kitchen.id))
        repeat(3) { s.createBox("L00$it", kitchen.id) }
        s.createBox("L010", bedroom.id)
        assertEquals("Kitchen box 4", s.data.value.defaultBoxName(ContainerKind.CARTON, kitchen.id))
        assertEquals("Bedroom box 2", s.data.value.defaultBoxName(ContainerKind.CARTON, bedroom.id))
        assertEquals("Box 1", s.data.value.defaultBoxName()) // no room
        assertEquals("Suitcase 1", s.data.value.defaultBoxName(ContainerKind.SUITCASE, kitchen.id))
        s.createBox("B1", bedroom.id, ContainerKind.SUITCASE)
        assertEquals("Suitcase 2", s.data.value.defaultBoxName(ContainerKind.SUITCASE, kitchen.id)) // suitcases are counted across rooms
        assertEquals("Crate 1", s.data.value.defaultBoxName(ContainerKind.CRATE))
        // A name already in use is skipped.
        s.put(s.data.value.liveBoxes.first().copy(name = "Kitchen box 4"))
        assertEquals("Kitchen box 5", s.data.value.defaultBoxName(ContainerKind.CARTON, kitchen.id))

        assertEquals("Kitchen item 1", s.data.value.defaultItemName(kitchen.id))
        s.createItem("", kitchen.id)
        s.createItem("Kitchen item 2", kitchen.id)
        assertEquals("Kitchen item 3", s.data.value.defaultItemName(kitchen.id))
        assertEquals("Item 1", s.data.value.defaultItemName())
    }

    @Test
    fun theNextContainerInARoomStartsTheSameAsTheLast() {
        val s = store()
        val newKitchen = s.createRoom(Home.DEST, "Kitchen")
        val newGarage = s.createRoom(Home.DEST, "Garage")
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen")
        val bedroom = s.createRoom(Home.ORIGIN, "Bedroom")
        // The first one in a room: suggestions from the room.
        val first = s.data.value.packDefaults(kitchen.id)
        assertEquals(PackDefaults(kitchen.id, newKitchen.id, "Kitchen", ContainerKind.CARTON, BoxSize.M, fromLast = false), first)
        val a = s.createContainer("L001", kitchen.id)
        assertEquals("Kitchen box 1", a.name)
        assertEquals("Kitchen", a.category)
        assertEquals(newKitchen.id, a.destRoomId)
        assertEquals(ContainerKind.CARTON, a.kind)
        assertEquals("Kitchen box 1 (L001)", a.title())

        // The person changes what this one is; the next starts the same.
        s.put(s.data.value.box(a.id)!!.copy(category = "Food", kind = ContainerKind.CRATE, size = BoxSize.S, destRoomId = newGarage.id))
        val same = s.data.value.packDefaults(kitchen.id)
        assertEquals(PackDefaults(kitchen.id, newGarage.id, "Food", ContainerKind.CRATE, BoxSize.S, fromLast = true), same)
        val b = s.createContainer("L002", kitchen.id)
        assertEquals("Food", b.category)
        assertEquals(ContainerKind.CRATE, b.kind)
        assertEquals(BoxSize.S, b.size)
        assertEquals(newGarage.id, b.destRoomId)
        assertEquals("Crate 2", b.name)
        // Another room is not affected.
        assertEquals("Clothes", s.data.value.packDefaults(bedroom.id).category)
        assertFalse(s.data.value.packDefaults(bedroom.id).fromLast)
        // Choosing a kind keeps the rest; a suitcase travels with you unless told otherwise.
        val c = s.createContainer("B1", kitchen.id, ContainerKind.SUITCASE)
        assertEquals("Suitcase 1", c.name)
        assertEquals("Food", c.category)
        assertTrue(c.travelsWithMe)
        // It uses the room the phone is working in when none is given.
        s.setDevice { it.copy(currentRoomId = bedroom.id) }
        assertEquals(bedroom.id, s.createContainer("L003").originRoomId)
        // The old way of making a box is unchanged: nothing is pre-filled.
        val plain = s.createBox("L004", kitchen.id)
        assertEquals("", plain.name)
        assertEquals("", plain.category)
        assertEquals("Box L004", plain.title())
    }

    @Test
    fun commonContentsAreOfferedAsChips() {
        // Every category has a short list, and each chip is one thing.
        assertEquals(BOX_CATEGORIES.toSet(), COMMON_CONTENTS.keys)
        for ((category, chips) in COMMON_CONTENTS) {
            assertTrue("$category has ${chips.size}", chips.size in 8..12)
            assertEquals(category, chips.size, chips.map { it.lowercase() }.toSet().size)
            for (chip in chips) assertEquals(listOf(chip), contentsLines(chip))
        }
        assertEquals(listOf("Plates", "Bowls", "Glasses", "Mugs"), contentsChips("Kitchen").take(4))
        // No category yet: the room decides. Nothing to go on: the general list.
        assertEquals(COMMON_CONTENTS["Kitchen"], contentsChips("", "Kitchen"))
        assertEquals(COMMON_CONTENTS["Other"], contentsChips("", null))
        assertEquals(COMMON_CONTENTS["Other"], contentsChips("Made-up category"))
        // What is already in the container is not offered again.
        assertEquals(listOf("Bowls", "Mugs"), contentsChips("Kitchen", contents = "plates, GLASSES and cutlery").take(2))

        val s = store()
        val kitchen = s.createRoom(Home.ORIGIN, "Kitchen")
        var box = s.createBox("L001", kitchen.id)
        assertEquals("Plates", s.data.value.contentsChips(box).first())
        // Tapping chips builds the contents; tapping again takes one off.
        s.put(box.copy(contents = addContents(box.contents, "Plates", "Mugs")))
        box = s.data.value.box(box.id)!!
        assertEquals("Plates, Mugs", box.contents)
        assertEquals("Box L001 · Plates, Mugs", box.title())
        assertFalse("Plates" in s.data.value.contentsChips(box))
        assertEquals("Mugs", removeContents(box.contents, "plates"))
        s.put(box.copy(category = "Books"))
        assertEquals("Novels", s.data.value.contentsChips(s.data.value.box(box.id)!!).first())
    }

    @Test
    fun aNewPersonsLetterIsPickedForThem() {
        val s = store()
        s.setDevice { it.copy(name = "Asha", letter = "A") }
        assertEquals("B", s.data.value.freeLetter())
        assertEquals(listOf("B", "C", "D"), listOf("Ben", "Cy", "Di").map { s.addPerson(it, Role.HELPER).valueOrNull()!!.letter })
        // A letter is not handed out again after its person has been removed: their boxes still carry it.
        s.removePerson(s.data.value.people.first { it.name == "Cy" }.id)
        assertEquals("E", s.addPerson("Eve", Role.DECIDER).valueOrNull()!!.letter)
        assertTrue(s.data.value.duplicateLetters().isEmpty())
    }
}
