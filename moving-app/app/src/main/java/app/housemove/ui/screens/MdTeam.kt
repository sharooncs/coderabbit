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
 * Two pieces the cost and task screens share: a line that adds something by saying it,
 * and everyone in the move as chips. Suggestions, Approve and Decline, and showing why
 * the Store refused something are in Shared.kt, the same for every screen.
 */

/**
 * One line for adding something by name without typing: a microphone to say it. Tapping
 * the line itself opens the keyboard's way ([onType]), for a phone with no speech or a
 * name that is hard to say. [label] is what the line reads; [name] is what a screen reader calls it.
 */
@Composable
internal fun MdAdder(label: String, name: String, prompt: String, sayIt: String, onType: () -> Unit, onSaid: (String) -> Unit) {
    GlassPill(Modifier.fillMaxWidth(), sheen = true, contentPadding = PaddingValues(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 4.dp)) {
        Row(
            Modifier.weight(1f).clip(CircleShape).clickable(role = Role.Button, onClick = onType)
                .semantics { contentDescription = name }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = Night.Mint)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Keyboard, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(6.dp))
        SpeakButton(prompt, contentDescription = sayIt, onText = onSaid)
    }
}

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
