package app.housemove.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ExifInterface
import android.media.ToneGenerator
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.housemove.App
import app.housemove.data.AppData
import app.housemove.data.BackupPlace
import app.housemove.data.Outcome
import app.housemove.data.Qr
import app.housemove.data.ScanFormats
import app.housemove.data.Store
import app.housemove.pdf.Csv
import app.housemove.pdf.Decisions
import app.housemove.pdf.HelperCard
import app.housemove.pdf.LabelSheet
import app.housemove.pdf.Pdf
import app.housemove.pdf.Share
import app.housemove.pdf.TagSheet
import app.housemove.pdf.pdf
import app.housemove.pdf.qrBitmap
import app.housemove.ui.screens.CardPrinter
import app.housemove.ui.screens.TagPrinter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What the shared screens ask of the phone ([Platform]), on Android: the code the screens ran here before they were
 * shared with the iPhone (design/ios-plan.md, step 2). Made once per screen tree by AppRoot, with the activity's
 * [context] and the PDF makers in use ([tagPrinter] and [cardPrinter], which tests replace).
 */
class AndroidPlatform(
    private val context: Context,
    private val tagPrinter: TagPrinter,
    private val cardPrinter: CardPrinter,
) : Platform {
    override fun toast(text: String) = toast(context, text)

    @Composable
    override fun screen(): ScreenDp {
        val c = LocalConfiguration.current
        return ScreenDp(c.screenWidthDp, c.screenHeightDp, c.fontScale)
    }

    // ---- the camera and the scanner (CameraX) ----------------------------------------------------------------

    override val camera: Boolean get() = true

    @Composable
    override fun rememberCameraPermission(): CameraPermission {
        var granted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
        return CameraPermission(granted) { launcher.launch(Manifest.permission.CAMERA) }
    }

    override fun openAppSettings(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    @Composable
    override fun PhotoCameraView(modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        val capture = remember {
            ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setJpegQuality(82)
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                ).build()
        }
        val live = remember { CameraXCamera(context, capture) }
        LaunchedEffect(live) { onReady(live) }
        CameraPreview(listOf(capture), modifier) { live.camera = it }
    }

    @Composable
    override fun ScannerView(formats: ScanKind, onText: (String) -> Unit, modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        val haptic = LocalHapticFeedback.current
        val callback by rememberUpdatedState(onText)
        val executor = remember { Executors.newSingleThreadExecutor() }
        val tone = remember {
            try {
                ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            } catch (_: Exception) {
                null
            }
        }
        val analysis = remember {
            var last = ""
            var lastAt = 0L
            val main = ContextCompat.getMainExecutor(context)
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder().setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
                ).build().also {
                    it.setAnalyzer(executor, QrAnalyzer(ScanFormats.valueOf(formats.name)) { text ->
                        val now = System.currentTimeMillis()
                        if (text != last || now - lastAt > 2500) {
                            last = text
                            lastAt = now
                            main.execute {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                try {
                                    tone?.startTone(ToneGenerator.TONE_PROP_ACK, 120)
                                } catch (_: Exception) {
                                }
                                callback(text)
                            }
                        }
                    })
                }
        }
        DisposableEffect(Unit) {
            onDispose {
                analysis.clearAnalyzer()
                executor.shutdown()
                tone?.release()
            }
        }
        val live = remember { CameraXCamera(context, null) }
        LaunchedEffect(live) { onReady(live) }
        CameraPreview(listOf(analysis), modifier) { live.camera = it }
    }

    // ---- photos already on the phone --------------------------------------------------------------------------

    @Composable
    override fun rememberPhotoPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): () -> Unit {
        val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
            uris.forEach { uri ->
                val f = newFile()
                if (importImage(context, uri, f)) onPhoto(f)
            }
            onDone(uris.size)
        }
        return { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }

    // ---- speech: the phone's own "speak now" sheet, so the app needs no microphone permission ------------------

    @Composable
    override fun canSpeak(): Boolean = remember { speechIntent("").resolveActivity(context.packageManager) != null }

    @Composable
    override fun rememberSpeech(onText: (String) -> Unit): ((String) -> Unit)? {
        val available = remember { speechIntent("").resolveActivity(context.packageManager) != null }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(onText)
            }
        }
        if (!available) return null
        return { prompt ->
            try {
                launcher.launch(speechIntent(prompt))
            } catch (e: ActivityNotFoundException) {
                // The speech service vanished between the check and the tap; typing still works.
            }
        }
    }

    @Composable
    override fun rememberDictation(onText: (String) -> Unit): () -> Unit {
        val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) onText(said)
        }
        return {
            try {
                speech.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                )
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "No voice input on this phone. Use the keyboard's microphone.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---- files: the share sheet, and the system's pickers -----------------------------------------------------

    override val shareFiles: Boolean get() = true

    override fun outFile(name: String): File = Share.outFile(context, name)

    override fun share(file: File, mime: String) = shareSheet(context, file, mime)

    override fun open(file: File, mime: String) = Share.open(context, file, mime)

    @Composable
    override fun rememberOpenFile(onPicked: (PickedFile?) -> Unit): (List<String>) -> Unit {
        val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onPicked(uri?.let { UriFile(context, it) }) }
        return { mimes -> open.launch(mimes.toTypedArray()) }
    }

    @Composable
    override fun rememberSaveFile(mime: String, onPicked: (SaveFile?) -> Unit): (String) -> Unit {
        val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
            onPicked(uri?.let { SaveFile { context.contentResolver.openOutputStream(it) } })
        }
        return { name -> save.launch(name) }
    }

    @Composable
    override fun incomingUpdate(): PickedFile? {
        val app = context.applicationContext as? App ?: return null
        val incoming by app.incomingUpdate.collectAsState()
        return incoming?.let { UriFile(context, it) }
    }

    override fun incomingUpdateTaken() {
        (context.applicationContext as? App)?.incomingUpdate?.value = null
    }

    // ---- the daily small backup's folder: a document tree picked once ------------------------------------------

    @Composable
    override fun rememberFolderPicker(onPicked: (PickedFolder?) -> Unit): () -> Unit {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            onPicked(uri?.let { TreeFolder(context, it) })
        }
        return { launcher.launch(null) }
    }

    override fun backupFolder(saved: String): BackupPlace? = backupFolder(context, saved)

    override fun backupFolderName(saved: String): String = backupFolderName(context, saved)

    // ---- moves: several on one phone, joined and restored from Your moves ---------------------------------------

    override val moreMoves: Boolean get() = true

    // ---- the PDFs (app pdf/) and the app itself ------------------------------------------------------------------

    override val papers: Papers = object : Papers {
        override fun labels(codes: List<String>, sheet: LabelSheet, copies: Int, moveTag: String) = Pdf.labels(context, codes, sheet, copies, moveTag)
        override fun hangTags(codes: List<String>, sheet: TagSheet, moveTag: String) = tagPrinter.make(context, codes, sheet, moveTag)
        override fun doorSigns(data: AppData, letter: Boolean) = Pdf.doorSigns(context, data, letter)
        override fun helperCard(data: AppData, paper: HelperCard.Paper) = cardPrinter.make(context, data, paper)
        override fun inventory(data: AppData) = Pdf.inventory(context, data)
        override fun inventoryCsv(data: AppData) = Csv.inventory(context, data)
        override fun damageReport(store: Store) = Pdf.damageReport(context, store)
        override fun decisions(store: Store) = Decisions.pdf(context, store)
    }

    override val shareTheApp: (CoroutineScope) -> Unit = { scope -> shareTheApp(context, scope) }

    // ---- small things -------------------------------------------------------------------------------------------

    override fun qrImage(text: String, sizePx: Int): ImageBitmap = qrBitmap(text, sizePx).asImageBitmap()

    override fun readSet(file: String, key: String): Set<String> =
        context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).getStringSet(key, emptySet())?.toSet() ?: emptySet()

    override fun writeSet(file: String, key: String, values: Set<String>) {
        context.applicationContext.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putStringSet(key, values).apply()
    }

    override fun country(): String = Locale.getDefault().country
}

/** The Android platform for the screens under it: made once for the activity's context and the PDF makers in use. */
@Composable
fun rememberAndroidPlatform(tagPrinter: TagPrinter, cardPrinter: CardPrinter): Platform {
    val context = LocalContext.current
    return remember(context, tagPrinter, cardPrinter) { AndroidPlatform(context, tagPrinter, cardPrinter) }
}

// ---- saying things --------------------------------------------------------------------------------------------

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

/** Shows why the Store refused, and hands back what it saved when it did not ([Outcome.orSay] with Android's Context). */
fun <T> Outcome<T>.orSay(context: Context): T? {
    if (this is Outcome.Refused) toast(context, reason)
    return valueOrNull()
}

/** Shows why the Store refused. True when the call went through. */
fun <T> Outcome<T>.okOrSay(context: Context): Boolean {
    if (this is Outcome.Refused) toast(context, reason)
    return ok
}

// ---- files picked through the system ---------------------------------------------------------------------------

/** A file picked with the system's picker, or handed over by another app ("Open with HouseMove"): a content Uri. */
private data class UriFile(private val context: Context, val uri: Uri) : PickedFile {
    override fun open() = context.contentResolver.openInputStream(uri)
}

/** The daily backup's folder as the system's folder picker gives it: a document tree. */
private class TreeFolder(private val context: Context, private val tree: Uri) : PickedFolder {
    override val saved: String get() = tree.toString()

    override fun keep(): Boolean = keepFolder(context, tree)

    override fun place(): BackupPlace = TreePlace(context, tree)
}

// ---- sharing the app ------------------------------------------------------------------------------------------

private const val APK = "application/vnd.android.package-archive"

/** Where the installed app is read from. Tests put a small file in its place. */
@Volatile
internal var installedApk: (Context) -> File = { File(it.applicationInfo.sourceDir) }

/** What hands a file to the share sheet. Tests see what would be sent. */
@Volatile
internal var shareSheet: (Context, File, String) -> Unit = { context, file, type -> Share.send(context, file, type) }

/**
 * Send this phone's copy of the app through the share sheet, which reaches Quick Share (no
 * internet needed), for a phone that has an older HouseMove or none. The installed file is
 * copied to the shared cache folder first, which the app's FileProvider serves.
 */
fun shareTheApp(context: Context, scope: CoroutineScope) {
    // The share sheet and any message are on the main thread, whatever thread the scope resumes on.
    scope.launch(Dispatchers.Main) {
        val file = withContext(Dispatchers.IO) {
            try {
                Share.outFile(context, "HouseMove.apk").also { out -> installedApk(context).copyTo(out, overwrite = true) }
            } catch (_: Exception) {
                null
            }
        }
        val sent = file != null && try {
            shareSheet(context, file, APK)
            true
        } catch (_: Exception) {
            false
        }
        if (!sent) toast(context, "This phone could not get the app ready to share. Try again in a moment.")
    }
}

// ---- image files ------------------------------------------------------------------------------------------------

/** Decode a photo no larger than [maxPx] on its long side, upright. Null if unreadable. */
fun loadScaledBitmap(file: File, maxPx: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (bmp != null && degrees != 0f) {
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    } else bmp
} catch (_: Exception) {
    null
}

/** Copy a picture chosen from the gallery into [dest] as a reasonably sized JPEG. */
fun importImage(context: Context, uri: Uri, dest: File): Boolean = try {
    val tmp = File(dest.parentFile, dest.name + ".import")
    context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
    val bmp = loadScaledBitmap(tmp, 1600)
    tmp.delete()
    if (bmp == null) false else {
        dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        true
    }
} catch (_: Exception) {
    false
}

// ---- speech -----------------------------------------------------------------------------------------------------

private fun speechIntent(prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
}

// ---- the camera (CameraX) ---------------------------------------------------------------------------------------

/** The live camera of [PhotoCameraView] (with [capture]) or [ScannerView] (without): the torch, and taking a photo. */
private class CameraXCamera(private val context: Context, private val capture: ImageCapture?) : LiveCamera {
    /** Set once CameraX has bound the camera; null until then, or when it could not. */
    var camera: Camera? = null

    override fun torch(on: Boolean) {
        try {
            camera?.cameraControl?.enableTorch(on)
        } catch (_: Exception) {
        }
    }

    override fun shoot(file: app.housemove.data.File, done: (Throwable?) -> Unit) {
        val capture = capture ?: return done(IllegalStateException("not a photo camera"))
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = done(null)

                override fun onError(e: ImageCaptureException) = done(e)
            },
        )
    }
}

/** The camera's live picture, with [useCases] bound to it for as long as it is on screen. */
@Composable
private fun CameraPreview(
    useCases: List<UseCase>,
    modifier: Modifier = Modifier,
    onCamera: (Camera?) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(owner) {
        var provider: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                p.unbindAll()
                val camera = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, *useCases.toTypedArray())
                onCamera(camera)
            } catch (_: Exception) {
                onCamera(null)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            try {
                provider?.unbindAll()
            } catch (_: Exception) {
            }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Reads codes of [formats] from the camera's frames (their brightness), one frame at a time. */
private class QrAnalyzer(formats: ScanFormats, private val onText: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val decoder = Qr.Decoder(formats)

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes[0]
            val w = image.width
            val h = image.height
            val stride = plane.rowStride
            val buf = plane.buffer
            val data = ByteArray(w * h)
            if (stride == w) {
                buf.get(data, 0, minOf(data.size, buf.remaining()))
            } else {
                for (row in 0 until h) {
                    buf.position(row * stride)
                    buf.get(data, row * w, minOf(w, buf.remaining()))
                }
            }
            decoder.decode(data, w, h)?.let(onText)
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }
}
