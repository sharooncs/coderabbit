package app.housemove.ui.screens

import android.content.Context
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
import app.housemove.data.*
import app.housemove.pdf.HelperCard
import app.housemove.pdf.LabelSheet
import app.housemove.pdf.Pdf
import app.housemove.pdf.Share
import app.housemove.pdf.TagSheet
import app.housemove.pdf.labelCode
import app.housemove.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.ceil

private const val PDF_MIME = "application/pdf"

/** Countries that print on Letter paper; everywhere else it is A4. */
private val LETTER_COUNTRIES = setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "PA", "GT", "SV", "CR", "DO", "PR")

/**
 * Makes the sheet of hang tags. The app draws a PDF; tests put their own in its place,
 * because Android's PDF writer does not exist where they run.
 */
fun interface TagPrinter {
    fun make(context: Context, codes: List<String>, sheet: TagSheet): File
}

val LocalTagPrinter = staticCompositionLocalOf { TagPrinter { context, codes, sheet -> Pdf.hangTags(context, codes, sheet) } }

/** Makes the Helper card. The app draws a PDF; tests put their own in its place, as for [TagPrinter]. */
fun interface CardPrinter {
    fun make(context: Context, data: AppData, paper: HelperCard.Paper): File
}

val LocalCardPrinter = staticCompositionLocalOf { CardPrinter { context, data, paper -> HelperCard.make(context, data, paper) } }

/**
 * The number the next printed code gets: one after the last made, and after any printed-style
 * code already on something (typed in by hand, say), so no code is ever printed twice.
 */
internal fun AppData.nextLabelNumber(): Int {
    val inUse = (liveBoxes.map { it.label } + liveItems.mapNotNull { it.label })
        .mapNotNull { c -> c.trim().takeIf { PRINTED_CODE.matches(it) }?.drop(1)?.take(9)?.toIntOrNull() }
        .maxOrNull() ?: 0
    return maxOf(move.labelsPrinted, inUse) + 1
}

/** One printing of hang tags: the luggage that gets a tag with its own code, then new numbers that fill the page. */
internal class TagRun(val luggage: List<Box>, val fresh: List<String>, val lastNumber: Int) {
    val codes: List<String> get() = luggage.map { it.label } + fresh
}

/**
 * What to print on hang tags. A suitcase or bag that was given a handwritten code (a
 * sticker does not stay on it) gets a proper tag with that same code; the rest of the
 * page is new numbers from [first] on, for the luggage still to be packed.
 */
internal fun AppData.hangTagRun(sheet: TagSheet, first: Int = nextLabelNumber()): TagRun {
    val luggage = liveBoxes
        .filter { (it.kind == ContainerKind.SUITCASE || it.kind == ContainerKind.BAG) && !PRINTED_CODE.matches(it.label) && it.stage != Stage.DONE }
        .sortedWith(CODE_ORDER).distinctBy { it.label.uppercase() }
    val pages = maxOf(1, ceil(luggage.size / sheet.perPage.toDouble()).toInt())
    val spare = pages * sheet.perPage - luggage.size
    val fresh = ArrayList<String>()
    var n = first
    while (fresh.size < spare && n <= 9999) {
        val code = labelCode(n)
        if (findByLabel(code) == null) fresh += code
        n++
    }
    return TagRun(luggage, fresh, if (fresh.isEmpty()) first - 1 else n - 1)
}

/**
 * Everything that is printed: stickers for boxes, hang tags for suitcases and bags, and
 * signs for the doors of the new home. The paper is chosen once, for all three; numbers
 * carry on from the last ones made, so nothing needs typing to print the next sheet.
 */
@Composable
fun LabelsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val scope = rememberCoroutineScope()
    val tagPrinter = LocalTagPrinter.current
    val cardPrinter = LocalCardPrinter.current

    var letter by rememberSaveable { mutableStateOf(Locale.getDefault().country in LETTER_COUNTRIES) }
    var plain by rememberSaveable { mutableStateOf(false) }
    val sheet = when {
        letter && plain -> LabelSheet.LETTER_PLAIN
        letter -> LabelSheet.LETTER_10
        plain -> LabelSheet.A4_PLAIN
        else -> LabelSheet.A4_21
    }
    var copies by rememberSaveable { mutableStateOf(2) }
    // The first number follows the last one made until this sheet has been made (or a number is typed):
    // from then on it stays put, so "Open to print" followed by "Share" gives the same sheet.
    var fixedStart by rememberSaveable { mutableStateOf<Int?>(null) }
    var badStart by rememberSaveable { mutableStateOf(false) }
    var sheets by rememberSaveable { mutableStateOf(4) }
    var exact by rememberSaveable { mutableStateOf<Int?>(null) }
    var badCount by rememberSaveable { mutableStateOf(false) }
    // Goes up when a chip sets the numbers, so the fields under "Other numbers" show them.
    var round by rememberSaveable { mutableStateOf(0) }
    var tagStart by rememberSaveable { mutableStateOf<Int?>(null) }
    // Which printout is being made right now ("labels", "tags" or "signs"), or null.
    var busy by remember { mutableStateOf<String?>(null) }

    /** Build a PDF off the main thread, then open or share it. */
    fun produce(what: String, share: Boolean, after: () -> Unit = {}, make: () -> File) {
        if (busy != null) return
        busy = what
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) { make() }
            } catch (e: CancellationException) {
                throw e // the screen was left while the file was being made
            } catch (_: Throwable) {
                null
            }
            busy = null
            if (file == null) {
                toast(context, "Could not create the PDF. Please try again.")
            } else {
                after()
                try {
                    if (share) Share.send(context, file, PDF_MIME) else Share.open(context, file, PDF_MIME)
                } catch (_: Exception) {
                    toast(context, "The PDF was made, but no app could open it.")
                }
            }
        }
    }

    val next = data.nextLabelNumber()

    Screen("Labels and door signs") {
        ScrollColumn {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ListHeading("Paper")
                ChipPicker(listOf(false, true), letter, label = { if (it) "Letter" else "A4" }, onPick = { letter = it })
            }

            // ---- box labels: what will be printed, the buttons, then the options ----
            Section {
                val s = if (badStart) null else fixedStart ?: next
                val n = if (badCount) null else exact ?: (sheets * sheet.perPage / copies).coerceIn(1, 500)
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
                val markPrinted: () -> Unit = {
                    fixedStart = s
                    store.setMove { it.copy(labelsPrinted = maxOf(it.labelsPrinted, last)) }
                }
                PrintButtons(
                    enabled = ready && busy == null,
                    busy = busy == "labels",
                    onPrint = { produce("labels", share = false, after = markPrinted) { Pdf.labels(context, codes, sheet, copies) } },
                    onShare = { produce("labels", share = true, after = markPrinted) { Pdf.labels(context, codes, sheet, copies) } },
                )
                if (s != null && s < next) {
                    GlassChip(
                        "These are made. Next: from ${labelCode(next)}", icon = Icons.Default.SkipNext, tone = GlassTone.Amber,
                        onClick = { fixedStart = null; badStart = false; round++ },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Pages", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                    ChipPicker(
                        listOf(1, 2, 4, 6, 10), sheets.takeIf { exact == null && !badCount },
                        label = { "$it" },
                        onPick = { sheets = it; exact = null; badCount = false; round++ },
                        modifier = Modifier.weight(1f),
                    )
                }
                // What is set once and then left alone: the kind of sheet, stickers per box, other numbers.
                Fold(
                    "Sheet and numbers", Pic.More, open = !ready,
                    summary = listOfNotNull(
                        if (plain) "Plain paper" else "Sticker sheet",
                        if (copies == 2) "2 a box" else "1 a box",
                        if (s != null && n != null) "from ${labelCode(s)}" else null,
                    ).joinToString(" · "),
                ) {
                    IconChoice(
                        listOf(
                            Choice(false, "Sticker sheet", Icons.Default.ViewModule),
                            Choice(true, "Plain paper, cut out", Icons.Default.ContentCut),
                        ),
                        plain,
                        onPick = { plain = it },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberInput(
                            s?.toDouble(),
                            onChange = { v -> fixedStart = v?.toInt()?.takeIf { it in 1..9999 }; badStart = fixedStart == null },
                            label = "First number",
                            modifier = Modifier.weight(1f),
                            key = "start:$round",
                        )
                        NumberInput(
                            n?.toDouble(),
                            onChange = { v -> exact = v?.toInt()?.takeIf { it in 1..500 }; badCount = exact == null },
                            label = "How many boxes",
                            modifier = Modifier.weight(1f),
                            key = "count:$round:$sheet:$copies",
                        )
                    }
                }
            }

            // ---- hang tags: a sticker does not stay on a suitcase or a bag ----
            Section {
                val tagSheet = if (letter) TagSheet.LETTER_TAGS else TagSheet.A4_TAGS
                val first = tagStart ?: next
                val run = data.hangTagRun(tagSheet, first)
                val codes = run.codes
                val pages = ceil(codes.size / tagSheet.perPage.toDouble()).toInt()
                Printout(
                    Icons.Default.Luggage,
                    title = "Hang tags",
                    sub = "${codes.size} tags on $pages page${if (pages == 1) "" else "s"} for suitcases and bags. Cut out, punch a hole, tie to the handle.",
                )
                // What will be on them: the luggage already packed under a handwritten code, then new numbers.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    run.luggage.forEach { GlassChip(it.label, icon = it.kind.picture(), tone = GlassTone.Mint) }
                    if (run.fresh.isNotEmpty()) {
                        GlassChip(if (run.fresh.size == 1) run.fresh.first() else "${run.fresh.first()} – ${run.fresh.last()}", icon = Pic.Label)
                    }
                }
                val markPrinted: () -> Unit = {
                    tagStart = first
                    store.setMove { it.copy(labelsPrinted = maxOf(it.labelsPrinted, run.lastNumber)) }
                }
                PrintButtons(
                    enabled = codes.isNotEmpty() && busy == null,
                    busy = busy == "tags",
                    onPrint = { produce("tags", share = false, after = markPrinted) { tagPrinter.make(context, codes, tagSheet) } },
                    onShare = { produce("tags", share = true, after = markPrinted) { tagPrinter.make(context, codes, tagSheet) } },
                )
                if (run.fresh.isNotEmpty() && first < next) {
                    GlassChip(
                        "These are made. Next: from ${labelCode(next)}", icon = Icons.Default.SkipNext, tone = GlassTone.Amber,
                        onClick = { tagStart = null },
                    )
                }
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
                    RowButton("Add rooms", onClick = { nav.open("rooms") })
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
                }
            }

            // ---- the Helper card: half a page that works without the app ----
            Section {
                val paper = if (letter) HelperCard.Paper.LETTER else HelperCard.Paper.A4
                Printout(
                    Icons.Default.Badge,
                    title = "Helper card",
                    sub = "Half a page for each Helper: five steps, the door colours, and what to write on a box with no label. Two to a page.",
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassChip("Write ${HelperCard.exampleCode(data)} on two sides", icon = Icons.Default.Edit)
                }
                PrintButtons(
                    enabled = busy == null,
                    busy = busy == "card",
                    onPrint = { produce("card", share = false) { cardPrinter.make(context, data, paper) } },
                    onShare = { produce("card", share = true) { cardPrinter.make(context, data, paper) } },
                )
            }

            Fold("How it works", Icons.Default.HelpOutline, summary = "Stickers, hang tags and door signs") {
                Text("Every box gets a sticker with a number and a square code. Scanning the code opens that box in the app: what's inside, where it goes, where it is now.")
                Text("A sticker does not stay on a suitcase or a bag, so those get a tag tied to the handle. It carries the same kind of number and code.")
                Text("Every room in the new home gets a sign on its door in its own colour, so anyone carrying a box can see where to put it down. Tape the signs up before unloading starts.")
                Text("Print more than you think you need; spare numbers cost nothing. A black-and-white printer works. No printer today? The app gives each box a short code to write on with a marker.")
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
    FileButtons("Open to print", enabled, onOpen = onPrint, onShare = onShare, openIcon = Icons.Default.Print, busy = busy)
}
