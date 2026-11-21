package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/**
 * Full-screen "scan things into this box": every book or product barcode the
 * camera sees is added to the box's list. Books get their title looked up
 * online if the person has agreed to that; otherwise they are kept by number
 * and can be named by hand.
 */
@Composable
fun ScanInDialog(box: Box, onClose: () -> Unit) {
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    val scope = rememberCoroutineScope()
    val entries = data.entriesOf(box.id)
    var status by remember { mutableStateOf("Point the camera at the barcode on a book or product") }
    var typed by remember { mutableStateOf("") }
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
                result.isFailure -> status = "No connection. Saved by its number; the title can be looked up later."
                info == null -> status = "Not in the catalogue. Type its title in the list below."
                current.title.isBlank() -> {
                    store.put(current.copy(title = info.title, author = info.author))
                    status = "Added: ${info.title}"
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
        status = when (result) {
            is ScanIn.Added -> "Added: $name"
            is ScanIn.AlreadyHere -> "Already in this box: $name"
            is ScanIn.Moved -> "Moved here from box ${result.fromLabel ?: "?"}: $name"
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
        Surface(Modifier.fillMaxSize(), color = Night.Ground1, contentColor = Night.Text) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    Text("Scan into box ${box.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Button(onClick = onClose) { Text("Done") }
                }
                // The camera keeps the larger share; what just happened is written in a strip along its foot.
                QrScanner(
                    onText = { add(it) },
                    modifier = Modifier.fillMaxWidth().weight(if (entries.isEmpty()) 3f else 1.3f),
                    formats = ScanFormats.PRODUCTS,
                )
                Surface(color = Night.PanelHigh, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        status,
                        color = Night.Text,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        colors = glassFieldColors(),
                        value = typed,
                        onValueChange = { typed = it },
                        label = { Text("Or type a barcode number or a name", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = typed.isNotBlank(), onClick = { add(typed); typed = "" }) { Text("Add") }
                }
                val untitled = entries.filter { it.title.isBlank() && isIsbn(it.code) }
                Row(Modifier.padding(start = 16.dp, end = 8.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (entries.isEmpty()) "Nothing scanned into this box yet" else "In this box (${entries.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (untitled.isNotEmpty() && device.bookLookup == true) {
                        TextButton(onClick = { untitled.forEach { lookUp(it.id, it.code) } }) {
                            Text("Look up ${untitled.size} missing title${if (untitled.size == 1) "" else "s"}")
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(entries, key = { it.id }) { e ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isIsbn(e.code)) Icons.Default.MenuBook else Pic.Box, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(14.dp))
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
                                Icon(Icons.Default.Close, contentDescription = "Remove ${e.title.ifBlank { e.code }}")
                            }
                        }
                        HorizontalDivider()
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
                        "If you say no, you type the titles yourself. You can change this in Settings."
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

/** Type a name for something that was saved by its barcode number only. */
@Composable
private fun NameEntryRow(e: BoxEntry) {
    val store = LocalStore.current
    var name by remember(e.id) { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            colors = glassFieldColors(),
            value = name,
            onValueChange = { name = it },
            label = { Text("Name for barcode ${e.code}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            enabled = name.isNotBlank(),
            onClick = { store.data.value.liveEntries.firstOrNull { it.id == e.id }?.let { store.put(it.copy(title = name.trim())) } },
        ) { Text("Save") }
    }
}
