package app.housemove.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import app.housemove.data.*
import java.io.File

/*
 * "Ask for decisions" (design 2.1, section 4), drawn as a PDF: a picture list of the things nobody has decided on yet,
 * for family who never visit. What is on it and how it is laid out (Decisions: the things, the answers, the pages) is
 * shared with the screens and the iPhone (sharedUi pdf/Decisions.kt); Android draws it through AndroidDraw.kt.
 */

/**
 * Draw page [page] (from 0) of the list onto [c], a page of 595 by 842 points: [items] are the whole list, and
 * [picture] gives an item's photo, or null.
 */
fun Decisions.drawPage(c: Canvas, data: AppData, items: List<Item>, page: Int, picture: (Item) -> Bitmap?) =
    draw(AndroidPage(c), data, items, page) { item -> picture(item)?.let { BitmapPicture(it) } }

/** The list as a PDF in the shareable folder, for the share sheet. */
fun Decisions.pdf(context: Context, store: Store): File =
    AndroidPdf.pdf(Share.outFile(context, fileName(store.data.value))) { document(it, store) }
