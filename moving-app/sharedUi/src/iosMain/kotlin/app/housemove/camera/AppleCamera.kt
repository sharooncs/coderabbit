@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import app.housemove.data.File
import app.housemove.ui.CameraPermission
import app.housemove.ui.LiveCamera
import app.housemove.ui.ScanKind
import app.housemove.ui.iphone.Barcodes
import app.housemove.ui.iphone.PhoneCamera
import app.housemove.ui.iphone.ScanZoom
import app.housemove.ui.iphone.Symbology
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureAutoFocusRangeRestrictionNear
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureDevicePositionBack
import platform.AVFoundation.AVCaptureDeviceRotationCoordinator
import platform.AVFoundation.AVCaptureDeviceTypeBuiltInWideAngleCamera
import platform.AVFoundation.AVCaptureFocusModeContinuousAutoFocus
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCapturePhoto
import platform.AVFoundation.AVCapturePhotoCaptureDelegateProtocol
import platform.AVFoundation.AVCapturePhotoOutput
import platform.AVFoundation.AVCapturePhotoQualityPrioritizationSpeed
import platform.AVFoundation.AVCapturePhotoSettings
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPreset1920x1080
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureSessionPresetPhoto
import platform.AVFoundation.AVCaptureTorchModeOff
import platform.AVFoundation.AVCaptureTorchModeOn
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeCode128Code
import platform.AVFoundation.AVMetadataObjectTypeCode39Code
import platform.AVFoundation.AVMetadataObjectTypeCode93Code
import platform.AVFoundation.AVMetadataObjectTypeDataMatrixCode
import platform.AVFoundation.AVMetadataObjectTypeEAN13Code
import platform.AVFoundation.AVMetadataObjectTypeEAN8Code
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.AVMetadataObjectTypeUPCECode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.autoFocusRangeRestriction
import platform.AVFoundation.autoFocusRangeRestrictionSupported
import platform.AVFoundation.defaultDeviceWithDeviceType
import platform.AVFoundation.fileDataRepresentation
import platform.AVFoundation.focusMode
import platform.AVFoundation.hasTorch
import platform.AVFoundation.isFocusModeSupported
import platform.AVFoundation.isTorchModeSupported
import platform.AVFoundation.minimumFocusDistance
import platform.AVFoundation.requestAccessForMediaType
import platform.AVFoundation.torchMode
import platform.AVFoundation.videoZoomFactor
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObject
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create

/*
 * The iPhone's camera and scanner (step 3a of design/ios-plan.md), from Kotlin with Apple's own frameworks: AVFoundation
 * for the live picture, the photos, the torch and reading codes (AVCaptureMetadataOutput reads QR codes and barcodes
 * itself), ImageIO and UIKit for the JPEGs (Jpeg.kt), AudioToolbox for the beep, PhotosUI for choosing photos
 * (PhotoPicker.kt). No Swift: every one of these is Objective-C or C, which Kotlin/Native calls directly.
 */

/**
 * The real camera, the back one (as Android's DEFAULT_BACK_CAMERA). [hasCamera] is false where there is none: the
 * simulator. [openSettings] opens HouseMove's page in Settings, where the camera is switched on for it.
 */
internal class AppleCamera(private val openSettings: () -> Unit) : PhoneCamera {
    override val hasCamera: Boolean by lazy { backCamera() != null }

    /**
     * AVFoundation's answer, and asking: iOS asks its own question (NSCameraUsageDescription) once. After a no, iOS does not
     * ask again: the screens ask once by themselves on opening (nothing happens then, as on Android), and the next ask,
     * "Allow camera", opens Settings.
     */
    @Composable
    override fun rememberPermission(): CameraPermission {
        var status by remember { mutableStateOf(cameraStatus()) }
        val asked = remember { intArrayOf(0) }
        return CameraPermission(status == AVAuthorizationStatusAuthorized) {
            asked[0]++
            when (cameraStatus()) {
                AVAuthorizationStatusNotDetermined -> AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
                    onMain { status = cameraStatus() }
                }
                AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted -> if (asked[0] > 1) openSettings()
                else -> status = cameraStatus()
            }
        }
    }

    @Composable
    override fun PhotoView(modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        val live = remember { LiveSession(null) }
        Live(live, modifier, onReady)
    }

    @Composable
    override fun CodeView(kind: ScanKind, onCode: (String) -> Unit, modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        val callback by rememberUpdatedState(onCode)
        val live = remember(kind) { LiveSession(CodeReader(kind) { callback(it) }) }
        Live(live, modifier, onReady)
    }

    @Composable
    override fun rememberPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): () -> Unit =
        rememberPhotoPicker(newFile, onPhoto, onDone)

    override fun beep() = Beep.play()
}

/** The back camera, as Android's DEFAULT_BACK_CAMERA: the main (wide-angle) one. Null in the simulator. */
internal fun backCamera(): AVCaptureDevice? =
    AVCaptureDevice.defaultDeviceWithDeviceType(AVCaptureDeviceTypeBuiltInWideAngleCamera, AVMediaTypeVideo, AVCaptureDevicePositionBack)

private fun cameraStatus(): Long = AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)

/** [block] on the main thread, later. */
internal fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue(), block)

/** [block] off the main thread, for work that takes a moment (a photo made into a JPEG). */
internal fun inBackground(block: () -> Unit) = dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED.toLong(), 0u), block)

/**
 * The one queue every live camera is set up, started and stopped on: those calls block, and in this order one camera
 * closing has stopped before the next one opening starts.
 */
private val cameraQueue = dispatch_queue_create("app.housemove.camera", null)

/** [live] on screen: it runs while it is shown and stops when it leaves, and its picture fills [modifier]. */
@Composable
private fun Live(live: LiveSession, modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
    val ready by rememberUpdatedState(onReady)
    DisposableEffect(live) {
        live.start { ok -> ready(if (ok) live else null) }
        onDispose { live.stop() }
    }
    UIKitView(
        factory = { PreviewHost(live.preview) },
        modifier = modifier,
        // Touches go to the screen's own buttons drawn over the picture; VoiceOver has nothing to read in it.
        properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
    )
}

/**
 * The UIKit view the live picture is in: [picture] (the camera's preview layer, or demo mode's still picture) always
 * fills it, cut to its edges.
 */
internal class PreviewHost(private val picture: CALayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    init {
        backgroundColor = UIColor.blackColor
        clipsToBounds = true
        layer.addSublayer(picture)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        // At once, without the layer's own animation.
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        picture.frame = bounds
        CATransaction.commit()
    }
}

/**
 * One live camera: a capture session with the back camera, its picture on screen ([preview], filling it as Android's
 * FILL_CENTER does), and either a photo output or, with a [reader], AVFoundation's code reader. Setting it up, starting
 * and stopping run on [cameraQueue]; what the screens hear of it (that it runs, a photo taken, a code read) comes on the
 * main thread. Every call that AVFoundation would refuse with an exception (which would end the app) is checked first.
 */
private class LiveSession(private val reader: CodeReader?) : LiveCamera {
    private val session = AVCaptureSession()
    val preview = AVCaptureVideoPreviewLayer(session = session).apply { videoGravity = AVLayerVideoGravityResizeAspectFill }

    // On cameraQueue only.
    private var device: AVCaptureDevice? = null
    private var photos: AVCapturePhotoOutput? = null
    private var stopped = false

    // On the main thread only: which way up the phone is held, and the photos being taken (AVFoundation keeps only a
    // weak reference to their delegates).
    private var turning: AVCaptureDeviceRotationCoordinator? = null
    private val shooting = mutableSetOf<PhotoSaver>()

    /** Main thread. [onStarted] is told, on the main thread, whether the camera runs. */
    fun start(onStarted: (Boolean) -> Unit) {
        val camera = backCamera()
        if (camera == null) {
            onStarted(false)
            return
        }
        turning = AVCaptureDeviceRotationCoordinator(device = camera, previewLayer = preview)
        dispatch_async(cameraQueue) {
            val ok = !stopped && configure(camera)
            if (ok) session.startRunning()
            onMain {
                // The picture upright on the screen, which is always upright (the app is portrait only).
                val angle = turning?.videoRotationAngleForHorizonLevelPreview() ?: 90.0
                if (ok) preview.connection?.turn(angle)
                onStarted(ok && session.running)
            }
        }
    }

    /** Main thread: the camera stops (and with it the torch), once whatever was started before has. */
    fun stop() {
        turning = null
        dispatch_async(cameraQueue) {
            stopped = true
            if (session.running) session.stopRunning()
        }
    }

    /** On [cameraQueue]: the camera in, and the photo output or the code reader out. */
    private fun configure(camera: AVCaptureDevice): Boolean {
        val input = AVCaptureDeviceInput.deviceInputWithDevice(camera, error = null) ?: return false
        session.beginConfiguration()
        try {
            if (!session.canAddInput(input)) return false
            session.addInput(input)
            if (reader == null) {
                // 4:3 at the sensor's photo size; the photo is made 1600 x 1200 as it is saved, as Android's is.
                session.use(AVCaptureSessionPresetPhoto)
                val out = AVCapturePhotoOutput()
                if (!session.canAddOutput(out)) return false
                session.addOutput(out)
                photos = out
            } else {
                // Enough detail for a small barcode (Android reads 1280 x 720 frames).
                if (!session.use(AVCaptureSessionPreset1920x1080)) session.use(AVCaptureSessionPresetHigh)
                val out = AVCaptureMetadataOutput()
                if (!session.canAddOutput(out)) return false
                session.addOutput(out)
                // Codes are read on the main thread, where the screens take them.
                out.setMetadataObjectsDelegate(reader, dispatch_get_main_queue())
                val available = out.availableMetadataObjectTypes
                out.metadataObjectTypes = reader.types.filter { it in available }
            }
        } finally {
            session.commitConfiguration()
        }
        device = camera
        if (reader != null) closeUp(camera)
        return true
    }

    /** On [cameraQueue]: codes are held close, so focus near, and zoom in where the lens cannot focus that close. */
    private fun closeUp(camera: AVCaptureDevice) {
        if (!camera.lockForConfiguration(null)) return
        try {
            if (camera.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) camera.focusMode = AVCaptureFocusModeContinuousAutoFocus
            if (camera.autoFocusRangeRestrictionSupported) camera.autoFocusRangeRestriction = AVCaptureAutoFocusRangeRestrictionNear
            val format = camera.activeFormat
            val zoom = ScanZoom.factor(camera.minimumFocusDistance.toDouble(), format.videoFieldOfView.toDouble())
            camera.videoZoomFactor = zoom.coerceIn(1.0, maxOf(1.0, format.videoMaxZoomFactor))
        } finally {
            camera.unlockForConfiguration()
        }
    }

    override fun torch(on: Boolean) {
        dispatch_async(cameraQueue) {
            val camera = device ?: return@dispatch_async
            val mode = if (on) AVCaptureTorchModeOn else AVCaptureTorchModeOff
            if (!camera.hasTorch || !camera.isTorchModeSupported(mode) || !camera.lockForConfiguration(null)) return@dispatch_async
            camera.torchMode = mode
            camera.unlockForConfiguration()
        }
    }

    /** Main thread. The photo turned as the phone is held, so a photo taken sideways comes out level. */
    override fun shoot(file: File, done: (Throwable?) -> Unit) {
        val angle = turning?.videoRotationAngleForHorizonLevelCapture() ?: 90.0
        lateinit var saver: PhotoSaver
        saver = PhotoSaver(file) { error ->
            onMain {
                shooting.remove(saver)
                done(error)
            }
        }
        shooting.add(saver)
        dispatch_async(cameraQueue) {
            val out = photos
            val connection = out?.connectionWithMediaType(AVMediaTypeVideo)
            if (out == null || connection == null || !connection.active || !connection.enabled || !session.running) {
                saver.fail("the camera is not on yet")
                return@dispatch_async
            }
            connection.turn(angle)
            // Taken as quickly as it can be (Android's CAPTURE_MODE_MINIMIZE_LATENCY), with no flash: the torch is the light.
            val settings = AVCapturePhotoSettings.photoSettings()
            settings.photoQualityPrioritization = AVCapturePhotoQualityPrioritizationSpeed
            out.capturePhotoWithSettings(settings, delegate = saver)
        }
    }
}

/** Sets [preset] when the session can have it. */
private fun AVCaptureSession.use(preset: String?): Boolean {
    if (preset == null || !canSetSessionPreset(preset)) return false
    sessionPreset = preset
    return true
}

private fun AVCaptureConnection.turn(angle: Double) {
    if (isVideoRotationAngleSupported(angle)) videoRotationAngle = angle
}

/**
 * Saves the photo AVFoundation took into [file] (Jpeg.kt), off the main thread, then tells [finished]: null, or what
 * went wrong. Called on AVFoundation's own queue.
 */
private class PhotoSaver(private val file: File, private val finished: (Throwable?) -> Unit) : NSObject(), AVCapturePhotoCaptureDelegateProtocol {
    fun fail(why: String) = finished(Exception(why))

    override fun captureOutput(output: AVCapturePhotoOutput, didFinishProcessingPhoto: AVCapturePhoto, error: NSError?) {
        if (error != null) return fail(error.localizedDescription)
        val data = didFinishProcessingPhoto.fileDataRepresentation() ?: return fail("the camera gave no picture")
        inBackground {
            if (saveJpeg(data, file)) finished(null) else fail("the photo could not be saved")
        }
    }
}

/**
 * AVFoundation's code reader, for a scanner of [kind]: the codes it sees in each frame of the camera, on the main
 * thread; the first one this kind reads goes to [onCode], as Android's scanner would give its text ([Barcodes]).
 */
private class CodeReader(private val kind: ScanKind, private val onCode: (String) -> Unit) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {
    /** AVFoundation's names of the kinds of code to read. */
    val types: List<String> = Barcodes.of(kind).mapNotNull { avType(it) }

    override fun captureOutput(output: AVCaptureOutput, didOutputMetadataObjects: List<*>, fromConnection: AVCaptureConnection) {
        for (seen in didOutputMetadataObjects) {
            val code = seen as? AVMetadataMachineReadableCodeObject ?: continue
            val symbology = Symbology.entries.firstOrNull { avType(it) == code.type } ?: continue
            val text = Barcodes.text(kind, symbology, code.stringValue) ?: continue
            onCode(text)
            return
        }
    }
}

private fun avType(s: Symbology): String? = when (s) {
    Symbology.QR -> AVMetadataObjectTypeQRCode
    Symbology.EAN_13 -> AVMetadataObjectTypeEAN13Code
    Symbology.EAN_8 -> AVMetadataObjectTypeEAN8Code
    Symbology.UPC_E -> AVMetadataObjectTypeUPCECode
    Symbology.CODE_128 -> AVMetadataObjectTypeCode128Code
    Symbology.CODE_39 -> AVMetadataObjectTypeCode39Code
    Symbology.CODE_93 -> AVMetadataObjectTypeCode93Code
    Symbology.DATA_MATRIX -> AVMetadataObjectTypeDataMatrixCode
}
