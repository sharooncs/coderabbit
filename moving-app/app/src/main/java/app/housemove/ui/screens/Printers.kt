package app.housemove.ui.screens

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import app.housemove.data.AppData
import app.housemove.pdf.HelperCard
import app.housemove.pdf.Pdf
import app.housemove.pdf.TagSheet
import app.housemove.pdf.make
import java.io.File

/*
 * Two of the PDFs the Labels screen makes, as Android draws them, with a way for the tests to put their own in place
 * (Android's PDF writer does not exist where they run). AndroidPlatform's papers use these.
 */

/**
 * Makes the sheet of hang tags. The app draws a PDF; tests put their own in its place,
 * because Android's PDF writer does not exist where they run.
 */
fun interface TagPrinter {
    /** [moveTag] goes into each QR code, as on the box labels. */
    fun make(context: Context, codes: List<String>, sheet: TagSheet, moveTag: String): File
}

val LocalTagPrinter = staticCompositionLocalOf { TagPrinter { context, codes, sheet, moveTag -> Pdf.hangTags(context, codes, sheet, moveTag = moveTag) } }

/** Makes the Helper card. The app draws a PDF; tests put their own in its place, as for [TagPrinter]. */
fun interface CardPrinter {
    fun make(context: Context, data: AppData, paper: HelperCard.Paper): File
}

val LocalCardPrinter = staticCompositionLocalOf { CardPrinter { context, data, paper -> HelperCard.make(context, data, paper) } }
