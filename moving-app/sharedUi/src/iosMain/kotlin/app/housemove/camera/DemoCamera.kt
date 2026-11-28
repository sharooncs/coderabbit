@file:OptIn(ExperimentalForeignApi::class)

package app.housemove.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import app.housemove.data.File
import app.housemove.demoCodes
import app.housemove.ui.CameraPermission
import app.housemove.ui.LiveCamera
import app.housemove.ui.ScanKind
import app.housemove.ui.iphone.PhoneCamera
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.CoreFoundation.CFRetain
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSProcessInfo
import platform.QuartzCore.CALayer
import platform.QuartzCore.kCAGravityResizeAspectFill
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIRectFill

/*
 * Demo mode's camera and scanner, for the UI tests in the simulator, which has no camera. They exist only in demo mode
 * (`-HMDemoHost YES`, which only Xcode and the UI tests can pass: IosHooks makes a DemoCamera only then), and they go
 * through the same screens, the same scanner rules (IphonePlatform: the gate, the buzz, the beep) and the same JPEG
 * saving as the real ones.
 *   - The fake camera "takes" a sample picture drawn here (a sofa in a room), 2000 x 1500, saved as a photo is (1600 x
 *     1200, quality 82). Its live picture is that sample, in the same UIKit view the real camera's picture is in.
 *   - The fake scanner "reads" the text given with the launch argument `-HMFakeScan <text>`, once, two seconds after it
 *     opens (time for a UI test's picture of it): a label (`-HMFakeScan HM:A1`), a barcode, or one of the sample move's
 *     own codes, named `demo-sync-code` (Get fresh data) and `demo-invite`. Without the argument it reads nothing.
 *   - Choosing photos is the real picker: it works in the simulator.
 */

/** Demo mode's camera: the fake camera and scanner, and the real photo picker of [real]. */
internal class DemoCamera(private val real: AppleCamera) : PhoneCamera {
    override val hasCamera: Boolean get() = true

    /** Allowed: the simulator asks nothing, so the UI tests meet no question of iOS's. */
    @Composable
    override fun rememberPermission(): CameraPermission = remember { CameraPermission(true) {} }

    @Composable
    override fun PhotoView(modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        FakePicture(modifier, "Demo camera: it takes a sample picture")
        val ready by rememberUpdatedState(onReady)
        LaunchedEffect(Unit) { ready(FakeShutter) }
    }

    @Composable
    override fun CodeView(kind: ScanKind, onCode: (String) -> Unit, modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        val text = remember { fakeScanText() }
        FakePicture(modifier, if (text == null) "Demo scanner: nothing to read" else "Demo scanner")
        val ready by rememberUpdatedState(onReady)
        val callback by rememberUpdatedState(onCode)
        LaunchedEffect(text) {
            ready(FakeShutter)
            if (text != null) {
                delay(2_000)
                callback(text)
            }
        }
    }

    @Composable
    override fun rememberPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): (() -> Unit)? =
        real.rememberPicker(newFile, onPhoto, onDone)

    override fun beep() = real.beep()
}

/**
 * What the fake scanner reads: `-HMFakeScan` as the app was launched with it, the sample move's sync code for
 * `demo-sync-code` and its invite for `demo-invite`; null without one. Read as given (ProcessInfo), as demo mode is.
 */
internal fun fakeScanText(): String? {
    val args = NSProcessInfo.processInfo.arguments.map { it.toString() }
    val i = args.indexOf("-HMFakeScan")
    if (i < 0) return null
    val given = args.getOrNull(i + 1)?.takeIf { it.isNotBlank() } ?: return null
    return when (given) {
        "demo-sync-code" -> demoCodes?.syncCode
        "demo-invite" -> demoCodes?.inviteCode
        else -> given
    }
}

/** The fake's live picture: the sample, in the real camera's UIKit view, with a few words saying it is the demo's. */
@Composable
private fun FakePicture(modifier: Modifier, words: String) {
    Box(modifier) {
        UIKitView(
            factory = {
                val still = CALayer()
                still.contents = CFBridgingRelease(CFRetain(sample.CGImage))
                still.contentsGravity = kCAGravityResizeAspectFill
                PreviewHost(still)
            },
            modifier = Modifier.fillMaxSize(),
            properties = UIKitInteropProperties(interactionMode = null, isNativeAccessibilityEnabled = false),
        )
        Text(
            words, color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.Center).background(Color(0xB8081220), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** The fake camera: no torch, and each photo is the sample picture, saved as the real camera saves one. */
private object FakeShutter : LiveCamera {
    override fun torch(on: Boolean) {}

    override fun shoot(file: File, done: (Throwable?) -> Unit) {
        inBackground {
            val ok = saveJpeg(sampleJpeg, file)
            onMain { done(if (ok) null else Exception("the demo picture could not be saved")) }
        }
    }
}

/** The sample picture: a green sofa with a cushion, against a wall, on a wooden floor. 2000 x 1500, as a camera's 4:3. */
private val sample: UIImage by lazy {
    val format = UIGraphicsImageRendererFormat()
    format.scale = 1.0
    format.opaque = true
    UIGraphicsImageRenderer(size = CGSizeMake(2000.0, 1500.0), format = format).imageWithActions { _ ->
        fun colour(rgb: Int) = UIColor.colorWithRed(((rgb shr 16) and 0xff) / 255.0, ((rgb shr 8) and 0xff) / 255.0, (rgb and 0xff) / 255.0, 1.0)
        fun box(rgb: Int, x: Double, y: Double, w: Double, h: Double) {
            colour(rgb).setFill()
            UIRectFill(CGRectMake(x, y, w, h))
        }
        fun round(rgb: Int, x: Double, y: Double, w: Double, h: Double, r: Double) {
            colour(rgb).setFill()
            UIBezierPath.bezierPathWithRoundedRect(CGRectMake(x, y, w, h), cornerRadius = r).fill()
        }
        box(0xEFE3D3, 0.0, 0.0, 2000.0, 1120.0) // the wall
        box(0xB4865A, 0.0, 1120.0, 2000.0, 380.0) // the floor
        round(0x2E8B7A, 440.0, 560.0, 1120.0, 420.0, 70.0) // the sofa's back
        round(0x3AA38E, 380.0, 880.0, 1240.0, 260.0, 60.0) // its seat
        round(0x2E8B7A, 300.0, 760.0, 200.0, 420.0, 80.0) // its arms
        round(0x2E8B7A, 1500.0, 760.0, 200.0, 420.0, 80.0)
        round(0xF2B45C, 620.0, 700.0, 260.0, 200.0, 50.0) // a cushion
        box(0x4A3828, 420.0, 1170.0, 44.0, 90.0) // its feet
        box(0x4A3828, 1536.0, 1170.0, 44.0, 90.0)
    }
}

/** The sample as a camera's JPEG. */
private val sampleJpeg: NSData by lazy { UIImageJPEGRepresentation(sample, 0.9) ?: NSData() }
