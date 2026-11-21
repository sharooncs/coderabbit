package app.housemove.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.housemove.data.*
import app.housemove.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the strip under the camera says, and how: something went in, something to know, or nothing yet. */
private data class ScanNote(val text: String, val tone: GlassTone = GlassTone.Plain)

/**
 * Full-screen "scan things into this box": every book or product barcode the
 * camera sees is added to the container's list. Books get their title looked up
 * online if the person has agreed to that; otherwise they are kept by number
 * and can be named by saying the name, or by typing it.
 *
 * It stands on the night ground like every screen: the camera in a rounded window with
 * a glass rim, what just happened on a glass strip under it, and each thing scanned as
 * a glass row.
 */
@Composable
fun ScanInDialog(box: Box, onClose: () -> Unit) {
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val scope = rememberCoroutineScope()
    val entries = data.entriesOf(box.id)
    var note by remember { mutableStateOf(ScanNote("Point the camera at the barcode on a book or product")) }
    var typed by remember { mutableStateOf("") }
    // The keyboard is the last resort: its field only shows once it is asked for.
    var typing by remember { mutableStateOf(false) }
    var askLookup by remember { mutableStateOf(false) }

    fun lookUp(entryId: String, isbn: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    Result.success(BookLookup.fetch(isbn))
                } catch (e: IOException) {
                    Result.failure(e)
                }
            }
            val current = store.data.value.liveEntries.firstOrNull { it.id == entryId } ?: return@launch
            val info = result.getOrNull()
            when {
                result.isFailure -> note = ScanNote("No connection. Saved by its number; the title can be looked up later.", GlassTone.Amber)
                info == null -> note = ScanNote("Not in the catalogue. Say or type its title in the list below.", GlassTone.Amber)
                current.title.isBlank() -> {
                    store.put(current.copy(title = info.title, author = info.author))
                    note = ScanNote("Added: ${info.title}", GlassTone.Mint)
                }
            }
        }
    }

    fun add(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        val isCode = looksLikeBarcodeNumber(text)
        val code = if (isCode) text.replace("-", "").replace(" ", "") else ""
        val result = store.scanInto(box.id, code = code, title = if (isCode) "" else text)
        val e = result.entry
        val name = e.title.ifBlank { "barcode ${e.code}" }
        note = when (result) {
            is ScanIn.Added -> ScanNote("Added: $name", GlassTone.Mint)
            is ScanIn.AlreadyHere -> ScanNote("Already in here: $name", GlassTone.Amber)
            is ScanIn.Moved -> ScanNote("Moved here from ${result.fromLabel ?: "another one"}: $name", GlassTone.Mint)
        }
        if (e.title.isBlank() && isIsbn(e.code)) {
            when (store.device.value.bookLookup) {
                true -> lookUp(e.id, e.code)
                null -> askLookup = true
                false -> Unit
            }
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().drawBehind { drawNightGround() }) {
            CompositionLocalProvider(LocalContentColor provides Night.Text) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        GlassOrb(Icons.Default.Close, "Close", onClick = onClose)
                        Column(Modifier.weight(1f)) {
                            Text("Scan into", style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1)
                            Text(box.shownName(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        RowButton("Done", onClick = onClose, icon = Icons.Default.Check, look = RowButtonLook.Primary)
                    }
                    // The camera keeps the larger share; what just happened is written on a strip under it.
                    val window = RoundedCornerShape(24.dp)
                    QrScanner(
                        onText = { add(it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().weight(if (entries.isEmpty()) 3f else 1.3f)
                            .clip(window).border(1.dp, Night.GlassRim, window),
                        formats = ScanFormats.PRODUCTS,
                    )
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), note.tone, shadow = 4.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            when (note.tone) {
                                GlassTone.Mint -> Icons.Default.CheckCircle
                                GlassTone.Amber -> Icons.Default.Info
                                else -> Icons.Default.QrCodeScanner
                            },
                            contentDescription = null, tint = note.tone.accent(), modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            note.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                    // Something with no barcode: say what it is. Typing is there for when speaking will not do.
                    Row(Modifier.padding(start = 16.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SpeakButton("What is it?", contentDescription = "Say what it is", size = 40.dp) { said ->
                            contentsLines(said).forEach { add(it.replaceFirstChar(Char::uppercase)) }
                        }
                        Text(
                            "No barcode? Say what it is", style = MaterialTheme.typography.bodyMedium, color = Night.TextDim,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        GlassChip("Type it", icon = Icons.Default.Keyboard, selected = typing, onClick = { typing = !typing })
                    }
                    if (typing) {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                colors = glassFieldColors(),
                                value = typed,
                                onValueChange = { typed = it },
                                label = { Text("A barcode number or a name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            RowButton(
                                "Add", onClick = { if (typed.isNotBlank()) { add(typed); typed = "" } },
                                modifier = Modifier.enabledLook(typed.isNotBlank()),
                            )
                        }
                    }
                    val untitled = entries.filter { it.title.isBlank() && isIsbn(it.code) }
                    Row(Modifier.padding(start = 20.dp, end = 16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                        ListHeading(if (entries.isEmpty()) "Nothing scanned in yet" else "Scanned in (${entries.size})", Modifier.weight(1f))
                        if (untitled.isNotEmpty() && device.bookLookup == true) {
                            GlassChip(
                                "Look up ${untitled.size} missing title${if (untitled.size == 1) "" else "s"}", icon = Icons.Default.Search,
                                onClick = { untitled.forEach { lookUp(it.id, it.code) } },
                            )
                        }
                    }
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(entries, key = { it.id }) { e ->
                            Row(
                                Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), shadow = 0.dp).heightIn(min = 56.dp).padding(start = 12.dp, end = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (isIsbn(e.code)) Icons.AutoMirrored.Filled.MenuBook else Pic.Box, contentDescription = null, tint = Night.Mint)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                    if (e.title.isBlank()) {
                                        NameEntryRow(e)
                                    } else {
                                        Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val sub = listOf(e.author, e.code).filter { it.isNotBlank() }.joinToString(" · ")
                                        if (sub.isNotBlank()) Hint(sub)
                                    }
                                }
                                IconButton(onClick = { store.delete(e) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove ${e.title.ifBlank { e.code }}", tint = Night.TextDim)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (askLookup) {
        AlertDialog(
            modifier = Modifier.dialogRim(),
            onDismissRequest = { askLookup = false },
            title = { Text("Look up book titles online?") },
            text = {
                Text(
                    "Titles come from Open Library, a free public catalogue. Only the barcode number is sent. " +
                        "If you say no, you name the books yourself. You can change this in Settings."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    store.setDevice { it.copy(bookLookup = true) }
                    askLookup = false
                    store.data.value.entriesOf(box.id).filter { it.title.isBlank() && isIsbn(it.code) }.forEach { lookUp(it.id, it.code) }
                }) { Text("Look up titles") }
            },
            dismissButton = {
                TextButton(onClick = { store.setDevice { it.copy(bookLookup = false) }; askLookup = false }) { Text("No, stay offline") }
            },
        )
    }
}

/** Give a name to something that was saved by its barcode number only: say it, or type it. */
@Composable
private fun NameEntryRow(e: BoxEntry) {
    val store = LocalStore.current
    var typing by remember(e.id) { mutableStateOf(false) }
    var name by remember(e.id) { mutableStateOf("") }
    fun save(title: String) {
        store.data.value.liveEntries.firstOrNull { it.id == e.id }?.let { store.put(it.copy(title = title.trim())) }
    }
    if (!typing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Barcode ${e.code}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            SpeakButton("What is it called?", contentDescription = "Say the name for barcode ${e.code}", size = 40.dp) { save(it.replaceFirstChar(Char::uppercase)) }
            GlassOrb(Icons.Default.Edit, "Type a name for barcode ${e.code}", size = 40.dp, onClick = { typing = true })
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                colors = glassFieldColors(),
                value = name,
                onValueChange = { name = it },
                label = { Text("Name for barcode ${e.code}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            RowButton("Save", onClick = { if (name.isNotBlank()) save(name) }, modifier = Modifier.enabledLook(name.isNotBlank()))
        }
    }
}
