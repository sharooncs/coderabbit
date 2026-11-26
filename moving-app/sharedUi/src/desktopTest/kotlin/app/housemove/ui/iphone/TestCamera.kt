package app.housemove.ui.iphone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import app.housemove.data.File
import app.housemove.ui.CameraPermission
import app.housemove.ui.LiveCamera
import app.housemove.ui.ScanKind

/**
 * The iPhone's camera in the desktop tests ([PhoneHooks.camera]), as demo mode's fake is on the iPhone: its live
 * picture is a still of a sofa in a room, a photo taken is a small file, and its scanner sees [codes], one after the
 * other, as soon as it opens (every one, as the real reader passes on every frame: IphonePlatform's gate comes after).
 * [allowed] is the camera permission. The photo picker counts how often it was opened.
 */
internal class TestCamera(
    var codes: List<String> = emptyList(),
    var allowed: Boolean = true,
    override val hasCamera: Boolean = true,
) : PhoneCamera {
    var pickerOpened = 0
    var beeps = 0
    var shots = 0

    @Composable
    override fun rememberPermission(): CameraPermission = CameraPermission(allowed) {}

    @Composable
    override fun PhotoView(modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        Still(modifier)
        val ready by rememberUpdatedState(onReady)
        LaunchedEffect(Unit) { ready(shutter) }
    }

    @Composable
    override fun CodeView(kind: ScanKind, onCode: (String) -> Unit, modifier: Modifier, onReady: (LiveCamera?) -> Unit) {
        Still(modifier)
        val ready by rememberUpdatedState(onReady)
        val callback by rememberUpdatedState(onCode)
        LaunchedEffect(Unit) {
            ready(shutter)
            codes.forEach { callback(it) }
        }
    }

    @Composable
    override fun rememberPicker(newFile: () -> File, onPhoto: (File) -> Unit, onDone: (Int) -> Unit): () -> Unit = { pickerOpened++ }

    override fun beep() {
        beeps++
    }

    private val shutter = object : LiveCamera {
        override fun torch(on: Boolean) {}

        override fun shoot(file: File, done: (Throwable?) -> Unit) {
            shots++
            file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            done(null)
        }
    }
}

/** The test camera's live picture: a sofa against a wall, on a wooden floor (demo mode's sample picture, drawn). */
@Composable
private fun Still(modifier: Modifier) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(Color(0xFFEFE3D3), size = Size(w, h * 0.75f))
            drawRect(Color(0xFFB4865A), topLeft = Offset(0f, h * 0.75f), size = Size(w, h * 0.25f))
            val sofa = Color(0xFF2E8B7A)
            drawRoundRect(sofa, Offset(w * 0.22f, h * 0.45f), Size(w * 0.56f, h * 0.22f), CornerRadius(w * 0.04f))
            drawRoundRect(Color(0xFF3AA38E), Offset(w * 0.19f, h * 0.6f), Size(w * 0.62f, h * 0.14f), CornerRadius(w * 0.04f))
            drawRoundRect(sofa, Offset(w * 0.15f, h * 0.53f), Size(w * 0.1f, h * 0.24f), CornerRadius(w * 0.05f))
            drawRoundRect(sofa, Offset(w * 0.75f, h * 0.53f), Size(w * 0.1f, h * 0.24f), CornerRadius(w * 0.05f))
            drawRoundRect(Color(0xFFF2B45C), Offset(w * 0.31f, h * 0.5f), Size(w * 0.13f, h * 0.11f), CornerRadius(w * 0.03f))
        }
    }
}
