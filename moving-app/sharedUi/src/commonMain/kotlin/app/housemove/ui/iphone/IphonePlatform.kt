package app.housemove.ui.iphone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.housemove.data.BackupPlace
import app.housemove.data.File
import app.housemove.data.qrPixels
import app.housemove.ui.CameraPermission
import app.housemove.ui.LiveCamera
import app.housemove.ui.Night
import app.housemove.ui.Papers
import app.housemove.ui.PickedFile
import app.housemove.ui.PickedFolder
import app.housemove.ui.Platform
import app.housemove.ui.SaveFile
import app.housemove.ui.ScanKind
import app.housemove.ui.ScreenDp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * What the shared screens ask of the phone ([Platform]), on the iPhone: its Settings and its own small store through
 * [hooks], and the few words it says through [toasts]. Files (the share sheet, opening and saving them, "Open in
 * HouseMove", the PDFs) are [PhoneFiles], from step 3b. What waits for the rest of step 3 of design/ios-plan.md is not
 * offered, so the screens leave it out: the camera and scanning, choosing photos, speech and the daily backup folder.
 * A photo already in the move shows. The desktop tests render the shared screens with this too, so their pictures are
 * what the iPhone shows.
 */
class IphonePlatform(private val hooks: PhoneHooks, private val toasts: Toasts) : Platform {
    override fun toast(text: String) = toasts.say(text)

    /** The window as Compose measures it, in points (dp), with the text scale. */
    @Composable
    override fun screen(): ScreenDp {
        val size = LocalWindowInfo.current.containerSize
        val density = LocalDensity.current
        return ScreenDp((size.width / density.density).toInt(), (size.height / density.density).toInt(), density.fontScale)
    }

    // ---- not yet on the iPhone (step 3) ------------------------------------------------------------------------

    override val camera: Boolean get() = false

    @Composable
    override fun rememberCameraPermission() = CameraPermission(false) {}

    override fun openAppSettings(): Boolean {
        hooks.openSettings()
        return true
    }

    @Composable
    override fun PhotoCameraView(modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        LaunchedEffect(Unit) { onReady(null) }
    }

    @Composable
    override fun ScannerView(formats: ScanKind, onText: (String) -> Unit, modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        LaunchedEffect(Unit) { onReady(null) }
    }

    @Composable
    override fun rememberPhotoPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): (() -> Unit)? = null

    @Composable
    override fun canSpeak(): Boolean = false

    @Composable
    override fun rememberSpeech(onText: (String) -> Unit): ((String) -> Unit)? = null

    @Composable
    override fun rememberDictation(onText: (String) -> Unit): (() -> Unit)? = null

    // ---- files (step 3b): the share sheet, the document picker, "Open in HouseMove" ([PhoneFiles]) ----------------

    private val files: PhoneFiles? = hooks.files

    override val shareFiles: Boolean get() = files != null

    override fun outFile(name: String): File = File(files?.outDir ?: hooks.noBackupDir, name)

    override fun share(file: File, mime: String) {
        files?.share(file, mime)
    }

    /** The iPhone opens nothing in another app by itself: the share sheet offers Print, Save to Files and the apps. */
    override fun open(file: File, mime: String) {
        files?.share(file, mime)
    }

    @Composable
    override fun rememberOpenFile(onPicked: (PickedFile?) -> Unit): ((List<String>) -> Unit)? = files?.rememberOpenFile(onPicked)

    @Composable
    override fun rememberSaveFile(mime: String, onPicked: (SaveFile?) -> Unit): ((String) -> Unit)? = files?.rememberSaveFile(mime, onPicked)

    /** An update file opened with HouseMove; a backup opened with it is the iPhone's own ([IncomingFiles]). */
    @Composable
    override fun incomingUpdate(): PickedFile? = files?.incoming?.takeIf { !it.backup }?.file

    override fun incomingUpdateTaken() {
        if (files?.incoming?.backup == false) files.incomingTaken()
    }

    @Composable
    override fun rememberFolderPicker(onPicked: (PickedFolder?) -> Unit): (() -> Unit)? = null

    override fun backupFolder(saved: String): BackupPlace? = null

    override fun backupFolderName(saved: String): String = ""

    /** The iPhone holds the one move it joined (its own join screens), and leaves it from Get fresh data. */
    override val moreMoves: Boolean get() = false

    /** The printouts, drawn with the same layout as Android's (pdf/Printouts.kt) where the phone can share them. */
    override val papers: Papers? get() = files?.papers

    override val shareTheApp: ((CoroutineScope) -> Unit)? get() = null

    // ---- small things --------------------------------------------------------------------------------------------

    /** The shared encoder's QR code (the same modules as Android's ZXing draws), black on white, drawn into a picture. */
    override fun qrImage(text: String, sizePx: Int): ImageBitmap {
        val code = qrPixels(text, sizePx)
        val image = ImageBitmap(code.size, code.size)
        val canvas = Canvas(image)
        val white = Paint().apply { color = Color.White }
        val black = Paint().apply { color = Color.Black }
        canvas.drawRect(0f, 0f, code.size.toFloat(), code.size.toFloat(), white)
        // Each row's dark runs as one rectangle each.
        for (y in 0 until code.size) {
            var x = 0
            while (x < code.size) {
                if (!code[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < code.size && code[x, y]) x++
                canvas.drawRect(start.toFloat(), y.toFloat(), x.toFloat(), y + 1f, black)
            }
        }
        return image
    }

    override fun readSet(file: String, key: String): Set<String> = hooks.readSet("$file.$key")

    override fun writeSet(file: String, key: String, values: Set<String>) = hooks.writeSet("$file.$key", values)

    override fun country(): String = hooks.country()
}

/** What the iPhone says for a few seconds at the bottom of the screen, as Android's toasts do. */
class Toasts {
    var text: String? by mutableStateOf(null)
        private set

    /** Goes up with each thing said, so the same words said twice show twice. */
    var count: Int by mutableIntStateOf(0)
        private set

    fun say(words: String) {
        text = words
        count++
    }

    fun done(seen: Int) {
        if (seen == count) text = null
    }
}

/** The words of [toasts] on a glass pill near the bottom, for 3.5 seconds (Android's long toast), read out by VoiceOver. */
@Composable
fun ToastHost(toasts: Toasts, modifier: Modifier = Modifier) {
    val words = toasts.text ?: return
    val seen = toasts.count
    LaunchedEffect(seen) {
        delay(3_500)
        toasts.done(seen)
    }
    Box(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 96.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            words, style = MaterialTheme.typography.bodyLarge, color = Night.Text, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp).glass(RoundedCornerShape(22.dp), Tone.Plain).padding(horizontal = 18.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
