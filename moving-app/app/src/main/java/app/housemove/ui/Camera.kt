package app.housemove.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ExifInterface
import android.media.ToneGenerator
import android.net.Uri
import android.util.Size
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.housemove.data.Photo
import app.housemove.data.PhotoKind
import app.housemove.data.Qr
import app.housemove.data.ScanFormats
import app.housemove.data.labelFromQr
import coil.compose.AsyncImage
import java.io.File
import java.util.concurrent.Executors

// ---- image files -----------------------------------------------------------

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

/** The dark, see-through fill of everything that sits on a photograph or the live camera. */
private val OnPhoto = Color(0xB8081220)

/**
 * A round button that sits on top of a photograph or the live camera. Glass would
 * vanish against a bright picture, so this is a dark disc with a white picture and rim.
 * It is 48dp across unless [modifier] gives it a size: the photo under it can usually be
 * tapped too, so the button gets no touch area beyond what is drawn.
 */
@Composable
fun OverlayIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable () -> Unit) {
    FilledIconButton(
        onClick = onClick, modifier = modifier.defaultMinSize(48.dp, 48.dp).border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape), enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = OnPhoto, contentColor = Color.White),
        content = content,
    )
}

/** A few words on a photograph or the live camera: a dark pill with white text, like the buttons beside it. */
@Composable
private fun OnPhotoLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = modifier.clip(RoundedCornerShape(18.dp)).background(OnPhoto).border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The glowing mint ball that takes a photo: the one bright thing on a picture. */
@Composable
private fun CameraOrb(description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(48.dp).drawBehind { drawOrb(OrbKind.Primary, ring = false) }.clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Night.OnMint, modifier = Modifier.size(22.dp)) }
}

// ---- permission ------------------------------------------------------------

/** Shows [content] once the camera permission is granted; otherwise explains and asks. */
@Composable
fun CameraPermission(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) content() else {
        Column(
            modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Box(Modifier.size(72.dp).drawBehind { drawOrb(OrbKind.Plain) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(32.dp))
            }
            Text("The camera is needed to take photos and scan labels.", color = Night.TextSoft, textAlign = TextAlign.Center)
            PillButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            Hint("If nothing happens, allow the camera for HouseMove in the phone's app settings.")
        }
    }
}

// ---- camera preview shared by photo capture and scanning -------------------

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

// ---- taking photos ---------------------------------------------------------

/**
 * Full-screen camera that keeps shooting until closed.
 * Each shot is saved to [newFile] and reported through [onPhoto].
 */
@Composable
fun CameraDialog(
    hint: String,
    newFile: () -> File,
    onPhoto: (File) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Night.Text) {
            CameraPermission(Modifier.fillMaxSize().drawBehind { drawNightGround() }) {
                val context = LocalContext.current
                val haptic = LocalHapticFeedback.current
                var taken by remember { mutableIntStateOf(0) }
                var busy by remember { mutableStateOf(false) }
                var torch by remember { mutableStateOf(false) }
                var camera by remember { mutableStateOf<Camera?>(null) }
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
                Box(Modifier.fillMaxSize()) {
                    CameraPreview(listOf(capture), Modifier.fillMaxSize()) { camera = it }
                    CameraControls(
                        hint, taken, busy, torch,
                        onClose = onClose,
                        onTorch = {
                            torch = !torch
                            try {
                                camera?.cameraControl?.enableTorch(torch)
                            } catch (_: Exception) {
                            }
                        },
                        onShoot = {
                            busy = true
                            val file = newFile()
                            capture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        busy = false
                                        taken++
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onPhoto(file)
                                    }

                                    override fun onError(e: ImageCaptureException) {
                                        busy = false
                                        file.delete()
                                        toast(context, "Could not take the photo: ${e.message}")
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Everything that sits on the live camera picture: close, what is being photographed,
 * the torch, the shutter and Done. Kept apart from the camera itself so that it can be
 * looked at without one (AuditShotsTest draws it over a still picture).
 */
@Composable
internal fun CameraControls(
    hint: String, taken: Int, busy: Boolean, torch: Boolean,
    onClose: () -> Unit, onTorch: () -> Unit, onShoot: () -> Unit, modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        Row(
            // A shade at the top and the bottom, so the controls read on a bright scene too.
            Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                .statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close camera") }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { OnPhotoLabel(hint + if (taken > 0) " · $taken taken" else "") }
            Spacer(Modifier.width(12.dp))
            OverlayIconButton(onClick = onTorch) {
                Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, contentDescription = "Torch")
            }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 24.dp),
        ) {
            // The shutter: a white glass ball in a ring, in the middle where the thumb is.
            Box(
                Modifier.align(Alignment.Center).size(84.dp).border(2.dp, Color.White.copy(alpha = 0.75f), CircleShape).padding(7.dp)
                    .drawBehind { drawOrb(OrbKind.Selected, halo = false) }.clip(CircleShape)
                    .clickable(enabled = !busy, role = Role.Button, onClick = onShoot),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(32.dp), color = Night.MintDeep)
                else Icon(Icons.Default.PhotoCamera, contentDescription = "Take photo", tint = Night.OnWhite, modifier = Modifier.size(28.dp))
            }
            PillButton("Done", onClose, Modifier.align(Alignment.CenterEnd), icon = Icons.Default.Check)
        }
    }
}

// ---- scanning --------------------------------------------------------------

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

/**
 * Live QR scanner. Reports the raw QR text; the same code is not reported
 * again for a couple of seconds so holding the phone still does not re-trigger.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier, formats: ScanFormats = ScanFormats.LABELS) {
    CameraPermission(modifier) {
        val context = LocalContext.current
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
        var torch by remember { mutableStateOf(false) }
        var camera by remember { mutableStateOf<Camera?>(null) }
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
                    it.setAnalyzer(executor, QrAnalyzer(formats) { text ->
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
        Box(modifier) {
            CameraPreview(listOf(analysis), Modifier.fillMaxSize()) { camera = it }
            AimMarks(Modifier.matchParentSize())
            OverlayIconButton(
                onClick = {
                    torch = !torch
                    try {
                        camera?.cameraControl?.enableTorch(torch)
                    } catch (_: Exception) {
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { Icon(if (torch) Icons.Default.FlashOn else Icons.Default.FlashOff, contentDescription = "Torch") }
        }
    }
}

/** Four corner marks in the middle of the scanner's picture: where to hold the label. Decoration only. */
@Composable
internal fun AimMarks(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = minOf(size.width, size.height) * 0.62f
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val arm = side * 0.16f
        val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        for ((cx, dx) in listOf(left to 1f, left + side to -1f)) for ((cy, dy) in listOf(top to 1f, top + side to -1f)) {
            val corner = Path().apply {
                moveTo(cx + dx * arm, cy)
                lineTo(cx, cy)
                lineTo(cx, cy + dy * arm)
            }
            drawPath(corner, Color.Black.copy(alpha = 0.35f), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(corner, Night.MintLight, style = stroke)
        }
    }
}

/**
 * Full-screen "scan a label" dialog. Reports the label code (QR prefix removed).
 * The code can also be typed, for handwritten labels or when the camera is unavailable.
 * With [typingFolded] the field waits behind a "Type the code" chip, so the camera has the
 * screen to itself: for codes nobody would type unless they had to, such as a sync code or
 * an invite.
 */
@Composable
fun ScanDialog(
    title: String,
    onCode: (String) -> Unit,
    onClose: () -> Unit,
    raw: Boolean = false,
    formats: ScanFormats = ScanFormats.LABELS,
    typeLabel: String = "Or type the code",
    typingFolded: Boolean = false,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Night.Ground1, contentColor = Night.Text) {
            // The same ground and the same top bar as a screen: a round glass button, then the title.
            Column(Modifier.fillMaxSize().drawBehind { drawNightGround() }.systemBarsPadding().imePadding()) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassOrb(Icons.Default.Close, "Close", onClick = onClose)
                    Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                QrScanner(
                    onText = { onCode(if (raw) it else labelFromQr(it)) },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp).scannerFrame(),
                    formats = formats,
                )
                var typed by remember { mutableStateOf("") }
                var typing by remember { mutableStateOf(!typingFolded) }
                if (typingFolded) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = if (typing) 0.dp else 12.dp), horizontalArrangement = Arrangement.End) {
                        GlassChip("Type the code", icon = Icons.Default.Keyboard, selected = typing, onClick = { typing = !typing })
                    }
                }
                if (typing) {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            colors = glassFieldColors(),
                            value = typed,
                            onValueChange = { typed = it },
                            label = { Text(typeLabel) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        PillButton("OK", onClick = { onCode(typed.trim()); typed = "" }, enabled = typed.isNotBlank())
                    }
                }
            }
        }
    }
}

/** The rounded, rimmed window the scanner's live picture sits in, on any screen that shows one. */
fun Modifier.scannerFrame(): Modifier = clip(RoundedCornerShape(26.dp)).background(Color.Black).border(1.dp, Night.GlassRim, RoundedCornerShape(26.dp))

// ---- photo strips ----------------------------------------------------------

/**
 * A row of thumbnails for one owner and kind, with buttons to shoot more or
 * pick from the gallery. Tap a thumbnail to view or delete it.
 */
@Composable
fun PhotoStrip(ownerId: String, kind: PhotoKind, modifier: Modifier = Modifier, label: String = kind.label) {
    val store = LocalStore.current
    val context = LocalContext.current
    val photos = appData().photosOf(ownerId, kind)
    var camera by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { uri ->
            val f = store.newPhotoFile()
            if (importImage(context, uri, f)) store.addPhoto(ownerId, kind, f)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label + if (photos.isNotEmpty()) " (${photos.size})" else "",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            item {
                PhotoTile(Icons.Default.PhotoCamera, "Take $label photo") { camera = true }
            }
            item {
                PhotoTile(Icons.Default.PhotoLibrary, "Add $label from gallery") {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            items(photos, key = { it.id }) { p ->
                PhotoThumb(p, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).clickable { viewing = p })
            }
        }
    }
    if (camera) {
        CameraDialog(hint = label, newFile = { store.newPhotoFile() }, onPhoto = { store.addPhoto(ownerId, kind, it) }, onClose = { camera = false })
    }
    viewing?.let { p -> PhotoViewer(p, onClose = { viewing = null }) }
}

/**
 * The main picture of a box or item, shown large, with buttons to add more.
 * With no photo yet it is one big "take a photo" target.
 */
@Composable
fun PhotoHero(
    ownerId: String,
    kind: PhotoKind,
    modifier: Modifier = Modifier,
    label: String = kind.label,
    height: Dp = 200.dp,
    emptyText: String = "Take a photo",
) {
    val store = LocalStore.current
    val context = LocalContext.current
    val photos = appData().photosOf(ownerId, kind)
    var camera by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { uri ->
            val f = store.newPhotoFile()
            if (importImage(context, uri, f)) store.addPhoto(ownerId, kind, f)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().height(height).glass(RoundedCornerShape(20.dp), sheen = true, shadow = 6.dp)) {
            val first = photos.firstOrNull()
            if (first != null) {
                AsyncImage(
                    model = store.photoFile(first),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clickable { viewing = first },
                )
            } else {
                Column(
                    Modifier.fillMaxSize().clickable { camera = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(64.dp).drawBehind { drawOrb(OrbKind.Plain) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(emptyText, style = MaterialTheme.typography.titleMedium, color = Night.MintPale)
                }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (photos.size > 1) OnPhotoLabel("${photos.size} photos")
                OverlayIconButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Add $label from gallery")
                }
                CameraOrb("Take $label photo") { camera = true }
            }
        }
        if (photos.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.id }) { p -> PhotoThumb(p, Modifier.size(56.dp).clickable { viewing = p }) }
            }
        }
    }
    if (camera) {
        CameraDialog(hint = label, newFile = { store.newPhotoFile() }, onPhoto = { store.addPhoto(ownerId, kind, it) }, onClose = { camera = false })
    }
    viewing?.let { p -> PhotoViewer(p, onClose = { viewing = null }) }
}

@Composable
private fun PhotoTile(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(72.dp).glass(RoundedCornerShape(14.dp), shadow = 3.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Night.Mint) }
}

@Composable
fun PhotoThumb(photo: Photo, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    AsyncImage(
        model = store.photoFile(photo),
        contentDescription = photo.kind.label,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** First photo of an owner as a thumbnail, or a placeholder icon. */
@Composable
fun OwnerThumb(ownerId: String, modifier: Modifier = Modifier, isBox: Boolean = false) {
    val photo = appData().photosOf(ownerId).firstOrNull()
    if (photo != null) PhotoThumb(photo, modifier) else {
        Box(modifier.glass(RoundedCornerShape(10.dp), shadow = 0.dp), contentAlignment = Alignment.Center) {
            Icon(
                if (isBox) Icons.Default.Inventory2 else Icons.Default.Chair,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PhotoViewer(photo: Photo, onClose: () -> Unit) {
    val store = LocalStore.current
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = store.photoFile(photo),
                    contentDescription = photo.kind.label,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                        .statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayIconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close photo") }
                    // What kind of photo this is, and what was noted with it.
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                        OnPhotoLabel(listOf(photo.kind.label, photo.note).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                    OverlayIconButton(onClick = { confirm = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete photo") }
                }
            }
        }
    }
    if (confirm) ConfirmDialog(
        title = "Delete this photo?",
        text = "It will be removed from every phone at the next sync.",
        onConfirm = { store.delete(photo); onClose() },
        onDismiss = { confirm = false },
    )
}
