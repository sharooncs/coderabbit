package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
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

private fun LabelSheet.short(): String = when (this) {
    LabelSheet.A4_21 -> "A4 sticker sheet"
    LabelSheet.LETTER_10 -> "Letter sticker sheet"
    LabelSheet.A4_PLAIN -> "A4 paper, cut out"
    LabelSheet.LETTER_PLAIN -> "Letter paper, cut out"
}

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
    // Which printout is being made right now ("labels" or "signs"), or null.
    var busy by remember { mutableStateOf<String?>(null) }

    /** Build a PDF off the main thread, then open or share it. */
    fun produce(what: String, share: Boolean, after: () -> Unit = {}, make: () -> File) {
        if (busy != null) return
        busy = what
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { make() }
            } catch (_: Exception) {
                null
            }
            busy = null
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
            // ---- box labels: what will be printed, the buttons, then the options ----
            Section {
                val s = start
                val n = count
                val ready = s != null && n != null
                val last = if (s != null && n != null) s + n - 1 else 0
                val stickers = (n ?: 0) * copies
                val pages = ceil(stickers / sheet.perPage.toDouble()).toInt()
                Printout(
                    Pic.Label,
                    title = if (s != null && ready) "${labelCode(s)} – ${labelCode(last)}" else "Box labels",
                    sub = if (ready) "$stickers stickers on $pages page${if (pages == 1) "" else "s"}. Print at 100% size."
                    else "Enter a first number (1–9999) and how many boxes (1–500).",
                )
                val codes = if (s != null && n != null) (s until s + n).map { labelCode(it) } else emptyList()
                val markPrinted: () -> Unit = { store.setMove { it.copy(labelsPrinted = maxOf(it.labelsPrinted, last)) } }
                PrintButtons(
                    enabled = ready && busy == null,
                    busy = busy == "labels",
                    onPrint = { produce("labels", share = false, after = markPrinted) { Pdf.labels(context, codes, sheet, copies) } },
                    onShare = { produce("labels", share = true, after = markPrinted) { Pdf.labels(context, codes, sheet, copies) } },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberInput(
                        start?.toDouble(),
                        onChange = { v -> start = v?.toInt()?.takeIf { it in 1..9999 } },
                        label = "First number",
                        modifier = Modifier.weight(1f),
                        key = "start",
                    )
                    NumberInput(
                        count?.toDouble(),
                        onChange = { v -> count = v?.toInt()?.takeIf { it in 1..500 } },
                        label = "How many boxes",
                        modifier = Modifier.weight(1f),
                        key = "count",
                    )
                }
                if (s != null && s <= data.move.labelsPrinted) {
                    IconFlag(Icons.Default.Warning, "Up to ${labelCode(data.move.labelsPrinted)} are made already. Start after that", error = true)
                }

                IconChoice(
                    LabelSheet.entries.map { Choice(it, it.short(), if (it.outline) Icons.Default.ContentCut else Icons.Default.ViewModule) },
                    sheet,
                    onPick = { sheet = it },
                    columns = 2,
                )
                Hint(sheet.label)
                IconChoice(
                    listOf(
                        Choice(2, "2 stickers a box: top and side", Icons.Default.LooksTwo),
                        Choice(1, "1 sticker a box", Icons.Default.LooksOne),
                    ),
                    copies,
                    onPick = { copies = it },
                )
            }

            // ---- door signs ----
            Section {
                val rooms = data.roomsOf(Home.DEST)
                Printout(
                    Pic.Room,
                    title = "Door signs",
                    sub = if (rooms.isEmpty()) "Add the rooms of your new home first. Each one gets its own sign."
                    else "One page for each room in ${data.move.destName}, in its colour.",
                )
                if (rooms.isEmpty()) {
                    Button(onClick = { nav.open("rooms") }) { Text("Add rooms") }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        rooms.forEach { RoomBadge(it) }
                    }
                    PrintButtons(
                        enabled = busy == null,
                        busy = busy == "signs",
                        onPrint = { produce("signs", share = false) { Pdf.doorSigns(context, data, letter) } },
                        onShare = { produce("signs", share = true) { Pdf.doorSigns(context, data, letter) } },
                    )
                    ChipPicker(listOf(false, true), letter, label = { if (it) "Letter paper" else "A4 paper" }, onPick = { letter = it })
                }
            }

            Fold("How it works", Icons.Default.HelpOutline, summary = "Stickers for boxes, coloured signs for doors") {
                Text("Every box gets a sticker with a number and a square code. Scanning the code opens that box in the app: what's inside, where it goes, where it is now.")
                Text("Every room in the new home gets a sign on its door in its own colour, so anyone carrying a box can see where to put it down. Tape the signs up before unloading starts.")
                Text("Print more stickers than you think you need; spare numbers cost nothing. Big furniture can have one too. A black-and-white printer works: names and codes are printed large.")
                Text("No printer today? Skip the stickers: the app gives each box a short code to write on with a marker.")
            }
        }
    }
}

/** What will come out of the printer: a picture, the range or name in large type, and one line about it. */
@Composable
private fun Printout(icon: ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Hint(sub)
        }
    }
}

@Composable
private fun PrintButtons(enabled: Boolean, busy: Boolean, onPrint: () -> Unit, onShare: () -> Unit) {
    val print: @Composable (Modifier) -> Unit = { m ->
        Button(enabled = enabled, onClick = onPrint, modifier = m.heightIn(min = 52.dp)) {
            // While the PDF is being made, the picture on the button turns into a spinner.
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Print, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Open to print")
        }
    }
    val share: @Composable (Modifier) -> Unit = { m ->
        OutlinedButton(enabled = enabled, onClick = onShare, modifier = m.heightIn(min = 52.dp)) {
            Icon(Icons.Default.Share, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
    }
    // Side by side where there is room; stacked on a narrow screen or with large text.
    val config = LocalConfiguration.current
    if (config.screenWidthDp / config.fontScale >= 360f) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            print(Modifier.weight(1f))
            share(Modifier)
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            print(Modifier.fillMaxWidth())
            share(Modifier.fillMaxWidth())
        }
    }
}
