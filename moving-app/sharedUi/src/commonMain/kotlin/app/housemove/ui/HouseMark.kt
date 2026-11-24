package app.housemove.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.min

/**
 * The app's mark: a house outline with an amber window on a soft mint disc. Drawn from the Android launcher icon
 * (res/drawable/ic_launcher_fg.xml, the visible 72 of its 108 units), as the iPhone shell's SwiftUI HouseMark was.
 * Give it a size; [contentDescription] is what VoiceOver and TalkBack read for it.
 */
@Composable
fun HouseMark(modifier: Modifier = Modifier, contentDescription: String = "HouseMove") {
    Canvas(modifier.semantics { this.contentDescription = contentDescription; role = Role.Image }) {
        val k = min(size.width, size.height) / 72f
        fun p(x: Float, y: Float) = Offset((x - 18f) * k, (y - 18f) * k)
        drawCircle(Night.Mint.copy(alpha = 0.2f), radius = 30f * k, center = Offset(36f * k, 36f * k))
        val house = Path().apply {
            p(35.5f, 52.5f).let { moveTo(it.x, it.y) }
            p(54f, 37f).let { lineTo(it.x, it.y) }
            p(72.5f, 52.5f).let { lineTo(it.x, it.y) }
            p(72.5f, 72f).let { lineTo(it.x, it.y) }
            p(35.5f, 72f).let { lineTo(it.x, it.y) }
            close()
        }
        drawPath(house, Night.MintLight, style = Stroke(width = 4.5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawRect(Night.Amber, topLeft = p(48.5f, 57.5f), size = Size(11f * k, 11f * k))
    }
}
