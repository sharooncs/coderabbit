package app.housemove.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import app.housemove.data.AppData
import app.housemove.data.BackupPlace
import app.housemove.data.File
import app.housemove.data.InputStream
import app.housemove.data.OutputStream
import app.housemove.data.Store
import app.housemove.pdf.HelperCard
import app.housemove.pdf.LabelSheet
import app.housemove.pdf.TagSheet
import kotlinx.coroutines.CoroutineScope

/**
 * What the shared screens ask of the phone they run on, beyond Compose and the shared data and sync code (step 2 of
 * design/ios-plan.md): saying a few words, the camera and the scanner, photos already on the phone, speech, files to
 * share, open and save, the daily backup's folder, the PDFs, sending the app, and a few small things the phone keeps.
 *
 * Android's side is the app's AndroidPlatform: the code the screens ran there before they were shared. The iPhone's
 * is IosPlatform (iosMain), which says what it cannot do yet (step 3: the camera, scanning, the PDFs, the share sheet,
 * speech, the backup folder, picking photos). Where a phone cannot do something, the screens leave it out rather than
 * show it and fail: a `remember…` call answers null, or a flag is false. Given to the screens through [LocalPlatform].
 */
interface Platform {
    // ---- saying things ------------------------------------------------------------------------------------------

    /** A few words at the bottom of the screen for a few seconds (Android's Toast). */
    fun toast(text: String)

    /** The phone's screen in dp and its text scale, as Android's Configuration gives them. */
    @Composable
    fun screen(): ScreenDp

    // ---- the camera and the scanner -----------------------------------------------------------------------------

    /** Can HouseMove take photos and scan codes with this phone's camera? Where it cannot, nothing offers to. */
    val camera: Boolean

    /** Whether HouseMove may use the camera now, and the way to ask. */
    @Composable
    fun rememberCameraPermission(): CameraPermission

    /** Opens HouseMove's page in the phone's own settings (where the camera is allowed). False when it could not. */
    fun openAppSettings(): Boolean

    /** The live camera, for taking photos, filling [modifier]; [onReady] is given it once it runs, or null if it cannot. */
    @Composable
    fun PhotoCameraView(modifier: Modifier, onReady: (LiveCamera?) -> Unit)

    /**
     * The live camera reading codes of [formats], filling [modifier]. Each code read goes to [onText] on the main thread,
     * with a buzz and a beep; the same code again only after a couple of seconds. [onReady] as for [PhotoCameraView].
     */
    @Composable
    fun ScannerView(formats: ScanKind, onText: (String) -> Unit, modifier: Modifier, onReady: (LiveCamera?) -> Unit)

    // ---- photos already on the phone ----------------------------------------------------------------------------

    /**
     * Choosing up to ten photos already on the phone: each is copied into a file from [newFile] as a reasonably sized
     * JPEG and handed to [onPhoto]; then [onDone] is told how many were chosen. Null where this phone cannot yet.
     */
    @Composable
    fun rememberPhotoPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): (() -> Unit)?

    // ---- speech -------------------------------------------------------------------------------------------------

    /** Can a few words be said instead of typed here? (Tests stand in with [LocalSpeech], which counts as yes.) */
    @Composable
    fun canSpeak(): Boolean

    /** The phone's own "speak now" sheet, asking [prompt]; what was heard (never blank) goes to [onText]. Null: none. */
    @Composable
    fun rememberSpeech(onText: (String) -> Unit): ((prompt: String) -> Unit)?

    /** A text field's dictation button: what was heard goes to [onText]. Null where the phone has none. */
    @Composable
    fun rememberDictation(onText: (String) -> Unit): (() -> Unit)?

    // ---- files --------------------------------------------------------------------------------------------------

    /** Can files (backups, update files, PDFs) be handed to other apps and opened in them? */
    val shareFiles: Boolean

    /** A new file in the folder files are shared from. */
    fun outFile(name: String): File

    /** Hands [file] to the share sheet. */
    fun share(file: File, mime: String)

    /** Opens [file] in whatever app handles [mime] (a PDF viewer, to print), or shares it when none does. */
    fun open(file: File, mime: String)

    /** Choosing a file to open (an update file, a backup): [onPicked] gets it, or null. Null where this phone cannot. */
    @Composable
    fun rememberOpenFile(onPicked: (PickedFile?) -> Unit): ((mimes: List<String>) -> Unit)?

    /** Choosing where to save a file of [mime] (a backup): [onPicked] gets it, or null. Null where this phone cannot. */
    @Composable
    fun rememberSaveFile(mime: String, onPicked: (SaveFile?) -> Unit): ((name: String) -> Unit)?

    /** An update file another app asked HouseMove to open ("Open with"), until [incomingUpdateTaken]; null when none. */
    @Composable
    fun incomingUpdate(): PickedFile?

    fun incomingUpdateTaken()

    // ---- the daily small backup's folder ------------------------------------------------------------------------

    /** Choosing the daily backup's folder: [onPicked] gets it, or null. Null where this phone has no such folder yet. */
    @Composable
    fun rememberFolderPicker(onPicked: (PickedFolder?) -> Unit): (() -> Unit)?

    /** The folder kept as [saved] (see [PickedFolder.saved]), or null when there is none or it may no longer be used. */
    fun backupFolder(saved: String): BackupPlace?

    /** The name the folder [saved] has, to show ("Documents"), or an empty string. */
    fun backupFolderName(saved: String): String

    // ---- moves ---------------------------------------------------------------------------------------------------

    /**
     * Can this phone hold several moves, joining and restoring others from Your moves (Android)? The iPhone holds the one
     * it joined with its own join screens (ui/iphone), and leaves it from its own Get fresh data.
     */
    val moreMoves: Boolean

    // ---- what the phone prints, and the app itself ---------------------------------------------------------------

    /** The PDFs: label sheets, hang tags, door signs, the Helper card, reports. Null where this phone makes none yet. */
    val papers: Papers?

    /** Sends this phone's copy of HouseMove to a phone that has none or an older one. Null where it cannot. */
    val shareTheApp: ((CoroutineScope) -> Unit)?

    // ---- small things -------------------------------------------------------------------------------------------

    /** The words that differ between the phones ([PhoneWords]): Android's own unless the phone says otherwise. */
    val words: PhoneWords get() = PhoneWords.ANDROID

    /** [text] as a QR code of [sizePx] pixels with its quiet zone, black on white, for another phone's camera. */
    fun qrImage(text: String, sizePx: Int): ImageBitmap

    /** A set of words this phone keeps for itself, under [key] in [file] (which answers this person has read). */
    fun readSet(file: String, key: String): Set<String>

    fun writeSet(file: String, key: String, values: Set<String>)

    /** The country the phone is set to ("US"), for the paper size. */
    fun country(): String
}

/**
 * Which kinds of code a scanner accepts: narrow sets avoid misreading one kind as another. The same four as the shared
 * module's ScanFormats, which Android's scanner reads them with (zxing, on Android and the JVM only).
 */
enum class ScanKind {
    /** HouseMove's own stickers and sync codes. */
    LABELS,

    /** The barcode already printed on a book or a product. */
    PRODUCTS,

    /** Serial-number labels on the back of devices. */
    SERIALS,

    /** Anything: used when the app looks up what a code belongs to. */
    ANY,
}

/** The phone's screen as Android's Configuration gives it: width and height in dp, and the text scale. */
class ScreenDp(val screenWidthDp: Int, val screenHeightDp: Int, val fontScale: Float)

/** Whether HouseMove may use the camera ([granted]), and asking for it ([ask]): the phone shows its own question. */
class CameraPermission(val granted: Boolean, val ask: () -> Unit)

/** The live camera on screen. */
interface LiveCamera {
    fun torch(on: Boolean)

    /** Takes a photo into [file]; [done] is called on the main thread with null, or with what went wrong. */
    fun shoot(file: File, done: (Throwable?) -> Unit)
}

/** A file picked to open, read as often as needed. [open] is null when the phone could not open it. */
fun interface PickedFile {
    fun open(): InputStream?
}

/** A place picked to save a file to. [open] is null when the phone could not open it for writing. */
fun interface SaveFile {
    fun open(): OutputStream?
}

/** A folder picked for the daily backup. */
interface PickedFolder {
    /** How it is kept in the phone's settings, for [Platform.backupFolder]. */
    val saved: String

    /** Keeps the right to use the folder after the app is closed. False when the phone would not. */
    fun keep(): Boolean

    /** The folder, to read and write the daily backups in. */
    fun place(): BackupPlace
}

/** What the phone prints: each made as a file in the folder files are shared from. */
interface Papers {
    fun labels(codes: List<String>, sheet: LabelSheet, copies: Int, moveTag: String): File
    fun hangTags(codes: List<String>, sheet: TagSheet, moveTag: String): File
    fun doorSigns(data: AppData, letter: Boolean): File
    fun helperCard(data: AppData, paper: HelperCard.Paper): File
    fun inventory(data: AppData): File
    fun inventoryCsv(data: AppData): File
    fun damageReport(store: Store): File

    /** Ask for decisions: the things with no decision yet, with their photos. */
    fun decisions(store: Store): File
}

/** The phone the screens run on. The app's root gives it (Android's AppRoot, the iPhone's HouseMoveApp). */
val LocalPlatform = staticCompositionLocalOf<Platform> { error("Platform not provided") }

/**
 * The screens this phone cannot offer yet, which the lists of screens leave out: the walk-through and "Scan a label"
 * need the camera, the labels and Ask for decisions are PDFs, and joining or restoring another move needs [Platform.moreMoves].
 * Android offers every screen.
 */
@Composable
fun unavailableRoutes(): Set<String> {
    val p = LocalPlatform.current
    return remember(p) {
        buildSet {
            if (!p.camera) addAll(listOf("walk", "scan"))
            if (p.papers == null || !p.shareFiles) addAll(listOf("labels", "askdecisions"))
            if (!p.moreMoves) addAll(listOf("join", "lost"))
        }
    }
}

/** The phone's screen in dp and its text scale ([Platform.screen]). */
@Composable
fun screenDp(): ScreenDp = LocalPlatform.current.screen()
