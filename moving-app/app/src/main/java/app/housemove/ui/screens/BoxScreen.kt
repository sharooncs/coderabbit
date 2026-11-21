package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.*
import app.housemove.ui.*

private val PRINTED_LABEL = Regex("^L\\d+$", RegexOption.IGNORE_CASE)

/** The same picture drawn smaller, standing on the same floor, so sizes can be told apart at a glance. */
private fun ImageVector.shrunk(scale: Float): ImageVector =
    ImageVector.Builder(name + scale, defaultWidth, defaultHeight, viewportWidth, viewportHeight).apply {
        addGroup(scaleX = scale, scaleY = scale, pivotX = viewportWidth / 2, pivotY = viewportHeight * 0.92f)
        root.forEach { node ->
            if (node is VectorPath) {
                addPath(
                    node.pathData, node.pathFillType, node.name, node.fill, node.fillAlpha, node.stroke, node.strokeAlpha,
                    node.strokeLineWidth, node.strokeLineCap, node.strokeLineJoin, node.strokeLineMiter,
                )
            }
        }
        clearGroup()
    }.build()

private val SIZE_ICONS: Map<BoxSize, ImageVector> by lazy {
    mapOf(
        BoxSize.S to Pic.Box.shrunk(0.5f),
        BoxSize.M to Pic.Box.shrunk(0.67f),
        BoxSize.L to Pic.Box.shrunk(0.84f),
        BoxSize.XL to Pic.Box,
    )
}

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}

@Composable
fun BoxScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val box = data.box(id)
    if (box == null) {
        Screen("Box") {
            EmptyState(Pic.Box, "This box is no longer here", text = "It may have been deleted on another phone.")
        }
        return
    }

    // Always change the newest copy, so two quick edits never overwrite each other.
    val edit: ((Box) -> Box) -> Unit = { f -> store.data.value.box(id)?.let { store.put(f(it)) } }

    var confirmDelete by remember { mutableStateOf(false) }
    var relabel by remember { mutableStateOf(false) }
    var scanIn by remember { mutableStateOf(false) }
    val vehicle = data.vehicle()
    val entries = data.entriesOf(box.id)
    val dest = data.room(box.destRoomId)
    val handwritten = !PRINTED_LABEL.matches(box.label)
    val labelClash = box.label.uppercase() in data.duplicateLabels()
    val missing = listOfNotNull(
        "a room to go to".takeIf { dest == null },
        "what's inside".takeIf {
            box.contents.isBlank() && entries.isEmpty() && data.photosOf(box.id, PhotoKind.CONTENTS).isEmpty()
        },
    )

    Screen(
        "Box ${box.label}",
        actions = {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete this box") }
        },
    ) {
        ScrollColumn(Modifier.weight(1f)) {
            LabelCard(box.label, handwritten, labelClash)

            // ---- where it goes ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Heading("Going to", Modifier.weight(1f))
                    if (dest == null) IconFlag(Icons.Default.ErrorOutline, "Choose a room", error = true)
                }
                RoomTiles(Home.DEST, box.destRoomId, onPick = { r -> edit { it.copy(destRoomId = r) } })
            }

            // ---- what's inside ----
            PhotoHero(box.id, PhotoKind.CONTENTS, height = 190.dp, emptyText = "Photo of the open box")
            TextInput(
                box.contents,
                onChange = { v -> edit { it.copy(contents = v) } },
                label = "Contents",
                key = box.id,
                singleLine = false,
                mic = true,
                placeholder = "A few words: pans, cutlery, kettle",
            )
            OutlinedButton(onClick = { scanIn = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan books and products into this box")
            }
            if (entries.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Scanned in (${entries.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    entries.take(3).forEach { e ->
                        Text(
                            e.title.ifBlank { "Barcode ${e.code}" } + if (e.author.isBlank()) "" else " · ${e.author}",
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (entries.size > 3) TextButton(onClick = { scanIn = true }) { Text("Show all ${entries.size}") }
                }
            }

            // ---- handling ----
            ToggleTiles(
                listOf(
                    Toggle("Fragile", Pic.Fragile, box.fragile) { v -> edit { it.copy(fragile = v) } },
                    Toggle("Heavy", Pic.Heavy, box.heavy) { v -> edit { it.copy(heavy = v) } },
                    Toggle("This way up", Pic.Upright, box.thisWayUp) { v -> edit { it.copy(thisWayUp = v) } },
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading("Unpack")
                IconChoice(
                    Priority.entries.map { Choice(it, it.label, it.icon()) },
                    box.priority,
                    onPick = { p -> edit { it.copy(priority = p) } },
                )
            }

            // ---- needed now and then: closed, each saying what is set ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Heading("More about this box")
                Fold("Coming from", Pic.Room, summary = data.room(box.originRoomId)?.name ?: "No room") {
                    RoomTiles(Home.ORIGIN, box.originRoomId, onPick = { r -> edit { it.copy(originRoomId = r) } })
                }
                Fold("Box size", Pic.Size, summary = box.size.label) {
                    IconChoice(
                        BoxSize.entries.map { Choice(it, it.label, SIZE_ICONS.getValue(it)) },
                        box.size,
                        onPick = { s -> edit { it.copy(size = s) } },
                    )
                }
                Fold(
                    "Keep off the $vehicle", Pic.WithMe, key = "off",
                    summary = listOfNotNull("Travels with me".takeIf { box.travelsWithMe }, "Hazardous".takeIf { box.hazardous })
                        .joinToString(" · ").ifEmpty { "No, it goes on the $vehicle" },
                ) {
                    ToggleTiles(
                        listOf(
                            Toggle("Travels with me", Pic.WithMe, box.travelsWithMe) { v -> edit { it.copy(travelsWithMe = v) } },
                            Toggle("Hazardous", Pic.Hazard, box.hazardous) { v -> edit { it.copy(hazardous = v) } },
                        ),
                    )
                    Hint("Hazardous: paint, gas, fuel, aerosols, bleach.")
                }
                Fold("Where it is now", Pic.Where, summary = stageText(box.stage, isBox = true, vehicle)) {
                    IconChoice(
                        Stage.entries.map { Choice(it, stageText(it, isBox = true, vehicle), it.icon()) },
                        box.stage,
                        onPick = { s -> edit { it.copy(stage = s) } },
                    )
                    if (data.uses(Part.MOVE_DAY)) Hint("Changes by itself when the box is scanned on or off the $vehicle.")
                }
                Fold(
                    "Damage", Pic.Damage, open = box.damaged,
                    summary = if (box.damaged) box.damageNote.ifBlank { "Damaged in the move" } else "None",
                ) {
                    SwitchRow("Damaged in the move", box.damaged, onChange = { v -> edit { it.copy(damaged = v) } })
                    if (box.damaged) {
                        TextInput(
                            box.damageNote,
                            onChange = { v -> edit { it.copy(damageNote = v) } },
                            label = "What happened",
                            key = box.id + ":damage",
                            singleLine = false,
                            mic = true,
                        )
                        PhotoStrip(box.id, PhotoKind.DAMAGE, label = "Photo of the damage")
                    }
                }
                Fold(
                    "Notes", Pic.Notes,
                    summary = box.notes.lineSequence().firstOrNull { it.isNotBlank() }
                        ?: if (box.packedBy.isBlank()) "None" else "Packed by ${box.packedBy}",
                ) {
                    TextInput(
                        box.notes,
                        onChange = { v -> edit { it.copy(notes = v) } },
                        label = "Notes",
                        key = box.id + ":notes",
                        singleLine = false,
                    )
                    if (box.packedBy.isNotBlank()) Hint("Packed by ${box.packedBy}")
                }
                Fold(
                    "Label", Pic.Label, open = labelClash,
                    summary = box.label + " · " + if (handwritten) "handwritten code" else "printed label",
                ) {
                    OutlinedButton(onClick = { relabel = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text("Change label")
                    }
                }
            }
        }

        SealBar(
            sealed = box.sealed,
            missing = missing,
            onSeal = { edit { it.copy(sealed = true) } },
            onOpen = { edit { it.copy(sealed = false) } },
            onNext = { if (!nav.popBackStack("pack", inclusive = false)) nav.popBackStack() },
        )
    }

    if (relabel) {
        ScanDialog(
            title = "Scan or type the new label",
            onCode = { raw ->
                val code = raw.trim()
                val taken = store.data.value.findByLabel(code)
                when {
                    code.isBlank() -> {}
                    code.startsWith("HMSYNC", ignoreCase = true) ->
                        toast(context, "That is a code for linking phones, not a box label.")
                    taken != null && taken.id != id ->
                        toast(context, "$code is already on ${taken.title}. Use a different label.")
                    else -> {
                        edit { it.copy(label = code) }
                        relabel = false
                    }
                }
            },
            onClose = { relabel = false },
        )
    }

    if (scanIn) ScanInDialog(box, onClose = { scanIn = false })

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete box ${box.label}?",
            text = "This record and its photos will be removed from every phone at the next sync.",
            onConfirm = {
                data.photosOf(box.id).forEach { store.delete(it) }
                store.delete(box)
                nav.popBackStack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** The code to look for on the cardboard, and where to put it. */
@Composable
private fun LabelCard(label: String, handwritten: Boolean, clash: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    fontSize = 36.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.Black,
                    color = scheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
                Spacer(Modifier.width(16.dp))
                Icon(
                    if (handwritten) Icons.Default.Edit else Pic.Label, contentDescription = null,
                    tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (handwritten) "Write it on the top and one side" else "Stick one on top, one on a side",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (handwritten) FontWeight.SemiBold else FontWeight.Normal,
                    color = scheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            if (clash) IconFlag(Icons.Default.Warning, "This label is used twice. Change one", error = true)
        }
    }
}

/** Fixed at the bottom of the box page, so sealing never needs scrolling. */
@Composable
private fun SealBar(sealed: Boolean, missing: List<String>, onSeal: () -> Unit, onOpen: () -> Unit, onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (sealed) scheme.tertiaryContainer else scheme.surfaceContainer,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!sealed) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (missing.isNotEmpty()) Hint("Still to add: ${missing.joinToString(", ")}")
                BigAction(Icons.Default.Lock, "Seal box", onSeal)
            }
        } else {
            val tick = @Composable {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(28.dp))
            }
            val state = @Composable { Text("Sealed", style = MaterialTheme.typography.titleMedium, color = scheme.onTertiaryContainer) }
            val reopen = @Composable { TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Open again") } }
            val next: @Composable (Modifier) -> Unit = { m ->
                Button(onClick = onNext, modifier = m.heightIn(min = 56.dp)) {
                    Icon(Pic.Box, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text("Pack the next box", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
            // Side by side where there is room; stacked on a narrow screen or with large text.
            val config = LocalConfiguration.current
            if (config.screenWidthDp / config.fontScale >= 360f) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    tick()
                    Spacer(Modifier.width(10.dp))
                    Column {
                        state()
                        reopen()
                    }
                    Spacer(Modifier.width(16.dp))
                    next(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        tick()
                        Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f)) { state() }
                        reopen()
                    }
                    next(Modifier.fillMaxWidth())
                }
            }
        }
    }
}
