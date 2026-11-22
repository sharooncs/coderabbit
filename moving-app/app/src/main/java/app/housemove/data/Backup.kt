package app.housemove.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A backup is a zip holding data.json plus every photo file. Importing one
 * merges it into the current data, so the same file works for restoring onto
 * a new phone and for combining two phones when they cannot reach each other.
 */
object Backup {
    private const val DATA_ENTRY = "data.json"
    private const val ABOUT_ENTRY = "about.json"
    private const val PHOTO_PREFIX = "photos/"

    const val NOT_A_BACKUP = "That file is not a HouseMove backup, or it is damaged. Choose a file saved with \"Save backup\"."
    const val TOO_NEW = "This backup was made by a newer version of HouseMove. Update the app, then restore it."
    const val COULD_NOT_WRITE = "The backup could not be written. Check there is space on the phone, then try again."

    /**
     * Who made a backup, kept beside the data. [helpersShare] is true when it was made on a Helper's
     * phone, which is never sent costs, cost suggestions, or receipt and serial-label photos: their
     * absence from the backup says nothing about whether they exist.
     */
    @Serializable
    internal data class About(val by: String = "", val helpersShare: Boolean = false, val at: Long = 0)

    /** Write the whole move on this phone, photos included, to [out]. Throws an IOException with words for the screen when it cannot. */
    fun export(store: Store, out: OutputStream) {
        try {
            store.flush()
            val data = store.data.value
            val about = About(by = store.device.value.personId, helpersShare = data.hasOrganiser() && data.roleOf(store.device.value) == Role.HELPER, at = System.currentTimeMillis())
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(DATA_ENTRY))
                zip.write(AppJson.encodeToString(data).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(ABOUT_ENTRY))
                zip.write(AppJson.encodeToString(about).toByteArray())
                zip.closeEntry()
                for (p in data.livePhotos) {
                    val f = File(store.photoDir, p.file)
                    if (!f.isFile) continue
                    zip.putNextEntry(ZipEntry(PHOTO_PREFIX + p.file))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (e: IOException) {
            throw IOException(COULD_NOT_WRITE, e)
        }
    }

    data class Result(val records: Int, val photos: Int)

    /**
     * Merge a backup into [store]. With [replace] the current data is discarded first, except who is
     * in the move and what they have been granted, and, for a backup from a Helper's phone, the costs
     * and photos such a phone never holds.
     *
     * Only an Organiser may restore one. A backup of another move is not mixed into a move this phone
     * shares with others ([Store.whyNotRestore]). A phone on its own that is not in the backup becomes
     * the person whose phone made it ([About.by]), or a Helper of that move ([Store.restore]), never
     * an Organiser of its own making. Anything refused, and any file that is not a backup,
     * gives an IllegalArgumentException whose message says why in words for the screen; nothing is
     * changed then, and no photo file is left behind. A backup made before version 2.0 is brought up
     * to date first ([migrate]), so nothing in it is lost.
     */
    fun import(store: Store, input: InputStream, replace: Boolean = false): Result {
        store.data.value.refusal(store.me, Action.RESTORE_BACKUP)?.let { throw IllegalArgumentException(it) }
        var json: String? = null
        var about = About()
        // Photo files are put aside under a name the app clears at start-up, and only kept once the backup has been accepted.
        val staged = ArrayList<File>()
        try {
            try {
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name
                        when {
                            name == DATA_ENTRY -> json = zip.readBytes().decodeToString()
                            name == ABOUT_ENTRY -> about = try {
                                AppJson.decodeFromString<About>(zip.readBytes().decodeToString())
                            } catch (_: Exception) {
                                About()
                            }
                            name.startsWith(PHOTO_PREFIX) && !e.isDirectory -> {
                                // Only a bare file name is accepted, so a crafted zip cannot write outside the photo folder.
                                val fileName = name.removePrefix(PHOTO_PREFIX)
                                if (isSafeFileName(fileName) && !File(store.photoDir, fileName).exists()) {
                                    val tmp = File(store.photoDir, "$fileName.import")
                                    staged += tmp
                                    tmp.outputStream().use { zip.copyTo(it) }
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } catch (e: IOException) {
                throw IllegalArgumentException(NOT_A_BACKUP, e)
            }
            val d = try {
                json?.let { AppJson.decodeFromString<AppData>(it) }
            } catch (e: Exception) {
                throw IllegalArgumentException(NOT_A_BACKUP, e)
            } ?: throw IllegalArgumentException(NOT_A_BACKUP)
            if (d.version > SCHEMA) throw IllegalArgumentException(TOO_NEW)
            store.whyNotRestore(d)?.let { throw IllegalArgumentException(it) }
            // A backup is restored by an Organiser who chose the file, so parts of it that carry no stamp
            // (anything saved before 2.0) are taken as they stand rather than turned away as a sync would.
            store.restore(migrate(d), replace, helpersShare = about.helpersShare, madeBy = about.by)
            // The files of photos the move now has on record are kept; any other file in the zip is not.
            val wanted = store.data.value.photos.filter { !it.deleted }.map { it.file }.toSet()
            var photos = 0
            for (tmp in staged) {
                val target = File(store.photoDir, tmp.name.removeSuffix(".import"))
                if (target.name in wanted && !target.exists() && tmp.renameTo(target)) photos++
            }
            store.flush()
            return Result(d.items.size + d.boxes.size + d.rooms.size + d.tasks.size, photos)
        } finally {
            staged.forEach { if (it.exists()) it.delete() }
        }
    }
}

fun isSafeFileName(name: String): Boolean =
    name.isNotBlank() && name.length < 120 && name.none { it == '/' || it == '\\' || it == ':' } && name != "." && name != ".."
