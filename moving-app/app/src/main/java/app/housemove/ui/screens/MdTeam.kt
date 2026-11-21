package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/*
 * Everyone in the move as chips, for the task screen. Adding by name (QuickAdd),
 * suggestions, Approve and Decline, and showing why the Store refused something are in
 * Shared.kt, the same for every screen.
 */

/** Everyone in the move as chips: tap a name to choose that person, tap it again for nobody in particular. */
@Composable
internal fun MdPeople(selectedId: String?, onPick: (String?) -> Unit, modifier: Modifier = Modifier) {
    val data = appData()
    val me = LocalStore.current.me
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        data.livePeople.forEach { p ->
            val name = p.shownName()
            GlassChip(
                if (p.id == me?.id) "$name (you)" else name,
                selected = p.id == selectedId,
                onClick = { onPick(if (p.id == selectedId) null else p.id) },
            )
        }
    }
}
