package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.pdf.LabelSheet
import app.housemove.pdf.Pdf
import app.housemove.pdf.Share
import app.housemove.pdf.labelCode
import app.housemove.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

private const val PDF_MIME = "application/pdf"

@Composable
fun LabelsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val scope = rememberCoroutineScope()

    var sheet by rememberSaveable { mutableStateOf(LabelSheet.A4_21) }
    // Starts after the last printed number; stays put while this page is open so
    // "Open to print" followed by "Share" gives the same sheet.
    var start by rememberSaveable { mutableStateOf<Int?>(data.move.labelsPrinted + 1) }
    var count by rememberSaveable { mutableStateOf<Int?>(42) }
    var copies by rememberSaveable { mutableStateOf(2) }
    var letter by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    /** Build a PDF off the main thread, then open or share it. */
    fun produce(share: Boolean, after: () -> Unit = {}, make: () -> File) {
        if (busy) return
        busy = true
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { make() }
            } catch (_: Exception) {
                null
            }
            busy = false
            if (file == null) {
                toast(context, "Could not create the PDF. Please try again.")
            } else {
                after()
                if (share) Share.send(context, file, PDF_MIME) else Share.open(context, file, PDF_MIME)
            }
        }
    }

    Screen("Labels and door signs") {
        ScrollColumn {
            Section("How it works") {
                Text("Every box gets a sticker with a number and a square code. Scanning the code opens that box in the app: what's inside, where it goes, where it is now.")
                Text("Every room in the new home gets a sign on its door in its own colour, so anyone carrying a box can see where to put it down.")
                Hint("No printer today? You can skip the stickers: the app gives each box a short code to write on with a marker.")
            }

            // ---- QR labels ----
            Section("Box labels") {
                Text("Paper", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    LabelSheet.entries.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { sheet = s }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = s == sheet, onClick = { sheet = s })
                            Text(s.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberInput(
                        start?.toDouble(),
                        onChange = { v -> start = v?.toInt()?.takeIf { it in 1..9999 } },
                        label = "First number",
                        modifier = Modifier.weight(1f),
                    )
                    NumberInput(
                        count?.toDouble(),
                        onChange = { v -> count = v?.toInt()?.takeIf { it in 1..500 } },
                        label = "How many boxes",
                        modifier = Modifier.weight(1f),
                    )
                }
                if (data.move.labelsPrinted > 0) {
                    Hint("Labels up to ${labelCode(data.move.labelsPrinted)} have been made already. Start after that so no two boxes share a number.")
                }

                Text("Stickers per box", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ChipPicker(
                    listOf(2, 1),
                    copies,
                    label = { if (it == 2) "2: top and one side (best)" else "1" },
                    onPick = { copies = it },
                )

                val s = start
                val n = count
                if (s != null && n != null) {
                    val last = s + n - 1
                    val stickers = n * copies
                    val pages = ceil(stickers / sheet.perPage.toDouble()).toInt()
                    Text(
                        "${labelCode(s)} – ${labelCode(last)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Hint("$stickers stickers on $pages page${if (pages == 1) "" else "s"}. Print at 100% size (no \"fit to page\") so they line up with sticker sheets.")
                    val codes = (s until s + n).map { labelCode(it) }
                    val markPrinted: () -> Unit = { store.setMove { it.copy(labelsPrinted = maxOf(it.labelsPrinted, last)) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !busy, onClick = { produce(share = false, after = markPrinted) { Pdf.labels(context, codes, sheet, copies) } }) {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open to print")
                        }
                        OutlinedButton(enabled = !busy, onClick = { produce(share = true, after = markPrinted) { Pdf.labels(context, codes, sheet, copies) } }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share")
                        }
                    }
                } else {
                    Hint("Enter a first number (1–9999) and how many boxes (1–500).")
                }
                Hint("Print more than you think you need; spare numbers cost nothing. Big furniture can have a sticker too.")
            }

            // ---- door signs ----
            Section("Door signs") {
                val rooms = data.roomsOf(Home.DEST)
                if (rooms.isEmpty()) {
                    Text("Add the rooms of your new home first; each one gets its own sign.")
                    TextButton(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                } else {
                    Text("One page for each of the ${rooms.size} room${if (rooms.size == 1) "" else "s"} in ${data.move.destName}, in the room's colour.")
                    ChipPicker(listOf(false, true), letter, label = { if (it) "Letter paper" else "A4 paper" }, onPick = { letter = it })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(enabled = !busy, onClick = { produce(share = false) { Pdf.doorSigns(context, data, letter) } }) {
                            Icon(Icons.Default.Print, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open to print")
                        }
                        OutlinedButton(enabled = !busy, onClick = { produce(share = true) { Pdf.doorSigns(context, data, letter) } }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share")
                        }
                    }
                    Hint("Tape each sign to its door before unloading starts. A black-and-white printer works too: the room name and code are printed large.")
                }
            }

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Making the PDF…")
                }
            }
        }
    }
}
