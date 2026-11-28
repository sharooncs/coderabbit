package app.housemove.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import app.housemove.data.BackupPlace
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The folder the daily small backup goes to, as Android gives it: a tree picked once through the system's folder
 * picker (a Drive folder, Documents, a USB stick), kept with a permission that lasts. Files are found, written,
 * read and deleted through the documents provider that holds the folder.
 */
class TreePlace(private val context: Context, private val tree: Uri) : BackupPlace {
    private val resolver get() = context.contentResolver
    private val root: String = DocumentsContract.getTreeDocumentId(tree)
    private val folder: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, root)

    private fun children(): List<Pair<String, Uri>> {
        val list = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val out = ArrayList<Pair<String, Uri>>()
        resolver.query(list, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out += name to DocumentsContract.buildDocumentUriUsingTree(tree, id)
            }
        }
        return out
    }

    override fun names(): List<String> = children().map { it.first }

    override fun write(name: String, body: (OutputStream) -> Unit) {
        // A file of today's name from an earlier try that was cut off is replaced; some folders would add " (1)" instead.
        children().filter { it.first == name }.forEach { DocumentsContract.deleteDocument(resolver, it.second) }
        val doc = DocumentsContract.createDocument(resolver, folder, "application/zip", name) ?: throw IOException("could not create $name")
        try {
            (resolver.openOutputStream(doc, "w") ?: throw IOException("could not write $name")).use(body)
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(resolver, doc)
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException(e)
        }
    }

    override fun read(name: String): InputStream {
        val doc = children().firstOrNull { it.first == name }?.second ?: throw IOException("$name is not there")
        return resolver.openInputStream(doc) ?: throw IOException("could not read $name")
    }

    override fun delete(name: String) {
        children().filter { it.first == name }.forEach { DocumentsContract.deleteDocument(resolver, it.second) }
    }
}

/** Keep the right to the folder [uri], picked with the system's folder picker, after the app is closed. */
fun keepFolder(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    true
} catch (_: Exception) {
    false
}

/** The backup folder kept as [saved] (see [keepFolder]), or null when there is none or its permission has gone. */
fun backupFolder(context: Context, saved: String): BackupPlace? {
    if (saved.isBlank()) return null
    return try {
        val uri = Uri.parse(saved)
        val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (held) TreePlace(context, uri) else null
    } catch (_: Exception) {
        null
    }
}

/** The name the folder [saved] has, to show ("Documents", "HouseMove backups"), or an empty string. */
fun backupFolderName(context: Context, saved: String): String = try {
    val tree = Uri.parse(saved)
    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0).orEmpty() else ""
    } ?: ""
} catch (_: Exception) {
    ""
}

/**
 * The small copy of a photo for update files and the daily backup: at most [side] pixels on its longer side, the
 * right way up, as a JPEG of [quality]. Put in as [app.housemove.data.Shrink.jpeg] when the app starts.
 */
fun shrinkJpeg(file: File, side: Int, quality: Int): ByteArray? {
    val bmp = loadScaledBitmap(file, side) ?: return null
    val scale = minOf(1f, side.toFloat() / maxOf(bmp.width, bmp.height))
    val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * scale).toInt()), maxOf(1, (bmp.height * scale).toInt()), true) else bmp
    val out = ByteArrayOutputStream()
    if (!small.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
    if (small !== bmp) small.recycle()
    bmp.recycle()
    return out.toByteArray()
}
