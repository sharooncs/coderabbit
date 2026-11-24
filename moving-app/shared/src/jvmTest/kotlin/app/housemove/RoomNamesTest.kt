package app.housemove

import app.housemove.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Renaming a room: the usual names the app offers can be changed, and the door-sign code follows while it safely can. */
class RoomNamesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private fun store() = Store(tmp.newFolder()) { clock++ }

    @Test
    fun aRoomTakesItsNewNameAndACodeFromItWhileNothingIsMarked() {
        val s = store()
        val r = s.createRoom(Home.DEST, "Bedroom 2")
        assertEquals("BED2", r.code)
        val renamed = s.renameRoom(r.id, "  Ava's   room ").valueOrNull()!!
        assertEquals("Ava's room", renamed.name)
        assertEquals("AVA", renamed.code)
        assertEquals("Ava's room", s.data.value.room(r.id)!!.name)
    }

    @Test
    fun theCodeStaysOnceABoxIsMarkedWithIt() {
        val s = store()
        val r = s.createRoom(Home.DEST, "Bedroom 2")
        s.put(Box(id = "box1", label = "L001", destRoomId = r.id))
        assertFalse(s.data.value.roomCodeFollowsName(s.data.value.room(r.id)!!))
        val renamed = s.renameRoom(r.id, "Guest room").valueOrNull()!!
        assertEquals("Guest room", renamed.name)
        assertEquals("BED2", renamed.code)
    }

    @Test
    fun theCodeStaysOnceALabelledItemIsMarkedWithIt() {
        val s = store()
        val r = s.createRoom(Home.ORIGIN, "Office")
        s.put(Item(id = "desk", name = "Desk", originRoomId = r.id, label = "L007"))
        assertEquals("OFF", s.renameRoom(r.id, "Study").valueOrNull()!!.code)
    }

    @Test
    fun aCodeTypedInByHandStays() {
        val s = store()
        val r = s.createRoom(Home.DEST, "Bedroom 3")
        s.put(s.data.value.room(r.id)!!.copy(code = "KIDS"))
        assertEquals("KIDS", s.renameRoom(r.id, "Kids' room").valueOrNull()!!.code)
    }

    @Test
    fun theNewCodeIsNotOneAnotherRoomOfTheSameHomeUses() {
        val s = store()
        s.createRoom(Home.DEST, "Bathroom") // BAT
        val r = s.createRoom(Home.DEST, "Bedroom 2")
        assertEquals("BAT2", s.renameRoom(r.id, "Bathroom upstairs").valueOrNull()!!.code)
    }

    @Test
    fun aNameAnotherRoomOfTheSameHomeHasIsRefusedButTheOtherHomeMayHaveIt() {
        val s = store()
        s.createRoom(Home.DEST, "Kitchen")
        val bed = s.createRoom(Home.DEST, "Bedroom 2")
        val refused = s.renameRoom(bed.id, "kitchen ")
        assertFalse(refused.ok)
        assertTrue(refused.reason!!.startsWith("There is already a room called kitchen in the "))
        assertEquals("Bedroom 2", s.data.value.room(bed.id)!!.name)
        val oldBed = s.createRoom(Home.ORIGIN, "Bedroom 2")
        assertTrue(s.renameRoom(oldBed.id, "Kitchen").ok)
    }

    @Test
    fun aBlankNameOrAMissingRoomIsRefusedAndTheSameNameChangesNothing() {
        val s = store()
        val r = s.createRoom(Home.ORIGIN, "Loft")
        assertFalse(s.renameRoom(r.id, "   ").ok)
        assertFalse(s.renameRoom("no-such-room", "Attic").ok)
        val before = s.data.value
        assertTrue(s.renameRoom(r.id, "Loft").ok)
        assertSame(before, s.data.value)
    }

    @Test
    fun usualOtherNamesAreOfferedForTheRoom() {
        assertTrue("Guest room" in roomRenames("Bedroom 2"))
        assertTrue("En suite" in roomRenames("Bathroom"))
        assertTrue(roomRenames("Garden / shed").isNotEmpty())
        assertTrue(roomRenames("Something odd").isNotEmpty())
    }
}
