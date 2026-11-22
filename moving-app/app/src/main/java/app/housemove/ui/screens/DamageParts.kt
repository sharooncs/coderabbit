package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/*
 * Damage, as reports of their own (design 2.1, sections 6 and 7): each report is a photo first, with a few
 * optional words (tapped or said), kept until an Organiser clears it. Two phones reporting damage to the
 * same thing make two reports; clearing one leaves the other.
 */

/**
 * Which damage photos belong to which report of [thingId]: each photo goes with the first report made after
 * it was taken (a report's photo is taken first), and a photo taken after the last report goes with that one.
 * Reports from before 2.1 have no time and take what is left over.
 */
internal fun AppData.damagePhotos(thingId: String): Map<String, List<Photo>> {
    val reports = damageOf(thingId, open = false).sortedBy { it.at }
    if (reports.isEmpty()) return emptyMap()
    val out = HashMap<String, MutableList<Photo>>()
    for (p in photosOf(thingId, PhotoKind.DAMAGE).sortedBy { it.createdAt }) {
        val r = reports.firstOrNull { it.at != 0L && it.at >= p.createdAt } ?: reports.last()
        out.getOrPut(r.id) { ArrayList() } += p
    }
    return out
}

/**
 * Reporting damage to one thing: a large target for the photo comes first, then what happened, as the usual
 * words to tap or said (typing waits behind the pencil). Save makes a new report of its own.
 */
@Composable
internal fun DamagePanel(thingId: String, title: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    // The photos taken while this is open are this report's.
    val before = remember(thingId) { store.data.value.photosOf(thingId, PhotoKind.DAMAGE).map { it.id }.toSet() }
    val fresh = data.photosOf(thingId, PhotoKind.DAMAGE).filter { it.id !in before }
    var note by rememberSaveable(thingId) { mutableStateOf("") }
    var camera by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), GlassTone.Red, sheen = true).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MdMark(MdTone.BAD, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Damage", style = MaterialTheme.typography.titleMedium)
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        // The photo first: one large target until there is one, then the photo with a way to take another.
        Box(Modifier.fillMaxWidth().height(160.dp).glass(RoundedCornerShape(20.dp), sheen = true, shadow = 6.dp)) {
            val last = fresh.lastOrNull()
            if (last == null) {
                Column(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Photograph the damage") { camera = true },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    GlassOrb(Icons.Default.PhotoCamera, null, size = 56.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("Photograph it", style = MaterialTheme.typography.titleMedium, color = Night.MintPale)
                }
            } else {
                PhotoThumb(last, Modifier.fillMaxSize().clickable { viewing = last })
                Row(Modifier.align(Alignment.BottomEnd).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (fresh.size > 1) GlassChip(countOf(fresh.size, "photo"))
                    GlassOrb(Icons.Default.PhotoCamera, "Take another photo of the damage", kind = OrbKind.Selected, size = 48.dp) { camera = true }
                }
            }
        }
        DamageText(note, { note = it }, key = "$thingId:new-damage")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowButton(
                "Save", onClick = { if (store.reportDamage(thingId, note.trim()).okOrSay(context)) onClose() },
                icon = Icons.Default.Check, look = RowButtonLook.Primary,
            )
            RowButton(
                "Cancel", onClick = {
                    // Photos taken for a report that was not made are not left behind.
                    fresh.forEach { store.tryDelete(it) }
                    onClose()
                },
                forward = false,
            )
        }
    }
    if (camera) PhotoCamera("Damage", onPhoto = { store.addPhoto(thingId, PhotoKind.DAMAGE, it) }, onClose = { camera = false })
    viewing?.let { p -> PhotoViewer(p, onClose = { viewing = null }) }
}

/**
 * The damage reports on one thing, on its own page: each open one with its photos, its words and who
 * reported it, and Clear for an Organiser; cleared ones folded away; "Report damage" for anyone.
 */
@Composable
internal fun DamageSection(thingId: String, title: String, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val all = data.damageOf(thingId, open = false)
    val open = all.filter { !it.cleared }
    val cleared = all.filter { it.cleared }
    val photos = data.damagePhotos(thingId)
    // Clearing a report is an Organiser's (or anyone's on a move nobody runs).
    val mayClear = store.can(Action.DECIDE)
    var reporting by rememberSaveable(thingId) { mutableStateOf(false) }
    var showCleared by rememberSaveable(thingId) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (open.isEmpty()) "No damage reported" else countOf(open.size, "damage report"),
                style = MaterialTheme.typography.labelLarge, color = if (open.isEmpty()) Night.TextDim else Night.RedPale, modifier = Modifier.weight(1f),
            )
            if (cleared.isNotEmpty()) {
                GlassChip("${cleared.size} cleared", icon = if (showCleared) Icons.Default.ExpandLess else Icons.Default.ExpandMore, onClick = { showCleared = !showCleared })
            }
        }
        open.forEach { d -> DamageRow(d, photos[d.id].orEmpty(), data, mayClear) { store.clearDamage(d.id).okOrSay(context) } }
        if (showCleared) cleared.forEach { d -> DamageRow(d, photos[d.id].orEmpty(), data, mayClear) { store.clearDamage(d.id, cleared = false).okOrSay(context) } }
        if (reporting) DamagePanel(thingId, title, onClose = { reporting = false })
        else RowButton("Report damage", onClick = { reporting = true }, forward = false, icon = Pic.Damage)
    }
}

/** One damage report: its photos, its words, who reported it and when, and Clear (or Reopen) for whoever may. */
@Composable
private fun DamageRow(d: Damage, photos: List<Photo>, data: AppData, mayClear: Boolean, onToggle: () -> Unit) {
    var viewing by remember { mutableStateOf<Photo?>(null) }
    GlassCard(Modifier.fillMaxWidth(), tone = if (d.cleared) GlassTone.Plain else GlassTone.Red, shape = RoundedCornerShape(18.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).widthIn(min = 180.dp), verticalAlignment = Alignment.CenterVertically) {
                val first = photos.lastOrNull()
                if (first != null) {
                    PhotoThumb(first, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).clickable { viewing = first })
                    Spacer(Modifier.width(10.dp))
                } else {
                    Icon(Pic.Damage, contentDescription = null, tint = if (d.cleared) Night.TextDim else Night.RedPale, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(d.note.ifBlank { "Damaged" }, style = MaterialTheme.typography.bodyLarge, color = if (d.cleared) Night.TextDim else Night.Text)
                    val who = data.personName(d.by)
                    val line = listOfNotNull(
                        if (d.cleared) "Cleared" else null,
                        who.ifBlank { null }?.let { "Reported by $it" },
                        if (d.at > 0) fmtDayShort(d.at / 86_400_000L) else null,
                        if (photos.size > 1) countOf(photos.size, "photo") else null,
                    ).joinToString(" · ")
                    if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
                }
            }
            if (mayClear) RowButton(if (d.cleared) "Reopen" else "Clear", onClick = onToggle, forward = false, icon = if (d.cleared) Icons.Default.Undo else Icons.Default.Check)
        }
    }
    viewing?.let { p -> PhotoViewer(p, onClose = { viewing = null }) }
}

/** The words of every open damage report on a thing, for a list: "Dented; Corner crushed". */
internal fun AppData.damageWords(thingId: String): String = damageOf(thingId).map { it.note.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
