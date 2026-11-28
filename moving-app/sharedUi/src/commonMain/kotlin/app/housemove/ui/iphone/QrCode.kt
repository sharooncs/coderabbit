package app.housemove.ui.iphone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.data.qrPixels
import kotlin.math.floor

/**
 * [text] as a QR code, black on white, for another phone's camera: the shared encoder's modules (the same as
 * Android's ZXing draws), each a whole number of pixels so the edges stay sharp. VoiceOver reads [description].
 */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    // Size 0: one pixel per module, with the quiet zone of one module round it.
    val code = remember(text) { qrPixels(text, 0) }
    Canvas(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(12.dp)
            .semantics { contentDescription = description; role = Role.Image },
    ) {
        val n = code.size
        val cell = floor(minOf(size.width, size.height) / n)
        if (cell <= 0f) return@Canvas
        val left = floor((size.width - cell * n) / 2)
        val top = floor((size.height - cell * n) / 2)
        for (y in 0 until n) for (x in 0 until n) {
            if (code[x, y]) drawRect(Color.Black, Offset(left + x * cell, top + y * cell), Size(cell, cell))
        }
    }
}
