package app.housemove.ui.iphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import app.housemove.data.File
import app.housemove.ui.CameraPermission
import app.housemove.ui.LiveCamera
import app.housemove.ui.ScanKind
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The iPhone's camera, its scanner and the photos already on it (step 3a of design/ios-plan.md), as IphonePlatform
 * offers them to the shared screens. The iPhone's own is AVFoundation and PhotosUI, called from Kotlin (iosMain,
 * camera/AppleCamera.kt and camera/PhotoPicker.kt); demo mode's are fakes the UI tests drive (camera/DemoCamera.kt);
 * the desktop tests give their own. What is the same on every one of them is here: Android's rule for reading the
 * same code again, which codes each kind of scanner reads, and the beep.
 */

/** The camera, the scanner and choosing photos, as one phone has them. [PhoneHooks.camera] is null where there are none. */
interface PhoneCamera {
    /** Is there a camera to take photos and scan codes with? False in the simulator, where choosing photos still works. */
    val hasCamera: Boolean

    /** Whether HouseMove may use the camera now, and asking (iOS asks its own question the first time). */
    @Composable
    fun rememberPermission(): CameraPermission

    /** The live camera for photos, filling [modifier]. [onReady] gets it once it runs, or null if it cannot. */
    @Composable
    fun PhotoView(modifier: Modifier, onReady: (LiveCamera?) -> Unit)

    /**
     * The live camera reading codes of [kind], filling [modifier]. Each code it sees goes to [onCode] on the main thread,
     * as Android's scanner gives its text ([Barcodes.text]), every time it is seen: [ScanGate] is applied after.
     */
    @Composable
    fun CodeView(kind: ScanKind, onCode: (String) -> Unit, modifier: Modifier, onReady: (LiveCamera?) -> Unit)

    /**
     * Choosing up to ten photos already on the phone: each is copied into a file from [newFile] as a JPEG of at most
     * [PHOTO_LONG_SIDE] pixels and handed to [onPhoto], in the order chosen; then [onDone] is told how many were chosen.
     * All on the main thread. Null where this phone cannot.
     */
    @Composable
    fun rememberPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): (() -> Unit)?

    /** The short beep of a code read ([ScanBeep]). */
    fun beep() {}

    companion object {
        /** A photo's long side in pixels: Android's camera takes 1600 x 1200 (AndroidPlatform, ImageCapture). */
        const val PHOTO_LONG_SIDE = 1600

        /** Its JPEG quality, 0 to 1: Android's 82. */
        const val PHOTO_QUALITY = 0.82
    }
}

/**
 * Android's rule for a live scanner (AndroidPlatform's ScannerView): a code is passed on at once, unless it is the code
 * passed on last and that was no more than [againAfterMs] ago. So holding the phone still over a label does not read it
 * over and over, and a second label is read at once.
 */
class ScanGate(private val againAfterMs: Long = 2_500) {
    private var last = ""
    private var lastAt = 0L

    /** Should [text], seen at [now] (milliseconds), be passed on? */
    fun pass(text: String, now: Long): Boolean {
        if (text == last && now - lastAt <= againAfterMs) return false
        last = text
        lastAt = now
        return true
    }
}

/** The kinds of barcode the iPhone's scanner tells apart (AVFoundation's machine-readable code types). */
enum class Symbology { QR, EAN_13, EAN_8, UPC_E, CODE_128, CODE_39, CODE_93, DATA_MATRIX }

/**
 * Which codes each kind of scanner reads on the iPhone, and their text, as Android's reads them (the shared module's
 * ScanFormats, with zxing):
 *   LABELS    QR
 *   PRODUCTS  EAN-13, EAN-8, UPC-A, UPC-E
 *   SERIALS   Code 128, Code 39, Code 93, Data Matrix, QR, EAN-13, UPC-A
 *   ANY       QR, EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Data Matrix
 * AVFoundation has no UPC-A of its own: it reads one as the EAN-13 it is the same as, a 0 and then its 12 digits, where
 * zxing (asked for UPC-A too, as every set above that has EAN-13 is) gives the 12 digits. [text] does the same, so a
 * product scanned on the iPhone has the same number as on Android. Code 39 is read without a check character, as zxing
 * reads it by default.
 */
object Barcodes {
    fun of(kind: ScanKind): Set<Symbology> = when (kind) {
        ScanKind.LABELS -> setOf(Symbology.QR)
        ScanKind.PRODUCTS -> setOf(Symbology.EAN_13, Symbology.EAN_8, Symbology.UPC_E)
        ScanKind.SERIALS -> setOf(
            Symbology.CODE_128, Symbology.CODE_39, Symbology.CODE_93, Symbology.DATA_MATRIX, Symbology.QR, Symbology.EAN_13,
        )
        ScanKind.ANY -> setOf(Symbology.QR, Symbology.EAN_13, Symbology.EAN_8, Symbology.UPC_E, Symbology.CODE_128, Symbology.DATA_MATRIX)
    }

    /** The text of a code of [symbology] read as [raw], as Android's scanner of [kind] gives it; null when it does not read it. */
    fun text(kind: ScanKind, symbology: Symbology, raw: String?): String? {
        if (raw.isNullOrEmpty() || symbology !in of(kind)) return null
        if (symbology == Symbology.EAN_13 && raw.length == 13 && raw[0] == '0' && raw.all { it in '0'..'9' }) return raw.substring(1)
        return raw
    }
}

/**
 * How far the scanner zooms in, so a small code can be held where the lens can still focus (Apple's AVCamBarcode
 * sample): newer iPhones' main camera cannot focus closer than about 20 cm, where a 2 cm code is too small in the
 * picture; zooming in lets the phone stay at that distance with the code filling the aim marks (62 % of the picture's
 * width, as AimMarks draws them). [minFocusMm] is the camera's closest focus (AVFoundation's minimumFocusDistance, -1
 * when not known), [fieldOfViewDegrees] its picture's width as an angle. No more than 2x, so the picture stays easy to
 * aim; 1 (no zoom) where the lens focuses close enough.
 */
object ScanZoom {
    const val CODE_MM = 20.0
    const val FILL = 0.62
    const val MOST = 2.0

    fun factor(minFocusMm: Double, fieldOfViewDegrees: Double): Double {
        if (minFocusMm <= 0 || fieldOfViewDegrees <= 0 || fieldOfViewDegrees >= 180) return 1.0
        val halfAngle = fieldOfViewDegrees / 2 * PI / 180
        // How close the code may come and still fill the aim marks.
        val closest = CODE_MM / FILL / kotlin.math.tan(halfAngle)
        return (minFocusMm / closest).coerceIn(1.0, MOST)
    }
}

/**
 * The beep of a code read: Android's (ToneGenerator TONE_PROP_ACK for 120 ms) is a 1200 Hz tone of about a tenth of a
 * second. This is that tone as a WAV file (16-bit mono PCM, 44.1 kHz, at half loudness, with a few milliseconds of fade
 * at each end so it does not click), which the iPhone plays as a system sound: quiet with the ring switch on silent, and
 * no pause in the music or podcast someone is listening to.
 */
object ScanBeep {
    const val RATE = 44_100
    const val HERTZ = 1_200
    const val MILLIS = 100

    fun wav(): ByteArray {
        val samples = RATE * MILLIS / 1000
        val fade = RATE * 5 / 1000
        val data = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val edge = min(1.0, min(i, samples - 1 - i).toDouble() / fade)
            val v = (sin(2 * PI * HERTZ * i / RATE) * 0.5 * edge * Short.MAX_VALUE).roundToInt()
            data[2 * i] = (v and 0xff).toByte()
            data[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        val out = ByteArray(44 + data.size)
        fun text(at: Int, s: String) = s.forEachIndexed { k, c -> out[at + k] = c.code.toByte() }
        fun int(at: Int, v: Int, bytes: Int) {
            for (k in 0 until bytes) out[at + k] = ((v shr (8 * k)) and 0xff).toByte()
        }
        text(0, "RIFF")
        int(4, 36 + data.size, 4)
        text(8, "WAVE")
        text(12, "fmt ")
        int(16, 16, 4) // the size of the format chunk
        int(20, 1, 2) // PCM
        int(22, 1, 2) // one channel
        int(24, RATE, 4)
        int(28, RATE * 2, 4) // bytes per second
        int(32, 2, 2) // bytes per frame
        int(34, 16, 2) // bits per sample
        text(36, "data")
        int(40, data.size, 4)
        data.copyInto(out, 44)
        return out
    }
}

/**
 * Keeps the screen from locking while this is on screen (a live camera: the walk-through, scanning at the van), as a
 * sync does ([PhoneHooks.keepAwake]); the phone's own setting comes back once nothing needs it.
 */
@Composable
internal fun KeepAwake(hooks: PhoneHooks) {
    DisposableEffect(hooks) {
        hooks.keepAwake(true)
        onDispose { hooks.keepAwake(false) }
    }
}
