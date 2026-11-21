package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.platform.LocalConfiguration
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

/** "Suitcase 1 (B1)" or "Suitcase B1": a container's name with the code to look for. */
private fun Box.nameAndCode(): String = if (name.isBlank()) "${kind.noun} $label" else "$name ($label)"

/**
 * One container, laid out for packing it without the keyboard. Everything about it is
 * already filled in from the last one packed in the same room, so the first screenful is
 * only things to look at or tap: what kind it is, what kind of things go in it, a photo,
 * what is inside (tap, say or scan) and, fixed at the bottom, Seal. What else can go in
 * it (catalogued items, other containers) and everything rarely changed comes below.
 */
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
    var scanItem by remember { mutableStateOf(false) }
    var scanOuter by remember { mutableStateOf(false) }
    // Why the Store refused the last thing asked about items, and about containers.
    var itemTrouble by remember { mutableStateOf<String?>(null) }
    var nestTrouble by remember { mutableStateOf<String?>(null) }
    var destOpen by rememberSaveable(id) { mutableStateOf(false) }
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
    // Catalogued things from the same room that could go in: not packed yet, and in the same place as this.
    val loose = data.looseItems().filter { it.originRoomId == box.originRoomId && it.stage == box.stage }
    val anyLoose = loose.isNotEmpty() || data.looseItems().any { it.stage == box.stage }
    val lines = contentsLines(box.contents)
    val missing = listOfNotNull(
        "a room to go to".takeIf { dest == null && parent == null },
        "what's inside".takeIf {
            box.contents.isBlank() && entries.isEmpty() && items.isEmpty() && children.isEmpty() &&
                data.photosOf(box.id, PhotoKind.CONTENTS).isEmpty()
        },
    )
    val config = LocalConfiguration.current
    val narrow = config.screenWidthDp / config.fontScale < 360f

    fun setContents(f: (String) -> String) {
        edit { it.copy(contents = f(it.contents)) }
        typingRound++
    }

    Screen(
        box.shownName(),
        actions = {
            TopAction(Icons.Default.Delete, "Delete this $word") { confirmDelete = true }
        },
    ) {
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
            if (parent != null) {
                GlassPill(Modifier.fillMaxWidth(), tone = GlassTone.Amber, contentPadding = PaddingValues(start = 16.dp, end = 4.dp)) {
                    Icon(Icons.Default.SubdirectoryArrowRight, contentDescription = null, tint = Night.AmberPale, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Inside ${parent.nameAndCode()}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable { nav.open("box/${parent.id}") }.padding(vertical = 12.dp),
                    )
                    TextButton(onClick = { nestTrouble = store.nest(id, null).reason }) { Text("Take out") }
                }
                nestTrouble?.let { ContainerRefusal(it) { nestTrouble = null } }
            }

            // ---- what it is ----
            ContainerTiles(
                ContainerKind.entries.map { Choice(it, it.tileLabel(), it.picture()) }, box.kind, columns = 3,
                onPick = { k -> edit { store.data.value.withKind(it, k) } },
            )

            // ---- what's inside: the kind of things, then the things ----
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ContainerHeading("What's inside", Modifier.weight(1f))
                    SpeakButton("What is in the $word?", contentDescription = "Say what's inside", size = 40.dp) { said ->
                        setContents { addContents(it, *contentsLines(said).map { l -> l.replaceFirstChar(Char::uppercase) }.toTypedArray()) }
                    }
                    GlassOrb(Icons.Default.QrCodeScanner, "Scan barcodes of books and products", size = 40.dp) { scanIn = true }
                    GlassOrb(Icons.Default.Edit, "Edit what's inside", kind = if (typing) OrbKind.Selected else OrbKind.Plain, size = 40.dp) { typing = !typing }
                }
                ContainerTiles(
                    BOX_CATEGORIES.map { Choice(it, it, categoryPicture(it)) }, category, columns = if (narrow) 3 else 4,
                    onPick = { c -> edit { it.copy(category = c) } },
                )
                // What is in it already, ticked; then the usual things for this category, one tap each.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { line -> GlassChip(line, selected = true, onClick = { setContents { removeContents(it, line) } }) }
                    contentsChips(category, data.room(box.originRoomId)?.name, box.contents).forEach { chip ->
                        GlassChip(chip, onClick = { setContents { addContents(it, chip) } })
                    }
                }
                if (typing) {
                    TextInput(
                        box.contents,
                        onChange = { v -> edit { it.copy(contents = v) } },
                        label = "What's inside",
                        key = "$id:$typingRound",
                        singleLine = false,
                    )
                }
                if (entries.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { scanIn = true }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Scanned in (${entries.size})", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        entries.take(3).forEach { e ->
                            Text(
                                e.title.ifBlank { "Barcode ${e.code}" } + if (e.author.isBlank()) "" else " · ${e.author}",
                                style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (entries.size > 3) Text("and ${entries.size - 3} more", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // ---- catalogued items packed in it ----
            if (items.isNotEmpty() || anyLoose) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ContainerHeading(if (items.isEmpty()) "Items inside" else "Items inside (${items.size})", Modifier.weight(1f))
                        GlassOrb(Icons.Default.QrCodeScanner, "Scan an item's label to put it in", size = 40.dp) { scanItem = true }
                    }
                    items.forEach { i ->
                        ContainerItemRow(i, onOpen = { nav.open("item/${i.id}") }) {
                            IconButton(onClick = { itemTrouble = store.takeOut(i.id).reason }) {
                                Icon(Icons.Default.Close, contentDescription = "Take ${i.title()} out")
                            }
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
                    ContainerHeading("Inside this $word (${children.size})")
                    children.forEach { c ->
                        ContainerRow(
                            c, data, showRoom = false, onOpen = { nav.open("box/${it.id}") },
                            trailing = {
                                IconButton(onClick = { nestTrouble = store.nest(c.id, null).reason }) {
                                    Icon(Icons.Default.Close, contentDescription = "Take ${c.shownName()} out")
                                }
                            },
                        )
                    }
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
                ContainerHeading("Unpack")
                IconChoice(
                    Priority.entries.map { Choice(it, it.label, it.icon()) },
                    box.priority,
                    onPick = { p -> edit { it.copy(priority = p) } },
                )
            }

            // ---- needed now and then: closed, each saying what is set ----
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ContainerHeading("More about this $word")
                if (parent == null && children.isEmpty()) {
                    GlassPill(Modifier.fillMaxWidth(), onClick = { scanOuter = true }) {
                        Icon(Icons.Default.MoveToInbox, contentDescription = null, tint = Night.Mint)
                        Spacer(Modifier.width(14.dp))
                        Text("Put inside another container", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(20.dp))
                    }
                }
                if (parent == null) nestTrouble?.let { ContainerRefusal(it) { nestTrouble = null } }
                Fold("Coming from", Pic.Room, summary = data.room(box.originRoomId)?.name ?: "No room") {
                    RoomTiles(Home.ORIGIN, box.originRoomId, onPick = { r -> edit { it.copy(originRoomId = r) } })
                }
                Fold(if (box.kind == ContainerKind.CARTON) "Box size" else "Size", Pic.Size, summary = box.size.label, key = "size") {
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
                val photos = data.photosOf(box.id, PhotoKind.CONTENTS).size
                Fold("Photos", Pic.Camera, summary = if (photos == 0) "None yet" else containerCount(photos, "photo")) {
                    PhotoStrip(box.id, PhotoKind.CONTENTS, label = "What's inside")
                }
                Fold(
                    "Name and notes", Pic.Notes,
                    summary = listOfNotNull(
                        box.name.ifBlank { null },
                        box.notes.lineSequence().firstOrNull { it.isNotBlank() },
                        box.packedBy.ifBlank { null }?.let { "Packed by $it" },
                    ).joinToString(" · ").ifEmpty { "None" },
                ) {
                    TextInput(
                        box.name,
                        onChange = { v -> edit { it.copy(name = v) } },
                        label = "Name",
                        key = "$id:name:${box.kind}",
                        placeholder = "${box.kind.noun} ${box.label}",
                    )
                    TextInput(
                        box.notes,
                        onChange = { v -> edit { it.copy(notes = v) } },
                        label = "Notes",
                        key = box.id + ":notes",
                        singleLine = false,
                        mic = true,
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
                    ?: d.liveItems.firstOrNull { it.serial.isNotBlank() && it.serial.equals(code, ignoreCase = true) }?.toMovable()
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

    if (confirmDelete) {
        val kept = items.isNotEmpty() || children.isNotEmpty()
        ConfirmDialog(
            title = "Delete ${box.nameAndCode()}?",
            text = "This record and its photos will be removed from every phone at the next sync." +
                if (kept) " What is packed in it stays in the app." else "",
            onConfirm = {
                data.photosOf(box.id).forEach { store.delete(it) }
                store.delete(box)
                nav.popBackStack()
            },
            onDismiss = { confirmDelete = false },
        )
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
                Row(
                    Modifier.padding(top = 2.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClickLabel = if (destOpen) "Close the rooms" else "Choose another room", onClick = onToggleDest)
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Going to", tint = Night.TextSoft, modifier = Modifier.size(18.dp))
                    RoomBadge(dest, Modifier.weight(1f, fill = false))
                    Icon(if (destOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(20.dp))
                }
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

/** A catalogued item not packed yet, as a picture to tap: one tap puts it in. */
@Composable
private fun LooseTile(item: Item, onPut: () -> Unit) {
    Column(
        Modifier.width(104.dp).glass(RoundedCornerShape(18.dp), shadow = 4.dp)
            .clickable(role = Role.Button, onClickLabel = "Put in", onClick = onPut).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            OwnerThumb(item.id, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
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
