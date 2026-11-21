package app.housemove.data

import kotlinx.serialization.encodeToString
import java.io.File
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
    private const val PHOTO_PREFIX = "photos/"

    fun export(store: Store, out: OutputStream) {
        store.flush()
        val data = store.data.value
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DATA_ENTRY))
            zip.write(AppJson.encodeToString(data).toByteArray())
            zip.closeEntry()
            for (p in data.livePhotos) {
                val f = File(store.photoDir, p.file)
                if (!f.isFile) continue
                zip.putNextEntry(ZipEntry(PHOTO_PREFIX + p.file))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    data class Result(val records: Int, val photos: Int)

    /** Merge a backup into [store]. With [replace] the current data is discarded first. */
    fun import(store: Store, input: InputStream, replace: Boolean = false): Result {
        var data: AppData? = null
        var photos = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == DATA_ENTRY -> data = AppJson.decodeFromString<AppData>(zip.readBytes().decodeToString())
                    name.startsWith(PHOTO_PREFIX) && !e.isDirectory -> {
                        // Only a bare file name is accepted, so a crafted zip cannot write outside the photo folder.
                        val fileName = name.removePrefix(PHOTO_PREFIX)
                        if (isSafeFileName(fileName)) {
                            val target = File(store.photoDir, fileName)
                            if (!target.exists()) {
                                target.outputStream().use { zip.copyTo(it) }
                                photos++
                            }
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val d = data ?: throw IllegalArgumentException("Not a HouseMove backup: data.json is missing")
        if (d.version > SCHEMA) {
            throw IllegalArgumentException("This backup was made by a newer version of HouseMove. Update the app, then restore it.")
        }
        if (replace) store.replaceAll(d) else store.mergeIn(d)
        store.flush()
        return Result(d.items.size + d.boxes.size + d.rooms.size + d.tasks.size, photos)
    }
}

fun isSafeFileName(name: String): Boolean =
    name.isNotBlank() && name.length < 120 && name.none { it == '/' || it == '\\' || it == ':' } && name != "." && name != ".."
