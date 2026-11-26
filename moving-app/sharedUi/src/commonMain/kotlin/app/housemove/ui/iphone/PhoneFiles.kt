package app.housemove.ui.iphone

import androidx.compose.runtime.Composable
import app.housemove.data.File
import app.housemove.ui.Papers
import app.housemove.ui.PickedFile
import app.housemove.ui.SaveFile

/**
 * Files on the iPhone, for the shared screens (step 3b of design/ios-plan.md): the share sheet (AirDrop, Messages, Mail,
 * Save to Files, Print), the system's document picker to open a file and to save one in Files, files other apps hand to
 * HouseMove ("Open in HouseMove": update files and backups), and the printouts. [IphonePlatform] offers what the screens
 * ask of these; the iPhone's side is IosFiles (iosMain), given through [PhoneHooks.files]; the desktop tests give their
 * own.
 */
interface PhoneFiles {
    /** The folder files are made in to be handed on: backups, update files, PDFs. */
    val outDir: File

    /** Hands [file] to the share sheet. */
    fun share(file: File, mime: String)

    /** Choosing a file to open with the system's document picker: [onPicked] gets it, or null when none was chosen. */
    @Composable
    fun rememberOpenFile(onPicked: (PickedFile?) -> Unit): (mimes: List<String>) -> Unit

    /** Choosing where in Files a file of [mime] goes, under the name given: [onPicked] gets the place to write it, or null. */
    @Composable
    fun rememberSaveFile(mime: String, onPicked: (SaveFile?) -> Unit): (name: String) -> Unit

    /** The printouts: label sheets, hang tags, door signs, the Helper card, reports, Ask for decisions. */
    val papers: Papers

    /** A file another app asked HouseMove to open, until [incomingTaken]; null when there is none. Read in composition. */
    val incoming: IncomingFile?

    fun incomingTaken()
}

/** A file another app handed to HouseMove ("Open in HouseMove"): an update file, or with [backup], a backup. */
class IncomingFile(val file: PickedFile, val backup: Boolean)
