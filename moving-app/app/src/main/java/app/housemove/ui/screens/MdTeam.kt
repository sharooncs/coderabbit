package app.housemove.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.data.Action
import app.housemove.data.AppData
import app.housemove.data.Outcome
import app.housemove.data.Person
import app.housemove.data.Proposal
import app.housemove.data.Store
import app.housemove.data.personName
import app.housemove.data.refusal
import app.housemove.ui.*

/*
 * People, roles and suggestions as the cost and task screens show them.
 *
 * The rules themselves live in the Store. A screen only asks ([mdCan]) and then either
 * shows a thing, shows it in its "suggest" form, or leaves it out. When the Store still
 * says no, [mdTold] shows its reason. With one person in the move none of this appears.
 */

/** May this phone's person do [action]? Asked of the Store again whenever the data or the phone's person changes. */
@Composable
internal fun mdCan(action: Action): Boolean {
    val store = LocalStore.current
    // Read both, so whoever asks is asked again when a role changes or a sync brings news.
    appData()
    deviceInfo()
    return store.can(action)
}

/** More than one person in the move: only then do names, "yours first" and suggestions show at all. */
internal fun mdShared(data: AppData): Boolean = data.livePeople.size > 1

/** Say why when the Store refused something. True when it went through. */
internal fun mdTold(context: Context, outcome: Outcome<*>): Boolean {
    outcome.reason?.let { toast(context, it) }
    return outcome.ok
}

/**
 * For the Store's plain saves, which cannot answer: ask first whether this phone's person
 * may do [action], and say why not. True when the save may go ahead.
 */
internal fun mdMay(context: Context, store: Store, action: Action): Boolean {
    val why = store.data.value.refusal(store.me, action) ?: return true
    toast(context, why)
    return false
}

/** A person as others are named on this phone: "you" for its own person. */
internal fun mdWho(data: AppData, personId: String?, me: Person?): String =
    if (personId != null && personId == me?.id) "you" else data.personName(personId).ifBlank { "someone" }

private val MD_DECLINE_REASONS = listOf("Not needed", "Not now", "Already sorted")

/**
 * A suggestion, shown on the thing it is about: the amber "Waiting for approval" tag with
 * who suggested it, [what] it would change, and for an Organiser the buttons to approve or
 * decline it right there. Declining offers a few reasons to tap, or to say one.
 */
@Composable
internal fun MdApproval(p: Proposal, modifier: Modifier = Modifier, what: String? = null) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val mayApprove = mdCan(Action.APPROVE)
    var declining by remember(p.id) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassTag(
            if (p.by == store.me?.id) "You suggested this" else "Suggested by " + mdWho(data, p.by, store.me),
            "Waiting for approval", Icons.Default.HourglassTop,
        )
        val said = listOfNotNull(what?.takeIf { it.isNotBlank() }, p.note.takeIf { it.isNotBlank() }?.let { "“$it”" })
        if (said.isNotEmpty()) Text(said.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
        if (mayApprove && !declining) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MdRowButton("Approve", onClick = { mdTold(context, store.approve(p.id)) }, icon = Icons.Default.Check)
                MdRowButton("Decline", onClick = { declining = true }, forward = false, icon = Icons.Default.Close)
            }
        }
        if (mayApprove && declining) {
            Text("Why not?", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (MD_DECLINE_REASONS + "No reason").forEach { reason ->
                    GlassChip(reason, onClick = { mdTold(context, store.decline(p.id, if (reason == "No reason") "" else reason)) })
                }
                SpeakButton("Why not?", contentDescription = "Say why not") { mdTold(context, store.decline(p.id, it)) }
            }
        }
    }
}

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
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        data.livePeople.forEach { p ->
            val name = p.name.ifBlank { p.role.label }
            GlassChip(
                if (p.id == me?.id) "$name (you)" else name,
                selected = p.id == selectedId,
                onClick = { onPick(if (p.id == selectedId) null else p.id) },
            )
        }
    }
}
