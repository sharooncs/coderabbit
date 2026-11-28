package app.housemove.pdf

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.Toast
import androidx.core.content.FileProvider
import app.housemove.data.*
import java.io.File

// The sheets of labels and hang tags, and the codes on them, are in sharedUi (pdf/Sheets.kt): the screens choose them.
// What is printed and how it is laid out is shared with the iPhone too (sharedUi pdf/Printouts.kt, HelperCard.kt,
// Decisions.kt); Android draws it here, on its PdfDocument (AndroidDraw.kt).

/** QR code as a bitmap with a quiet zone, black on white. */
fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val matrix = Qr.matrix(text, sizePx)
    val pixels = IntArray(sizePx * sizePx)
    for (y in 0 until sizePx) for (x in 0 until sizePx) {
        pixels[y * sizePx + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

private fun outFile(context: Context, name: String): File =
    File(File(context.cacheDir, "out").apply { mkdirs() }, name)

object Pdf {

    /** A sheet of QR labels for the given codes; each code is repeated [copies] times. [moveTag] goes in each QR code (see [labelQrText]). */
    fun labels(context: Context, codes: List<String>, sheet: LabelSheet, copies: Int = 1, moveTag: String = ""): File =
        AndroidPdf.pdf(outFile(context, Printouts.LABELS)) { Printouts.labels(it, codes, sheet, copies, moveTag) }

    /** Draw one page of labels. Coordinates are PDF points. */
    fun drawLabelPage(c: Canvas, sheet: LabelSheet, codes: List<String>, moveTag: String = "") =
        Printouts.labelPage(AndroidPage(c), sheet, codes, moveTag)

    /** Sheets of hang tags for the given codes, for suitcases and bags; each code is repeated [copies] times. */
    fun hangTags(context: Context, codes: List<String>, sheet: TagSheet, copies: Int = 1, moveTag: String = ""): File =
        AndroidPdf.pdf(outFile(context, Printouts.HANG_TAGS)) { Printouts.hangTags(it, codes, sheet, copies, moveTag) }

    /**
     * Draw one page of hang tags. Each tag has a cut line, a punch-hole mark at
     * the left, the QR code, the code in large type and a line to write on.
     */
    fun drawTagPage(c: Canvas, sheet: TagSheet, codes: List<String>, moveTag: String = "") =
        Printouts.tagPage(AndroidPage(c), sheet, codes, moveTag)

    /** One landscape page per new-home room, in the room's colour, to tape on its door. */
    fun doorSigns(context: Context, data: AppData, letter: Boolean): File =
        AndroidPdf.pdf(outFile(context, Printouts.DOOR_SIGNS)) { Printouts.doorSigns(it, data, letter) }

    fun drawDoorSign(c: Canvas, w: Int, h: Int, room: Room, destName: String) =
        Printouts.doorSign(AndroidPage(c), w, h, room, destName)

    /** Everything being moved: items by old-home room, then boxes, with a volume estimate. */
    fun inventory(context: Context, held: AppData): File =
        AndroidPdf.pdf(outFile(context, Printouts.INVENTORY)) { Printouts.inventory(it, held) }

    /** Damaged boxes and items with their before and after photos. */
    fun damageReport(context: Context, store: Store): File =
        AndroidPdf.pdf(outFile(context, Printouts.DAMAGE_REPORT)) { Printouts.damageReport(it, store) }
}

object Csv {
    /** Items and boxes as one spreadsheet-friendly file. */
    fun inventory(context: Context, held: AppData): File =
        AndroidPdf.text(outFile(context, Printouts.INVENTORY_CSV), Printouts.inventoryCsv(held))
}

object Share {
    private fun uri(context: Context, file: File) =
        FileProvider.getUriForFile(context, context.packageName + ".files", file)

    /** Open in whatever app handles the type (a PDF viewer can print it). Falls back to sharing. */
    fun open(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, file), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            send(context, file, mime)
        }
    }

    fun send(context: Context, file: File, mime: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app found to share this file.", Toast.LENGTH_LONG).show()
        }
    }

    /** A file in the shareable output folder. */
    fun outFile(context: Context, name: String): File = app.housemove.pdf.outFile(context, name)
}
