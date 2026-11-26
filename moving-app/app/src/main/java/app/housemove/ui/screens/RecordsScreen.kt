package app.housemove.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import app.housemove.ui.icons.filled.PhotoLibrary
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.Add
import app.housemove.ui.icons.filled.Bolt
import app.housemove.ui.icons.filled.CheckCircle
import app.housemove.ui.icons.filled.Delete
import app.housemove.ui.icons.filled.LocalFireDepartment
import app.housemove.ui.icons.filled.PhotoCamera
import app.housemove.ui.icons.filled.Pin
import app.housemove.ui.icons.filled.Speed
import app.housemove.ui.icons.filled.WaterDrop
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.METER_KINDS
import app.housemove.data.PhotoKind
import app.housemove.data.Ratio
import app.housemove.data.Reading
import app.housemove.data.Store
import app.housemove.data.fmtDay
import app.housemove.data.newId
import app.housemove.data.today
import app.housemove.data.*
import app.housemove.ui.*

/** Change a reading using its latest saved copy, so quick edits never overwrite each other. */
private fun mdEditReading(store: Store, id: String, f: (Reading) -> Reading) {
    store.data.value.readings.firstOrNull { it.id == id && !it.deleted }?.let { store.put(f(it)) }
}

private fun mdMeterIcon(kind: String): ImageVector = when (kind) {
    "Electricity" -> Icons.Default.Bolt
    "Gas" -> Icons.Default.LocalFireDepartment
    "Water" -> Icons.Default.WaterDrop
    else -> Icons.Default.Speed
}

/** The reading of one kind of meter at a home, as saved right now: the latest if there are several. */
private fun mdReadingOf(store: Store, home: Home, kind: String): Reading? =
    store.data.value.liveReadings.filter { it.home == home && it.kind == kind }.maxByOrNull { it.updatedAt }

/** Dated photo evidence and meter readings for one of the two homes. */
@Composable
fun RecordsScreen(home: Home) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val rooms = data.roomsOf(home)
    val readings = data.liveReadings.filter { it.home == home }
    var deleting by remember { mutableStateOf<Reading?>(null) }
    // The kind of meter the camera is open for.
    var shooting by remember { mutableStateOf<String?>(null) }
    val homeName = if (home == Home.ORIGIN) data.move.originName else data.move.destName
    val photographed = rooms.count { data.photosOf(it.id, PhotoKind.ROOM).isNotEmpty() }

    Screen(if (home == Home.ORIGIN) "Old home record" else "New home record") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "head") {
                ProgressCard(
                    Ratio(photographed, rooms.size),
                    if (rooms.isEmpty()) homeName else "$photographed of ${rooms.size} rooms photographed",
                    sub = (if (rooms.isEmpty()) "" else "$homeName · ") +
                        if (home == Home.ORIGIN) "For your deposit and final bills" else "Before anything is carried in",
                )
            }

            // A photo of each meter is enough. Tapping one opens the camera; the reading is made by the photo.
            item(key = "meters") {
                TileGrid(METER_KINDS, columns = if (narrowScreen()) 2 else 4) { kind, m ->
                    val reading = readings.filter { it.kind == kind }.maxByOrNull { it.updatedAt }
                    MdMeterTile(kind, reading, m) { shooting = kind }
                }
            }

            // The numbers are optional: said or typed here, where the photos can be looked at again too.
            item(key = "numbers") {
                Fold(
                    "Meter numbers", Icons.Default.Pin,
                    summary = if (readings.isEmpty()) "Optional" else readings.joinToString(" · ") { r -> r.kind + " " + r.value.ifBlank { "–" } },
                    key = "meters:" + home.name,
                ) {
                    readings.forEach { r -> MdReadingEditor(r, onDelete = { deleting = r }) }
                    val missing = METER_KINDS.filter { k -> readings.none { it.kind == k } }
                    if (missing.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        missing.forEach { kind ->
                            GlassChip(kind, icon = Icons.Default.Add, onClick = { store.put(Reading(id = newId(), home = home, kind = kind, day = today())) })
                        }
                    }
                }
            }

            // One strip of photos for the home as a whole, then one per room. The camera comes first in each.
            item(key = "home") { MdRecordStrip("home:" + home.name, "Whole home and outside") }
            items(rooms, key = { "room:" + it.id }) { room -> MdRecordStrip(room.id, room.name) }
            if (rooms.isEmpty()) item(key = "norooms") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "No rooms for this home yet.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    RowButton("Add rooms", onClick = { nav.open("rooms") }, forward = false)
                }
            }
        }
    }

    shooting?.let { kind ->
        PhotoCamera(
            hint = "$kind meter",
            onPhoto = { file ->
                // The first photo of a meter makes its reading, dated today. The number can follow, or never.
                val reading = mdReadingOf(store, home, kind) ?: Reading(id = newId(), home = home, kind = kind, day = today()).also { store.put(it) }
                store.addPhoto(reading.id, PhotoKind.METER, file)
            },
            onClose = { shooting = null },
        )
    }

    deleting?.let { r ->
        ConfirmDialog(
            title = "Delete this reading?",
            text = "The ${r.kind} reading and its photos will be removed.",
            onConfirm = {
                // The reading first; then its photos, those this person may delete (the Store leaves the others).
                store.delete(r)
                data.photosOf(r.id).forEach { store.delete(it) }
            },
            onDismiss = { deleting = null },
        )
    }
}

/** One meter as a tile to tap for the camera. Its photo and a tick show once it has been read. */
@Composable
private fun MdMeterTile(kind: String, reading: Reading?, modifier: Modifier = Modifier, onShoot: () -> Unit) {
    val data = appData()
    val photo = reading?.let { data.photosOf(it.id, PhotoKind.METER).lastOrNull() }
    val done = photo != null || !reading?.value.isNullOrBlank()
    Column(
        modifier.glass(RoundedCornerShape(18.dp), if (done) GlassTone.Mint else GlassTone.Plain, shadow = 4.dp)
            .clickable(onClickLabel = "Photograph the $kind meter", role = Role.Button, onClick = onShoot)
            .heightIn(min = 104.dp).padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            if (photo != null) PhotoThumb(photo, Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)))
            else Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Icon(mdMeterIcon(kind), contentDescription = null, tint = Night.Mint, modifier = Modifier.size(30.dp))
            }
            Icon(
                if (done) Icons.Default.CheckCircle else Icons.Default.PhotoCamera,
                contentDescription = if (done) "Recorded" else null,
                tint = if (done) Night.MintLight else Night.TextDim, modifier = Modifier.size(16.dp),
            )
        }
        Text(kind, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val value = reading?.value.orEmpty()
        if (value.isNotBlank()) {
            Text(value, style = MaterialTheme.typography.labelSmall, color = Night.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One reading inside the fold: its number to say or type, its photos, when it was taken, and the way to delete it. */
@Composable
private fun MdReadingEditor(r: Reading, onDelete: () -> Unit) {
    val store = LocalStore.current
    // What was last said into it, so the field shows it at once.
    var said by remember(r.id) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextInput(
            said ?: r.value,
            { v -> mdEditReading(store, r.id) { it.copy(value = v) } },
            "${r.kind} reading",
            Modifier.weight(1f),
            key = r.id + ":" + said,
        )
        SpeakButton("Read out the number on the ${r.kind.lowercase()} meter", contentDescription = "Say the ${r.kind} reading") { heard ->
            // "4 8 2 1 3" is one number; anything else is kept as it was heard.
            val number = heard.filter { !it.isWhitespace() }.takeIf { n -> n.all { it.isDigit() || it == '.' || it == ',' } } ?: heard
            mdEditReading(store, r.id) { it.copy(value = number) }
            said = number
        }
        // A reading is part of the work, so anyone working on the move may delete it: the Store's rule says so.
        if (appData().canDelete(myPerson(), r)) GlassOrb(Icons.Default.Delete, "Delete this ${r.kind} reading", size = 40.dp, onClick = onDelete)
    }
    PhotoStrip(r.id, PhotoKind.METER, label = "${r.kind} meter, ${fmtDay(r.day)}")
    HorizontalDivider(color = Night.Hairline)
}

/**
 * The photos of one place as one glass row: a tick once it has been photographed, its
 * name and how many photos there are, the gallery and, brightest, the camera. What has
 * been taken follows underneath; tapping a photo opens it.
 */
@Composable
private fun MdRecordStrip(ownerId: String, name: String) {
    val store = LocalStore.current
    val context = LocalContext.current
    val photos = appData().photosOf(ownerId, PhotoKind.ROOM)
    val done = photos.isNotEmpty()
    var camera by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Photo?>(null) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        uris.forEach { uri ->
            val f = store.newPhotoFile()
            if (importImage(context, uri, f)) store.addPhoto(ownerId, PhotoKind.ROOM, f)
        }
    }
    GlassCard(Modifier.fillMaxWidth(), tone = if (done) GlassTone.Mint else GlassTone.Plain, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (done) Icons.Default.CheckCircle else Pic.Room, contentDescription = if (done) "Photographed" else null,
                tint = if (done) Night.MintLight else Night.TextDim, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (done) countOf(photos.size, "photo") else "No photos yet", style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
            }
            GlassOrb(Icons.Default.PhotoLibrary, "Add photos of $name from the gallery", size = 40.dp, onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            Spacer(Modifier.width(4.dp))
            GlassOrb(Icons.Default.PhotoCamera, "Photograph $name", kind = if (done) OrbKind.Plain else OrbKind.Selected, onClick = { camera = true })
        }
        if (done) {
            LazyRow(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it.id }) { p -> PhotoThumb(p, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).clickable { viewing = p }) }
            }
        }
    }
    if (camera) PhotoCamera(hint = name, onPhoto = { store.addPhoto(ownerId, PhotoKind.ROOM, it) }, onClose = { camera = false })
    viewing?.let { p -> PhotoViewer(p, onClose = { viewing = null }) }
}
