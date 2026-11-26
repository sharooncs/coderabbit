package app.housemove.ui.screens

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.automirrored.filled.MenuBook
import app.housemove.ui.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.*
import app.housemove.ui.*

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

/**
 * How many chips "What's inside" shows before "more": what is in it already first, then the
 * usual things to add, two or three rows in all, so the items and Seal stay in reach. Of
 * the usual things a few always show, however long the list of what is inside.
 */
private const val CHIPS_SHOWN = 9
private const val CHIPS_SHOWN_NARROW = 6
private const val USUAL_KEPT = 3

/**
 * One container, laid out for packing it without the keyboard. Everything about it is
 * already filled in from the last one packed in the same room, so the first screenful is
 * only what a packer looks at or taps: the photo and the room it goes to, one line that
 * says what kind of container it is and what kind of things go in it (each opens to
 * change), what is inside (tap, say or scan), the catalogued items to put in and, fixed at
 * the bottom, Seal. Everything rarely changed waits below in three closed sections.
 */
@Composable
fun BoxScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    deviceInfo() // whose phone this is decides whether deleting, or asking for it, is offered
    val box = data.box(id)
    if (box == null) {
        Screen("Box") {
            EmptyState(Pic.Box, "This box is no longer here", text = "It may have been deleted on another phone.")
        }
        return
    }

    // Always change the newest copy, so two quick edits never overwrite each other.
    val edit: ((Box) -> Box) -> Unit = { f -> store.data.value.box(id)?.let { store.put(f(it)) } }

    var relabel by remember { mutableStateOf(false) }
    var scanIn by remember { mutableStateOf(false) }
    var scanItem by remember { mutableStateOf(false) }
    var scanOuter by remember { mutableStateOf(false) }
    // Why the Store refused the last thing asked about items, and about containers.
    var itemTrouble by remember { mutableStateOf<String?>(null) }
    var nestTrouble by remember { mutableStateOf<String?>(null) }
    var destOpen by rememberSaveable(id) { mutableStateOf(false) }
    // Which of the two pickers under the ticket is open: "kind", "contents" or neither.
    var picking by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var allChips by rememberSaveable(id) { mutableStateOf(false) }
    var typing by rememberSaveable(id) { mutableStateOf(false) }
    // Goes up whenever the contents change from outside the text field, so the field shows them.
    var typingRound by remember { mutableIntStateOf(0) }

    val vehicle = data.vehicle()
    val word = box.kind.word()
    val entries = data.entriesOf(box.id)
    val items = data.itemsIn(box.id)
    val children = data.childrenOf(box.id)
    val parent = data.parentOf(box)
    val dest = data.room(box.destRoomId)
    val category = data.categoryOf(box)
    val handwritten = !PRINTED_CODE.matches(box.label)
    val labelClash = box.label.uppercase() in data.duplicateLabels()
    // Catalogued things that could go in: not packed yet, and in the same place as this. Those from
    // the same room are offered as pictures; anything else goes in by scanning its own label.
    val nearby = data.looseItems().filter { it.stage == box.stage }
    val loose = nearby.filter { it.originRoomId == box.originRoomId && it.couldBePacked() }
    val anyLoose = nearby.isNotEmpty()
    // What is inside, line by line: each line is a record of its own, added and removed on its own.
    val lines = data.linesOf(box.id).map { it.text }.ifEmpty { storedLines(box.contents) }
    val photos = data.photosOf(box.id, PhotoKind.CONTENTS).size
    val missing = listOfNotNull(
        "a room to go to".takeIf { dest == null && parent == null },
        "what's inside".takeIf { box.contents.isBlank() && entries.isEmpty() && items.isEmpty() && children.isEmpty() && photos == 0 },
    )
    val narrow = narrowScreen(360f)

    /** Add lines to what is inside, or take one away: each is a change of its own, so another phone's lines stay. */
    fun addLines(vararg more: String) {
        store.addLines(id, *more)
        typingRound++
    }

    fun removeLine(line: String) {
        store.removeLine(id, line)
        typingRound++
    }

    Screen(box.shownName()) {
        ScrollColumn(Modifier.weight(1f)) {
            Ticket(
                box, dest, handwritten, labelClash, narrow,
                // With no room chosen (and none to inherit from an outer container) the rooms are open already.
                destOpen = destOpen || (dest == null && parent == null && data.roomsOf(Home.DEST).isNotEmpty()),
                onToggleDest = { destOpen = !destOpen },
                onDest = { r ->
                    edit { it.copy(destRoomId = r) }
                    destOpen = false
                },
            )
            // Someone has asked for it to be deleted: an Organiser settles it here, whoever asked can withdraw it.
            DeleteRequests(box.id)
            if (parent != null) {
                GlassPill(Modifier.fillMaxWidth(), tone = GlassTone.Amber, contentPadding = PaddingValues(start = 16.dp, end = 4.dp)) {
                    Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = null, tint = Night.AmberPale, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Inside ${parent.nameAndCode()}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { nav.open("box/${parent.id}") }.padding(vertical = 12.dp),
                    )
                    RowButton("Take out", onClick = { nestTrouble = store.nest(id, null).reason }, forward = false)
                }
                nestTrouble?.let { ContainerRefusal(it) { nestTrouble = null } }
            }

            // ---- what it is, and what kind of things go in it: one line, each half opens to change ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PickerPill("Kind", box.kind.tileLabel(), box.kind.picture(), open = picking == "kind", Modifier.weight(1f).fillMaxHeight()) {
                        picking = if (picking == "kind") null else "kind"
                    }
                    PickerPill("Contents", category, categoryPicture(category), open = picking == "contents", Modifier.weight(1f).fillMaxHeight()) {
                        picking = if (picking == "contents") null else "contents"
                    }
                }
                if (picking == "kind") {
                    ContainerTiles(
                        ContainerKind.entries.map { Choice(it, it.tileLabel(), it.picture()) }, box.kind, columns = 3,
                        onPick = { k ->
                            edit { store.data.value.withKind(it, k) }
                            picking = null
                        },
                    )
                }
                if (picking == "contents") {
                    ContainerTiles(
                        BOX_CATEGORIES.map { Choice(it, it, categoryPicture(it)) }, category, columns = if (narrow) 3 else 4,
                        onPick = { c ->
                            edit { it.copy(category = c) }
                            picking = null
                        },
                    )
                }
            }

            // ---- what's inside ----
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ListHeading("What's inside", Modifier.weight(1f))
                    SpeakButton("What is in the $word?", contentDescription = "Say what's inside", size = 40.dp) { said ->
                        addLines(*contentsLines(said).map { l -> l.replaceFirstChar(Char::uppercase) }.toTypedArray())
                    }
                    GlassOrb(Icons.Default.QrCodeScanner, "Scan barcodes of books and products", size = 40.dp) { scanIn = true }
                    GlassOrb(Icons.Default.Edit, "Edit what's inside", kind = if (typing) OrbKind.Selected else OrbKind.Plain, size = 40.dp) { typing = !typing }
                }
                // What is in it already, ticked; then the usual things for this category, one tap each.
                // A long list is cut short behind "more", so what comes below stays in reach.
                val usual = contentsChips(category, data.room(box.originRoomId)?.name, box.contents)
                // Two rows on most phones (360-399dp wide), three on a large one.
                val budget = if (narrowScreen(400f)) CHIPS_SHOWN_NARROW else CHIPS_SHOWN
                val shownLines = if (allChips) lines else lines.take(budget - minOf(USUAL_KEPT, usual.size))
                val shownUsual = if (allChips) usual else usual.take((budget - shownLines.size).coerceAtLeast(0))
                val hidden = lines.size - shownLines.size + usual.size - shownUsual.size
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shownLines.forEach { line -> GlassChip(line, selected = true, onClick = { removeLine(line) }) }
                    shownUsual.forEach { chip -> GlassChip(chip, onClick = { addLines(chip) }) }
                    if (hidden > 0) GlassChip("$hidden more", icon = Icons.Default.ExpandMore, tone = GlassTone.Amber, onClick = { allChips = true })
                    else if (allChips && lines.size + usual.size > budget) {
                        GlassChip("Fewer", icon = Icons.Default.ExpandLess, tone = GlassTone.Amber, onClick = { allChips = false })
                    }
                }
                // Typed lines are saved when the field is left (or closed, or Done is pressed), never on each
                // keystroke: a half-typed word never becomes a line that is then taken away again.
                if (typing) ContentsField(id, lines.joinToString(", "), key = "$id:$typingRound")
                if (entries.isNotEmpty()) ScannedInRow(entries) { scanIn = true }
            }

            // ---- catalogued items packed in it ----
            if (items.isEmpty() && loose.isEmpty() && anyLoose) {
                // Nothing from this room to tap: one line, as for putting it in another container.
                GlassPill(Modifier.fillMaxWidth(), onClick = { scanItem = true }) {
                    Icon(Pic.Item, contentDescription = null, tint = Night.Mint)
                    Spacer(Modifier.width(14.dp))
                    Text("Put an item in", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(20.dp))
                }
                itemTrouble?.let { ContainerRefusal(it) { itemTrouble = null } }
            } else if (items.isNotEmpty() || anyLoose) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ListHeading(if (items.isEmpty()) "Items inside" else "Items inside (${items.size})", Modifier.weight(1f))
                        GlassOrb(Icons.Default.QrCodeScanner, "Scan an item's label to put it in", size = 40.dp) { scanItem = true }
                    }
                    items.forEach { i ->
                        ContainerItemRow(i, onOpen = { nav.open("item/${i.id}") }) {
                            GlassOrb(Icons.Default.Close, "Take ${i.title()} out", size = 36.dp, onClick = { itemTrouble = store.takeOut(i.id).reason })
                        }
                    }
                    if (loose.isNotEmpty()) {
                        Hint("Tap to put in" + (data.room(box.originRoomId)?.let { ": loose in ${it.name}" } ?: ""))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(loose, key = { it.id }) { i -> LooseTile(i) { itemTrouble = store.putInto(i.id, id).reason } }
                        }
                    }
                    itemTrouble?.let { ContainerRefusal(it) { itemTrouble = null } }
                }
            }

            // ---- other containers packed in it ----
            if (children.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListHeading("Containers inside (${children.size})")
                    children.forEach { c ->
                        ContainerRow(
                            c, data, showRoom = false, onOpen = { nav.open("box/${it.id}") },
                            trailing = {
                                GlassOrb(Icons.Default.Close, "Take ${c.shownName()} out", size = 36.dp, onClick = { nestTrouble = store.nest(c.id, null).reason })
                            },
                        )
                    }
                }
            }

            // ---- handling: what whoever carries it must know ----
            ToggleTiles(
                listOf(
                    Toggle("Fragile", Pic.Fragile, box.fragile) { v -> edit { it.copy(fragile = v) } },
                    Toggle("Heavy", Pic.Heavy, box.heavy) { v -> edit { it.copy(heavy = v) } },
                    Toggle("This way up", Pic.Upright, box.thisWayUp) { v -> edit { it.copy(thisWayUp = v) } },
                ),
            )

            // ---- needed now and then: three closed sections, each saying what is set ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (parent == null && children.isEmpty()) {
                    GlassPill(Modifier.fillMaxWidth(), onClick = { scanOuter = true }) {
                        Icon(Icons.Default.MoveToInbox, contentDescription = null, tint = Night.Mint)
                        Spacer(Modifier.width(14.dp))
                        Text("Put in another container", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(20.dp))
                    }
                }
                if (parent == null) nestTrouble?.let { ContainerRefusal(it) { nestTrouble = null } }

                Fold(
                    "Packing details", Pic.More, key = "packing",
                    // What is set, most telling first; the usual "unpack whenever" goes without saying.
                    summary = listOfNotNull(
                        box.size.label,
                        ("Unpack " + box.priority.label.lowercase()).takeIf { box.priority != Priority.WHENEVER },
                        "With me".takeIf { box.travelsWithMe },
                        "Hazardous".takeIf { box.hazardous },
                        "from " + (data.room(box.originRoomId)?.name ?: "no room"),
                    ).joinToString(" · "),
                ) {
                    FoldLabel("When to unpack it")
                    IconChoice(
                        Priority.entries.map { Choice(it, it.label, it.icon()) },
                        box.priority,
                        onPick = { p -> edit { it.copy(priority = p) } },
                        columns = if (narrow) 2 else 4,
                    )
                    FoldLabel(if (box.kind == ContainerKind.CARTON) "Box size" else "Size")
                    IconChoice(
                        BoxSize.entries.map { Choice(it, it.label, SIZE_ICONS.getValue(it)) },
                        box.size,
                        onPick = { s -> edit { it.copy(size = s) } },
                        columns = if (narrow) 2 else 4,
                    )
                    FoldLabel("Keep off the $vehicle")
                    ToggleTiles(
                        listOf(
                            Toggle("Travels with me", Pic.WithMe, box.travelsWithMe) { v -> edit { it.copy(travelsWithMe = v) } },
                            Toggle("Hazardous", Pic.Hazard, box.hazardous) { v -> edit { it.copy(hazardous = v) } },
                        ),
                    )
                    Hint("Hazardous: paint, gas, fuel, aerosols, bleach.")
                    FoldLabel("Room it comes from")
                    RoomTiles(Home.ORIGIN, box.originRoomId, onPick = { r -> edit { it.copy(originRoomId = r) } })
                }

                // Where it is now and any damage: a section of its own once it has left the old home or was
                // damaged. Before that it is rarely wanted, and waits at the end of "More".
                val travelled = box.stage != Stage.AT_ORIGIN || box.damaged
                val onTheMove: @Composable ColumnScope.() -> Unit = {
                    FoldLabel("Where it is now")
                    IconChoice(
                        Stage.entries.filter { it != Stage.STORED || data.offersStorage() || box.stage == Stage.STORED }
                            .map { Choice(it, stageText(it, isBox = true, vehicle), it.icon()) },
                        box.stage,
                        onPick = { s -> edit { it.copy(stage = s) } },
                        columns = if (narrow) 2 else 4,
                    )
                    HorizontalDivider(color = Night.Hairline)
                    DamageSection(box.id, box.nameAndCode())
                }
                if (travelled) Fold(
                    "On the move", Pic.Where, open = box.damaged, key = "move",
                    summary = data.statusText(box.toMovable()) + if (box.damaged) " · " + box.damageNote.ifBlank { "Damaged" } else "",
                ) { onTheMove() }

                // The name, the label, the photos, and what is not undone: deleting it.
                Fold(
                    "More", Pic.Notes, open = labelClash, key = "more",
                    summary = listOfNotNull(
                        box.label + " · " + if (handwritten) "handwritten code" else "printed label",
                        if (photos == 0) null else countOf(photos, "photo"),
                        box.notes.lineSequence().firstOrNull { it.isNotBlank() },
                        box.packedBy.ifBlank { null }?.let { "Packed by $it" },
                    ).joinToString(" · "),
                ) {
                    TextInput(
                        box.name,
                        onChange = { v -> edit { it.copy(name = v) } },
                        label = "Name",
                        key = "$id:name:${box.kind}",
                        placeholder = "${box.kind.noun} ${box.label}",
                    )
                    QuickText(
                        box.notes, { v -> edit { it.copy(notes = v) } }, label = "Notes", prompt = "Anything to note about it?",
                        key = "$id-notes", empty = "No notes",
                    )
                    if (box.packedBy.isNotBlank()) Hint("Packed by ${box.packedBy}")
                    PhotoStrip(box.id, PhotoKind.CONTENTS, label = "Photos of what's inside")
                    BigAction(Icons.Default.QrCodeScanner, "Change label", onClick = { relabel = true }, primary = false)
                    if (!travelled) {
                        HorizontalDivider(color = Night.Hairline)
                        onTheMove()
                    }
                    if (deleteOffered(box)) HorizontalDivider(color = Night.Hairline)
                    val kept = items.isNotEmpty() || children.isNotEmpty()
                    DeleteOrAsk(
                        box, "Delete this $word", "Delete ${box.nameAndCode()}?",
                        "This record and your photos of it will be removed from every phone at the next sync." +
                            if (kept) " What is packed in it stays in the app." else "",
                        Modifier.align(Alignment.CenterHorizontally),
                        onDelete = { store.tryDelete(box).okOrSay(context) },
                        onDeleted = { nav.popBackStack() },
                    )
                }
            }
        }

        SealBar(
            word = word,
            sealed = box.sealed,
            missing = missing,
            // Sealing accepts the category that was suggested, so the next one from this room starts the same.
            onSeal = { edit { it.copy(sealed = true, category = it.category.ifBlank { category }) } },
            onOpen = { edit { it.copy(sealed = false) } },
            onNext = {
                if (!nav.popBackStack("pack", inclusive = false)) {
                    nav.popBackStack()
                    nav.open("pack")
                }
            },
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

    if (scanIn) ScanInDialog(box, onClose = { scanIn = false; typingRound++ })

    if (scanItem) {
        ScanDialog(
            title = "Scan the label on the item",
            onCode = { raw ->
                val code = raw.trim()
                val d = store.data.value
                val found = d.findByLabel(code)
                    ?: d.findByAnyCode(code)?.takeIf { !it.isBox }
                if (code.isNotBlank()) {
                    itemTrouble = when {
                        found == null -> "Nothing here has the label $code."
                        found.id == id -> "That is this $word's own label."
                        // Another container: it goes inside this one.
                        found.isBox -> store.nest(found.id, id).reason
                        else -> store.putInto(found.id, id).reason
                    }
                    scanItem = false
                }
            },
            onClose = { scanItem = false },
        )
    }

    if (scanOuter) {
        ScanDialog(
            title = "Scan the label on the outer container",
            onCode = { raw ->
                val code = raw.trim()
                val outer = store.data.value.findByLabel(code)
                if (code.isNotBlank()) {
                    nestTrouble = when {
                        outer == null -> "No container has the label $code."
                        !outer.isBox -> "${outer.title} is an item, not a container."
                        else -> store.nest(id, outer.id).reason
                    }
                    scanOuter = false
                }
            },
            onClose = { scanOuter = false },
        )
    }

}

/** The small word above one thing to choose inside a section that holds several. */
@Composable
private fun FoldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
}

/**
 * One half of the line under the ticket: a small word for what is chosen here ("Kind"),
 * the choice itself with its picture ("Box"), and an arrow. Tapping it opens the tiles.
 */
@Composable
private fun PickerPill(caption: String, value: String, icon: ImageVector, open: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    Row(
        modifier.glass(RoundedCornerShape(20.dp), if (open) GlassTone.Mint else GlassTone.Plain, shadow = 4.dp)
            .clickable(role = Role.Button, onClickLabel = if (open) "Close" else "Change", onClick = onToggle)
            .heightIn(min = 52.dp).padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(caption, style = MaterialTheme.typography.labelSmall, color = Night.TextDim, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(20.dp))
    }
}

/**
 * The top of the page: the photo of what's inside, the code to look for on the thing
 * itself with how to mark it, and the room it goes to, which opens to change it.
 */
@Composable
private fun Ticket(
    box: Box,
    dest: Room?,
    handwritten: Boolean,
    clash: Boolean,
    narrow: Boolean,
    destOpen: Boolean,
    onToggleDest: () -> Unit,
    onDest: (String?) -> Unit,
) {
    val luggage = box.kind == ContainerKind.SUITCASE || box.kind == ContainerKind.BAG
    GlassCard(Modifier.fillMaxWidth(), tone = GlassTone.Mint, shape = RoundedCornerShape(26.dp), sheen = true) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ContainerPhoto(box.id, PhotoKind.CONTENTS, "Photo of what's inside", Modifier.size(if (narrow) 88.dp else 104.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    box.label, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (handwritten) Icons.Default.Edit else Pic.Label, contentDescription = null,
                        tint = Night.MintPale, modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            luggage && handwritten -> "Write it on a tag for the handle"
                            luggage -> "Tie its tag to the handle"
                            handwritten -> "Write it on the top and one side"
                            else -> "Stick one on top, one on a side"
                        },
                        style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, maxLines = 2,
                    )
                }
                // Where it goes, as everywhere in the app: an arrow and the room in its colour. Tapping it opens the rooms.
                GoingTo(dest, destOpen, onToggleDest)
            }
        }
        if (clash) {
            Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                IconFlag(Icons.Default.Warning, "This label is used twice. Change one", error = true)
            }
        }
        if (destOpen) {
            RoomTiles(Home.DEST, box.destRoomId, onPick = onDest, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp))
        }
    }
}

/**
 * The books and products scanned into it, as one glass row: the picture of a book, how
 * many, and the first few titles. Tapping it opens the scanner with the whole list.
 */
@Composable
private fun ScannedInRow(entries: List<BoxEntry>, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), shadow = 4.dp)
            .clickable(role = Role.Button, onClickLabel = "Open the list", onClick = onOpen)
            .heightIn(min = 56.dp).padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).glass(RoundedCornerShape(12.dp), shadow = 0.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Scanned in (${entries.size})", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                entries.take(3).joinToString(" · ") { it.title.ifBlank { "Barcode ${it.code}" } } + if (entries.size > 3) " and ${entries.size - 3} more" else "",
                style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim)
    }
}

/** A catalogued item not packed yet, as a picture to tap: one tap puts it in. */
@Composable
private fun LooseTile(item: Item, onPut: () -> Unit) {
    Column(
        Modifier.width(104.dp).glass(RoundedCornerShape(18.dp), shadow = 4.dp)
            .clickable(role = Role.Button, onClickLabel = "Put in", onClick = onPut).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            ItemPhoto(item.id, Modifier.fillMaxWidth().aspectRatio(4f / 3f), RoundedCornerShape(12.dp))
            // On a dark disc, so the plus shows on a bright photograph too.
            Icon(
                Icons.Default.Add, contentDescription = null, tint = Night.Mint,
                modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape)
                    .background(Night.Panel).border(1.dp, Night.GlassRimHi, CircleShape).padding(3.dp),
            )
        }
        Text(
            item.title(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Fixed at the bottom of the page, so sealing never needs scrolling. */
@Composable
private fun SealBar(word: String, sealed: Boolean, missing: List<String>, onSeal: () -> Unit, onOpen: () -> Unit, onNext: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().bottomBar(if (sealed) GlassTone.Mint else GlassTone.Plain)) {
        if (!sealed) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (missing.isNotEmpty()) Hint("Still to add: ${missing.joinToString(", ")}")
                BigAction(Icons.Default.Lock, "Seal $word", onSeal)
            }
        } else {
            val tick = @Composable {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(28.dp))
            }
            val state = @Composable { Text("Sealed", style = MaterialTheme.typography.titleMedium, color = scheme.onTertiaryContainer) }
            val reopen = @Composable { GlassChip("Open again", icon = Icons.Default.LockOpen, onClick = onOpen) }
            val next: @Composable (Modifier) -> Unit = { m ->
                BigAction(Pic.Box, "Pack the next box", onNext, m)
            }
            // Side by side where there is room; stacked on a narrow screen or with large text.
            if (!narrowScreen(360f)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    tick()
                    Spacer(Modifier.width(10.dp))
                    Column {
                        state()
                        reopen()
                    }
                    Spacer(Modifier.width(12.dp))
                    next(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)) {
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

/**
 * Typing what is inside, behind the pencil: one line of words, commas between things. What was typed is saved
 * as lines when the field is left, Done is pressed or the pencil closes it, so the lines another phone adds
 * meanwhile stay, and a half-typed word is never saved.
 */
@Composable
private fun ContentsField(boxId: String, current: String, key: String) {
    val store = LocalStore.current
    val focus = LocalFocusManager.current
    var text by remember(key) { mutableStateOf(current) }
    var focused by remember(key) { mutableStateOf(false) }
    val latest by rememberUpdatedState(text)
    // What the field started from (or was last saved as): only what was typed or taken out since is saved, so a
    // line added meanwhile by a chip, the microphone or another phone stays.
    val base = remember(key) { mutableStateOf(storedLines(current)) }
    fun commit() {
        val typed = storedLines(latest)
        if (typed == base.value) return
        val typedByKey = typed.associateBy { it.lowercase() }
        val removed = base.value.map { it.lowercase() }.filter { it !in typedByKey }.toSet()
        base.value = typed
        // The lines as they are now, less those taken out, with the words as typed; then the new ones.
        val kept = store.data.value.linesOf(boxId).map { it.text }.filter { it.lowercase() !in removed }.map { typedByKey[it.lowercase()] ?: it }
        val keys = kept.map { it.lowercase() }.toSet()
        store.setContents(boxId, (kept + typed.filter { it.lowercase() !in keys }).joinToString("\n"))
    }
    DisposableEffect(key) { onDispose { commit() } }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("What's inside") },
        placeholder = { Text("Plates, mugs, tea towels") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
        colors = glassFieldColors(),
        modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
            if (focused && !f.isFocused) commit()
            focused = f.isFocused
        },
    )
}
