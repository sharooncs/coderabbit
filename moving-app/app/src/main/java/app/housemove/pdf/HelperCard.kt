package app.housemove.pdf

import android.content.Context
import android.graphics.Canvas
import app.housemove.data.*
import java.io.File

/*
 * The half-page card for Helpers (design 2.1, section 2), drawn as a PDF. What it says and how it is laid out (its steps,
 * the door signs, the code to write on a box with no label) is HelperCard, shared with the screens and the iPhone
 * (sharedUi pdf/HelperCard.kt); Android draws it through AndroidDraw.kt.
 */

/** A PDF of one page with two cards, to print and cut in half. */
fun HelperCard.make(context: Context, data: AppData, paper: HelperCard.Paper): File =
    AndroidPdf.pdf(File(File(context.cacheDir, "out").apply { mkdirs() }, fileName(paper))) { document(it, data, paper) }

/** Two cards on a page of [w] by [h] points, with a dashed line to cut along between them. */
fun HelperCard.drawPage(c: Canvas, w: Int, h: Int, data: AppData) = page(AndroidPage(c), w, h, data)
