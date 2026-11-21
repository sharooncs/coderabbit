package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.*
import app.housemove.ui.*

private val PRINTED_LABEL = Regex("^L\\d+$", RegexOption.IGNORE_CASE)

@Composable
fun BoxScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val box = data.box(id)
    if (box == null) {
        Screen("Box") { EmptyHint("This box is no longer here. It may have been deleted on another phone.") }
        return
    }

    // Always change the newest copy, so two quick edits never overwrite each other.
    val edit: ((Box) -> Box) -> Unit = { f -> store.data.value.box(id)?.let { store.put(f(it)) } }

    var confirmDelete by remember { mutableStateOf(false) }
    var relabel by remember { mutableStateOf(false) }
    val dest = data.room(box.destRoomId)
    val handwritten = !PRINTED_LABEL.matches(box.label)
    val labelClash = box.label.uppercase() in data.duplicateLabels()

    Screen(
        "Box ${box.label}",
        actions = {
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete this box") }
        },
    ) {
        ScrollColumn {
            // ---- label ----
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        box.label,
                        fontSize = 64.sp,
                        lineHeight = 68.sp,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                    )
                    Text(
                        if (handwritten) "Write this code on the top and one side of the box"
                        else "Printed label. Put one on top and one on a side",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (handwritten) FontWeight.Bold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (labelClash) {
                        Spacer(Modifier.height(6.dp))
                        Flag("This label is also on something else. Change one of them", error = true)
                    }
                }
            }

            // ---- destination ----
            Section("Going to") {
                val rc = dest?.let { roomColor(it.color) }
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(rc?.color() ?: MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(
                        if (dest != null && rc != null) "${dest.name} · ${rc.name}" else "No room chosen yet",
                        color = rc?.onColor() ?: MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                RoomPicker(Home.DEST, box.destRoomId, onPick = { r -> edit { it.copy(destRoomId = r) } })
                Text("Coming from", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RoomPicker(Home.ORIGIN, box.originRoomId, onPick = { r -> edit { it.copy(originRoomId = r) } })
            }

            // ---- contents ----
            Section("What's inside") {
                TextInput(
                    box.contents,
                    onChange = { v -> edit { it.copy(contents = v) } },
                    label = "Contents",
                    key = box.id,
                    singleLine = false,
                    mic = true,
                    placeholder = "e.g. pans, cutlery, kettle",
                )
                Hint("A few words are enough. Tap the microphone to say them instead of typing.")
                PhotoStrip(box.id, PhotoKind.CONTENTS, label = "Photo of the open box")
            }

            // ---- handling ----
            Section("Handling") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = box.fragile, onClick = { edit { it.copy(fragile = !it.fragile) } }, label = { Text("Fragile") })
                    FilterChip(selected = box.heavy, onClick = { edit { it.copy(heavy = !it.heavy) } }, label = { Text("Heavy") })
                    FilterChip(selected = box.thisWayUp, onClick = { edit { it.copy(thisWayUp = !it.thisWayUp) } }, label = { Text("This way up") })
                }
                Text("Unpack", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipPicker(Priority.entries, box.priority, { it.label }, { p -> edit { it.copy(priority = p) } })
                Text("Box size", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipPicker(BoxSize.entries, box.size, { it.label }, { s -> edit { it.copy(size = s) } })
                SwitchRow(
                    "Travels with me",
                    box.travelsWithMe,
                    onChange = { v -> edit { it.copy(travelsWithMe = v) } },
                    sub = "Goes in your own car or bag, not in the van",
                )
                SwitchRow(
                    "Hazardous",
                    box.hazardous,
                    onChange = { v -> edit { it.copy(hazardous = v) } },
                    sub = "Paint, gas, fuel, aerosols, bleach. Keep it out of the van",
                )
            }

            // ---- seal ----
            if (!box.sealed) {
                Button(onClick = { edit { it.copy(sealed = true) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text("Seal box", style = MaterialTheme.typography.titleMedium)
                }
                val missing = listOfNotNull(
                    "a room to go to".takeIf { dest == null },
                    "a note or photo of what's inside".takeIf {
                        box.contents.isBlank() && data.photosOf(box.id, PhotoKind.CONTENTS).isEmpty()
                    },
                )
                if (missing.isNotEmpty()) Hint("Before you seal it, add ${missing.joinToString(" and ")}.")
            } else {
                Section {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(10.dp))
                        Text("Sealed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { edit { it.copy(sealed = false) } }) { Text("Open again") }
                    }
                    Button(
                        onClick = { if (!nav.popBackStack("pack", inclusive = false)) nav.popBackStack() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Pack the next box") }
                }
            }

            // ---- where it is ----
            Section("Where it is now") {
                ChipPicker(Stage.entries, box.stage, { stageText(it, true) }, { s -> edit { it.copy(stage = s) } })
                Hint("This changes by itself when the box is scanned on and off the van.")
            }

            // ---- damage ----
            Section("Damage") {
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

            // ---- notes ----
            Section("Notes") {
                TextInput(
                    box.notes,
                    onChange = { v -> edit { it.copy(notes = v) } },
                    label = "Notes",
                    key = box.id + ":notes",
                    singleLine = false,
                )
                if (box.packedBy.isNotBlank()) Hint("Packed by ${box.packedBy}")
            }

            OutlinedButton(onClick = { relabel = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("Change label")
            }
        }
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
