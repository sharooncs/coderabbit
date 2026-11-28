package app.housemove

import app.housemove.data.Backup
import app.housemove.data.File
import app.housemove.data.Moves
import app.housemove.data.PhotoKind
import app.housemove.data.Role
import app.housemove.data.Store
import app.housemove.data.exists
import app.housemove.data.findByLabel
import app.housemove.data.isDirectory
import app.housemove.data.listFiles
import app.housemove.data.name
import app.housemove.data.outputStream
import app.housemove.data.readBytes
import app.housemove.data.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A phone's moves on disk ([Moves]: a folder per move, its photos and codes beside it, a list of them, backups going
 * into their card or a new one), on every platform: what the iPhone's files have to hold up, as Android's do.
 * Ported from MoveIdTest, which stays on the JVM with the rest.
 */
class MovesCommonTest {
    private var now = 1_000_000L

    private fun backupOf(s: Store): ByteArray = BytesOut().also { Backup.export(s, it.stream) }.bytes()

    @Test
    fun aBackupGoesIntoTheCardOfItsMoveOrANewOneWhereThePhoneIsWhoeverMadeIt() = inTempFolder { tmp ->
        val root = File(tmp, "mine")
        val m = Moves(File(root, "files"), File(root, "nb"), { "Asha's phone" }) { now++ }
        val maple = m.create("Maple Road", yourName = "Asha")
        val elm = m.create("Elm Street")
        val box = maple.createBox("M1", null)
        val photo = maple.newPhotoFile()
        val photoBytes = ByteArray(70_000) { (it * 31).toByte() }
        photo.outputStream().use { it.write(photoBytes, 0, photoBytes.size) }
        maple.addPhoto(box.id, PhotoKind.CONTENTS, photo)
        val zip = backupOf(maple)
        maple.createBox("M2", null)
        elm.createBox("E1", null)
        // Restored on the same phone, with Elm Street in use: it goes into Maple Road's card, and nothing is lost there.
        val r = m.restore({ bytesIn(zip) })
        assertTrue(r.ok, r.reason)
        assertEquals(2, m.cards.value.size)
        assertEquals(maple, m.current.value)
        assertNotNull(maple.data.value.findByLabel("M2"))
        assertNull(elm.data.value.findByLabel("M1"))

        // On a new phone: a new card, and the phone is Asha, whose phone made it, with a letter of its own.
        val root2 = File(tmp, "new")
        val n = Moves(File(root2, "files"), File(root2, "nb"), { "New phone" }) { now++ }
        n.create("Tried it out").createBox("T1", null)
        val r2 = n.restore({ bytesIn(zip) })
        assertTrue(r2.ok, r2.reason)
        assertEquals(1, r2.valueOrNull()!!.photos)
        assertEquals(2, n.cards.value.size)
        val s = n.current.value!!
        assertEquals(maple.moveId, s.moveId)
        assertEquals(maple.me!!.id, s.me!!.id)
        assertEquals(Role.ORGANISER, s.myRole)
        assertNotNull(s.data.value.findByLabel("M1"))
        assertNull(s.data.value.findByLabel("T1"))
        assertNotEquals(maple.device.value.letter, s.device.value.letter)
        val restored = s.data.value.livePhotos.single()
        assertContentEquals(photoBytes, s.photoFile(restored).readBytes())
        // Restoring it again goes into that card, not a third one.
        assertTrue(n.restore({ bytesIn(zip) }).ok)
        assertEquals(2, n.cards.value.size)
        // Not a backup: refused in words, and no card is left behind.
        assertEquals(Backup.NOT_A_BACKUP, n.restore({ bytesIn("nope".encodeToByteArray()) }).reason)
        assertEquals(2, n.cards.value.size)
        assertFalse(File(File(root2, "files"), "moves").listFiles()!!.any { it.name.endsWith(".new") }, "no folder of a restore is left behind")
        n.current.value!!.flush()
    }

    @Test
    fun theMovesOnAPhoneAreThereWhenItStartsAgainAndADeletedOneIsGone() = inTempFolder { tmp ->
        val files = File(tmp, "files")
        val nb = File(tmp, "nb")
        val m = Moves(files, nb, { "Asha's phone" }) { now++ }
        val maple = m.create("Maple Road", yourName = "Asha")
        maple.createBox("M1", null)
        val elm = m.create("Elm Street")
        elm.createBox("E1", null)
        maple.flush()
        elm.flush()
        val elmId = elm.moveId
        val folders = File(files, "moves").listFiles()!!.filter { it.isDirectory }.map { it.name }.toSet()
        assertEquals(2, folders.size)

        val again = Moves(files, nb, { "Asha's phone" }) { now++ }
        assertEquals(setOf("Maple Road", "Elm Street"), again.cards.value.map { it.name }.toSet())
        assertEquals(m.phone.id, again.phone.id, "the phone keeps its ID")
        val mapleAgain = again.store(maple.moveId)!!
        assertNotNull(mapleAgain.data.value.findByLabel("M1"))

        assertTrue(again.delete(elmId).ok)
        assertEquals(listOf("Maple Road"), again.cards.value.map { it.name })
        assertEquals(1, File(files, "moves").listFiles()!!.count { it.isDirectory })
        val third = Moves(files, nb, { "Asha's phone" }) { now++ }
        assertEquals(listOf("Maple Road"), third.cards.value.map { it.name })
        assertTrue(File(nb, "phone-id").exists())
    }
}
