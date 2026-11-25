package app.housemove

import app.housemove.data.AppJson
import app.housemove.data.File
import app.housemove.data.IOException
import app.housemove.data.Store
import app.housemove.data.absoluteFile
import app.housemove.data.copyTo
import app.housemove.data.currentTimeMillis
import app.housemove.data.delete
import app.housemove.data.deleteRecursively
import app.housemove.data.exists
import app.housemove.data.inputStream
import app.housemove.data.isDirectory
import app.housemove.data.isFile
import app.housemove.data.lastModified
import app.housemove.data.length
import app.housemove.data.listFiles
import app.housemove.data.mkdirs
import app.housemove.data.name
import app.housemove.data.outputStream
import app.housemove.data.parentFile
import app.housemove.data.read
import app.housemove.data.readBytes
import app.housemove.data.readText
import app.housemove.data.renameTo
import app.housemove.data.write
import app.housemove.data.writeText
import app.housemove.data.writeWhole
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Files and folders through the shared code's seam (Files.kt), on every platform: on the JVM they are java.io and
 * kotlin.io themselves, on the iPhone the POSIX calls of Files.ios.kt. The same checks pass on both, so the iPhone's
 * files behave as the ones Android's code was written against.
 */
class FilesCommonTest {
    /** The file at [p], as java.io.File(null as File?, p): a path of its own. */
    private fun path(p: String) = File(null as File?, p)

    @Test
    fun pathsJoinAndSplitAsJavaIoFileDoes() {
        val root = path("/var/mobile")
        assertEquals("/var/mobile", root.toString())
        assertEquals("/var/mobile/a.json", File(root, "a.json").toString())
        assertEquals("/var/mobile/x/y", File(root, "x//y/").toString())
        assertEquals("/a", File(path("/"), "a").toString())
        assertEquals("/a/b", path("//a///b//").toString())
        assertEquals("a.json", File(root, "a.json").name)
        assertEquals(root, File(root, "a.json").parentFile)
        assertEquals("/", path("/var").parentFile.toString())
        assertNull(path("/").parentFile)
        assertNull(path("plain").parentFile)
        assertEquals(path("/var/mobile"), root)
        assertEquals(path("/var/mobile").hashCode(), root.hashCode())
        assertEquals(root, root.absoluteFile)
        assertTrue(path("relative").absoluteFile.toString().startsWith("/"))
    }

    @Test
    fun textAndBytesComeBackAsTheyWent() = inTempFolder { dir ->
        val f = File(dir, "text.json")
        assertFalse(f.exists())
        assertEquals(0L, f.length())
        assertEquals(0L, f.lastModified())
        // A lone surrogate is written as '?', as String.toByteArray() writes it.
        val text = "Zoë's room 🏠 ΟΔΟΣ \u0000 end"
        f.writeText(text)
        assertTrue(f.exists())
        assertTrue(f.isFile)
        assertFalse(f.isDirectory)
        assertEquals(text, f.readText())
        assertEquals(text.encodeToByteArray().size.toLong(), f.length())
        f.writeText("x\uD800y")
        assertEquals("x?y", f.readText())
        val now = currentTimeMillis()
        assertTrue(f.lastModified() in now - 120_000..now + 120_000, "the file's time ${f.lastModified()} is not near $now")

        val bytes = Random(7).nextBytes(300_001)
        val g = File(dir, "photo.jpg")
        g.outputStream().use { out ->
            var at = 0
            while (at < bytes.size) {
                val n = minOf(8191, bytes.size - at)
                out.write(bytes, at, n)
                at += n
            }
        }
        assertContentEquals(bytes, g.readBytes())
        assertEquals(bytes.size.toLong(), g.length())
        // Read in small pieces too.
        val back = ByteArray(bytes.size)
        g.inputStream().use { input ->
            var at = 0
            while (true) {
                val n = input.read(back, at, minOf(1000, back.size - at))
                if (n <= 0) break
                at += n
            }
            assertEquals(bytes.size, at)
            assertEquals(-1, input.read(ByteArray(10), 0, 10))
            assertEquals(0, input.read(ByteArray(10), 0, 0))
        }
        assertContentEquals(bytes, back)
        // Written again, it is emptied first.
        g.writeText("short")
        assertEquals("short", g.readText())
    }

    @Test
    fun foldersAreMadeListedAndDeleted() = inTempFolder { dir ->
        val deep = File(File(File(dir, "a"), "b"), "c")
        assertTrue(deep.mkdirs())
        assertFalse(deep.mkdirs(), "mkdirs says false when the folder is there already")
        assertTrue(deep.isDirectory)
        assertFalse(deep.isFile)
        // ASCII names: a JVM started without a UTF-8 locale writes others as "?" (the app's file names are ids).
        val names = listOf("one.json", "two.jpg", "three.part", "with space.txt")
        for (n in names) File(deep, n).writeText(n)
        File(deep, "inner").mkdirs()
        val listed = deep.listFiles()
        assertNotNull(listed)
        assertEquals((names + "inner").sorted(), listed.map { it.name }.sorted())
        assertTrue(listed.all { it.parentFile == deep })
        assertNull(File(deep, "one.json").listFiles(), "a file has no listing")
        assertNull(File(deep, "missing").listFiles(), "nothing there has no listing")
        assertEquals(0, File(deep, "inner").listFiles()!!.size)

        assertFalse(File(dir, "a").delete(), "a folder that is not empty is not deleted")
        assertTrue(File(deep, "inner").delete())
        assertFalse(File(deep, "missing").delete())
        assertTrue(File(dir, "a").deleteRecursively())
        assertFalse(File(dir, "a").exists())
        assertTrue(File(dir, "never").deleteRecursively(), "what is not there is deleted already")
        assertFalse(File(File(dir, "plain.txt").apply { writeText("x") }, "under").mkdirs(), "no folder in a file")
    }

    @Test
    fun renameReplacesAndCopyFollowsKotlinIo() = inTempFolder { dir ->
        val a = File(dir, "a.json").apply { writeText("new") }
        val b = File(dir, "b.json").apply { writeText("old one, longer") }
        assertTrue(a.renameTo(b), "rename replaces a file that is there")
        assertFalse(a.exists())
        assertEquals("new", b.readText())
        assertFalse(File(dir, "missing").renameTo(File(dir, "other")))

        val c = File(dir, "c.json")
        assertEquals(c, b.copyTo(c))
        assertEquals("new", c.readText())
        assertFailsWith<IOException> { b.copyTo(c) }
        b.writeText("newer")
        b.copyTo(c, overwrite = true)
        assertEquals("newer", c.readText())
        assertFailsWith<IOException> { File(dir, "missing").copyTo(File(dir, "x")) }
        // Into a folder not made yet: it is made.
        val d = File(File(dir, "sub"), "d.json")
        b.copyTo(d)
        assertEquals("newer", d.readText())
        assertFailsWith<IOException> { File(dir, "missing").inputStream() }
        assertFailsWith<IOException> { File(File(dir, "no-such-folder"), "x").outputStream() }
    }

    @Test
    fun aFileWrittenWholeReplacesTheOldOneAndLeavesNoTemporaryFile() = inTempFolder { dir ->
        val f = File(dir, "codes.json")
        writeWhole(f, "{\"letter\":\"A\"}")
        writeWhole(f, "{\"letter\":\"B\"}")
        assertEquals("{\"letter\":\"B\"}", f.readText())
        assertEquals(listOf("codes.json"), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun theStoreWritesItsDataWholeWithTheCopyBeforeIt() = inTempFolder { dir ->
        val s = Store(dir) { 1_000_000L }
        s.setMove { it.copy(name = "First") }
        s.flush()
        s.setMove { it.copy(name = "Second") }
        s.flush()
        val data = File(dir, "data.json")
        val before = File(dir, "data.json.bak")
        assertTrue(data.isFile && before.isFile)
        assertTrue("\"Second\"" in data.readText())
        // The copy before is a whole saved move too (the first, or the second once a delayed save has run as well).
        assertTrue(AppJson.decodeFromString(app.housemove.data.AppData.serializer(), before.readText()).move.name in setOf("First", "Second"))
        assertFalse(File(dir, "data.json.tmp").exists())
        assertEquals(AppJson.encodeToString(app.housemove.data.AppData.serializer(), s.data.value), data.readText())
        s.close()
        // A store started on the folder reads what was written.
        val again = Store(dir) { 2_000_000L }
        assertEquals("Second", again.data.value.move.name)
        again.close()
    }
}
