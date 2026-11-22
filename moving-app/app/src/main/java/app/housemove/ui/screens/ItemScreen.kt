package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import kotlin.math.roundToLong

/** The decisions offered as tiles. Tapping the chosen one again takes the decision back. */
private val DECISIONS = listOf(Disposition.MOVE, Disposition.SELL, Disposition.DONATE, Disposition.BIN, Disposition.STORE)

private val ACCESSORY_CHIPS = listOf("Remote", "Power lead", "Cables", "Stand", "Screws", "Shelves", "Manual")
private val WORTH_PRESETS = listOf(50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0)

/**
 * Everything about one catalogued item, in place and without the keyboard. Always showing:
 * the photo, what happens to it, where it goes (one line that opens the rooms) and what
 * it is packed in. The rest waits in a few sections that open when tapped, each saying in
 * one line what is already set: device details, size and handling, getting it ready, how
 * it fares on the move, and a last one for notes and what is rarely wanted.
 *
 * What happens to it depends on who is holding the phone. An Organiser decides with the
 * picture tiles and finds other people's suggestions here with Approve and Decline. A
 * Decider's same tiles make a suggestion, which then shows as waiting. A Helper sees how
 * things stand and can say that it needs a decision.
 */
@Composable
fun ItemScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    deviceInfo() // whose phone this is decides what is offered below
    val item = data.item(id)

    if (item == null) {
        Screen("Item") {
            EmptyState(Pic.Item, "This item is no longer here", text = "It may have been deleted on this phone or another one.")
        }
        return
    }

    val me = store.me
    val mayDecide = store.can(Action.DECIDE)
    val maySuggest = store.can(Action.SUGGEST)
    val mayCarryOut = data.mayCarryOut(me, item)
    val waitingDecision = data.waitingFor(id, ProposalKind.ITEM_DECISION, ProposalKind.QUESTION)
    val waitingRoom = data.waitingFor(id, ProposalKind.ITEM_ROOM)

    var scanning by remember { mutableStateOf(false) }
    var scanningSerial by remember { mutableStateOf(false) }
    var scanningContainer by remember { mutableStateOf(false) }
    var scanningAccessoryBox by remember { mutableStateOf(false) }
    // A label that was scanned for "Put in a container" and belongs to nothing yet.
    var newContainer by remember { mutableStateOf<String?>(null) }
    // Bumped when a scan fills the serial number in, so the text field picks the new value up.
    var serialScans by remember { mutableIntStateOf(0) }
    // Set when "It has a serial number" is switched on here, so its section appears already open.
    var deviceJustOn by rememberSaveable { mutableStateOf(false) }
    var editingDevice by rememberSaveable { mutableStateOf(false) }
    var naming by rememberSaveable { mutableStateOf(false) }
    // Bumped when the size is said, so the three fields pick the new numbers up.
    var sizeSaid by remember { mutableIntStateOf(0) }
    var choosingRoom by rememberSaveable(id) { mutableStateOf(false) }
    // A Decider choosing Sell names an asking price before the suggestion is sent.
    var pricing by rememberSaveable { mutableStateOf(false) }
    var askingDraft by remember(id, item.price) { mutableStateOf(item.price) }

    // Always edit the newest copy in the store, so two quick changes never overwrite each other.
    fun edit(f: (Item) -> Item) {
        store.data.value.item(id)?.let { store.put(f(it)) }
    }

    // Make, model, serial number, dates and value are on the item's hidden record (Store.setDetails).
    fun details(f: (Hidden) -> Hidden) {
        store.setDetails(id, f).okOrSay(context)
    }

    val moving = item.disposition == Disposition.MOVE
    val leaving = item.disposition == Disposition.SELL || item.disposition == Disposition.DONATE || item.disposition == Disposition.BIN
    val origin = data.room(item.originRoomId)
    val dest = data.room(item.destRoomId)
    val container = data.containerOf(item)
    val currency = data.move.currency.trim()
    val vehicle = data.vehicle()
    val inches = data.move.useInches
    // Device details are offered when that part of the app is in use, or something is already recorded.
    val hasDeviceDetails = item.electronic == true || item.make.isNotBlank() || item.model.isNotBlank() || item.serial.isNotBlank()
    val offerDevice = data.uses(Part.DEVICES) || hasDeviceDetails
    val electronic = offerDevice && item.isElectronic()
    // Is a move being run with the app, or is it only a list of belongings? Sections about the
    // journey are left out for a plain list, unless something is already recorded in them.
    val movesHouse = data.uses(Part.PLAN) || data.uses(Part.BOXES) || data.uses(Part.MOVE_DAY) || data.uses(Part.UNPACK)
    // On a small screen or with large text, tiles go fewer to a row so their words still fit.
    val narrow = narrowScreen()

    Screen(
        title = item.title(),
        actions = {
            TopAction(if (naming) Icons.Default.Check else Icons.Default.Edit, if (naming) "Done editing the name" else "Edit the name") { naming = !naming }
        },
    ) {
        ScrollColumn {
            PhotoHero(item.id, PhotoKind.GENERAL, label = item.title(), height = 190.dp)

            // The name is in the bar above. Until it has a real one, one tap or a spoken word gives it one;
            // the pencil up there opens a field for typing it.
            if (naming || item.hasPlaceholderName()) ItemNamer(item, showName = false, typing = naming)

            // ---- what happens to it ------------------------------------------------------

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (maySuggest) {
                    Text(if (mayDecide) "What happens to it?" else "Suggest what happens to it", style = MaterialTheme.typography.titleMedium)
                } else {
                    // A Helper does not choose. They see how it stands and can say it needs deciding.
                    val (state, tone) = data.itemState(item)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("What happens to it", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        GlassChip(state, icon = item.disposition.icon(), tone = tone)
                    }
                }
                // What others suggested or asked sits here, on the item: an Organiser settles it, the rest see it wait.
                // A request to delete it is here too, where it is seen.
                DeleteRequests(id)
                waitingDecision.forEach { p -> Suggestion(p, data) }
                if (!mayDecide && !item.gone && waitingDecision.none { it.by == me?.id }) MyLastAnswer(id, data)

                if (maySuggest) {
                    IconChoice(
                        options = DECISIONS.map { Choice(it, it.label, it.icon()) },
                        selected = if (pricing && !mayDecide) Disposition.SELL else item.disposition,
                        onPick = { d ->
                            when {
                                mayDecide -> store.decideOrSuggest(context, id, if (item.disposition == d) Disposition.UNDECIDED else d)
                                d == Disposition.SELL -> pricing = true
                                else -> {
                                    pricing = false
                                    store.decideOrSuggest(context, id, d)
                                }
                            }
                        },
                        columns = if (narrow) 3 else 5,
                    )
                    if (pricing && !mayDecide) {
                        Section {
                            AmountPicker(askingDraft, { askingDraft = it }, "Asking price", currency)
                            BigAction(
                                Icons.Default.Sell, "Suggest selling" + (askingDraft?.let { " for " + money(currency, it) } ?: ""),
                                onClick = { if (store.decideOrSuggest(context, id, Disposition.SELL, askingDraft)) pricing = false },
                            )
                        }
                    }
                } else if (waitingDecision.none { it.by == me?.id }) {
                    // One tap asks the Organisers; the microphone asks with a few words of why.
                    AskForDecision(id, primary = item.disposition == Disposition.UNDECIDED)
                }

                // Carrying the decision out, for whoever may: listed, gone, and what it went for.
                when (item.disposition) {
                    Disposition.SELL -> {
                        if (mayCarryOut) {
                            ToggleTiles(
                                listOf(
                                    Toggle("Listed for sale", Icons.Default.Campaign, item.listed) { v -> store.carryOut(id, listed = v).okOrSay(context) },
                                    Toggle("Sold and gone", goneIcon(item.gone), item.gone) { v ->
                                        // What it went for starts at the asking price: right most of the time, one tap to change.
                                        store.carryOut(id, gone = v, soldFor = if (v) item.soldFor ?: item.price else null).okOrSay(context)
                                    },
                                )
                            )
                        }
                        when {
                            item.gone && mayCarryOut -> AmountPicker(
                                item.soldFor, { v -> if (v != null) store.carryOut(id, soldFor = v).okOrSay(context) }, "Sold for", currency,
                                presets = soldPresets(item.price),
                            )
                            mayDecide && !item.gone -> AmountPicker(item.price, { v -> store.decide(id, Disposition.SELL, v).okOrSay(context) }, "Asking price", currency)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!mayDecide || item.gone) GlassChip(item.price?.let { "Asking " + money(currency, it) } ?: "No price yet", icon = Icons.Default.Sell)
                            if (!mayCarryOut && item.listed && !item.gone) GlassChip("Listed", icon = Icons.Default.Campaign)
                            if (!mayCarryOut && item.gone) GlassChip("Sold" + (item.soldFor?.let { " for " + money(currency, it) } ?: ""), icon = Icons.Default.Check, tone = GlassTone.Mint)
                            if (mayDecide && !item.gone) DateChip(item.deadlineDay, "Sell by") { d -> edit { it.copy(deadlineDay = d) } }
                        }
                    }
                    Disposition.DONATE, Disposition.BIN -> {
                        if (mayCarryOut) {
                            ToggleTiles(listOf(Toggle("Gone", goneIcon(item.gone), item.gone) { v -> store.carryOut(id, gone = v).okOrSay(context) }))
                        } else if (item.gone) GlassChip(data.itemState(item).first, icon = Icons.Default.Check, tone = GlassTone.Mint)
                        if (mayDecide && !item.gone) {
                            DateChip(item.deadlineDay, if (item.disposition == Disposition.DONATE) "Donate by" else "Gone by") { d -> edit { it.copy(deadlineDay = d) } }
                        }
                    }
                    Disposition.STORE -> Hint("Going into storage, so it is not counted in the $vehicle load.")
                    Disposition.MOVE, Disposition.UNDECIDED -> Unit
                }
                // Why a Decider finds no "sold" or "gone" here yet.
                if (leaving && !item.gone && !mayCarryOut && maySuggest) {
                    Hint("You can mark it ${if (item.disposition == Disposition.SELL) "sold" else "gone"} once an Organiser approves a suggestion of yours for it.")
                }
            }

            // ---- where it goes: one line, which opens the rooms ---------------------------

            if (moving && (movesHouse || dest != null)) {
                // With no room yet the rooms are open already, unless it is packed: then it goes where its container goes.
                val roomsOpen = maySuggest && (choosingRoom || (dest == null && container == null))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (maySuggest && !mayDecide) "Suggest where it goes" else "Where it goes",
                            style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                        )
                        if (maySuggest) GoingTo(dest, roomsOpen, onToggle = { choosingRoom = !roomsOpen })
                        else RoomBadge(dest)
                    }
                    waitingRoom.forEach { p -> Suggestion(p, data) }
                    if (roomsOpen) {
                        RoomTiles(
                            Home.DEST, item.destRoomId, allowNone = mayDecide,
                            onPick = { rid ->
                                if ((mayDecide || rid != item.destRoomId) && store.planOrSuggestRoom(context, id, rid)) choosingRoom = false
                            },
                        )
                    }
                    if (data.uses(Part.PLAN)) {
                        FitLine(
                            item, dest, data.fitOf(item), inches,
                            onTakeApart = { edit { it.copy(needsDisassembly = true) } },
                            onAddDoor = { nav.open("rooms") },
                        )
                    }
                }
            }

            // ---- what it is packed in -----------------------------------------------------

            // A wardrobe or a fridge goes in no container: what plainly cannot be packed is not offered it.
            if (container != null || (data.uses(Part.BOXES) && !leaving && item.couldBePacked())) {
                ContainerBlock(
                    item, container, data,
                    onScan = { scanningContainer = true },
                    onOpen = { nav.open("box/${it.id}") },
                )
            }

            // ---- everything else, closed until wanted ----------------------------------

            if (electronic) {
                Fold(
                    "Make, model and serial number", Pic.Device,
                    summary = listOf(
                        item.makeModel(), if (item.serial.isBlank()) "" else "S/N ${item.serial}",
                        item.accessories.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                        .ifBlank { if (data.photosOf(id, PhotoKind.SERIAL).isNotEmpty()) "Serial label photographed" else "Nothing recorded yet" },
                    open = deviceJustOn,
                    key = "device-$deviceJustOn",
                ) {
                    // The serial number is scanned off the label; make and model are said, or typed behind the pencil.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.makeModel().ifBlank { "Make and model not recorded" }, style = MaterialTheme.typography.bodyLarge, color = if (item.makeModel().isBlank()) Night.TextDim else Night.Text)
                            Text(
                                if (item.serial.isBlank()) "No serial number yet" else "S/N ${item.serial}",
                                style = MaterialTheme.typography.titleMedium, color = if (item.serial.isBlank()) Night.AmberPale else Night.Text,
                            )
                        }
                        SpeakButton("Make and model?", contentDescription = "Say the make and model", size = 40.dp) { said ->
                            val words = said.trim().split(Regex("\\s+"), limit = 2)
                            details { it.copy(make = words[0].replaceFirstChar { c -> c.uppercase() }, model = words.getOrElse(1) { "" }) }
                        }
                        GlassOrb(
                            if (editingDevice) Icons.Default.Check else Icons.Default.Edit,
                            if (editingDevice) "Done editing make, model and serial number" else "Edit make, model and serial number",
                            size = 40.dp, onClick = { editingDevice = !editingDevice },
                        )
                    }
                    if (editingDevice) {
                        TextInput(item.make, { v -> details { it.copy(make = v) } }, "Make", key = "$id-make")
                        TextInput(item.model, { v -> details { it.copy(model = v) } }, "Model", key = "$id-model")
                        TextInput(item.serial, { v -> details { it.copy(serial = v.trim()) } }, "Serial number", key = "$id-serial-$serialScans")
                    }
                    BigAction(Icons.Default.QrCodeScanner, "Scan the serial number barcode", onClick = { scanningSerial = true }, primary = item.serial.isBlank())
                    PhotoStrip(item.id, PhotoKind.SERIAL, label = "Photo of the serial label")
                    PhotoStrip(item.id, PhotoKind.RECEIPT, label = "Receipt or warranty card")
                    DateButton(item.boughtDay, { d -> details { it.copy(boughtDay = d) } }, "Bought", Modifier.fillMaxWidth())
                    DateButton(item.warrantyDay, { d -> details { it.copy(warrantyDay = d) } }, "Warranty until", Modifier.fillMaxWidth())

                    // What travels with it: the remote, the leads, the stand.
                    HorizontalDivider(color = Night.Hairline)
                    QuickText(
                        item.accessories, { v -> edit { it.copy(accessories = v) } }, label = "What travels with it", prompt = "What travels with it?",
                        key = "$id-acc", chips = ACCESSORY_CHIPS, empty = "Nothing listed",
                    )
                    CheckRow("Bagged and marked with this item's name", item.accessoriesPacked, { v -> edit { it.copy(accessoriesPacked = v) } })
                    val accBox = data.findByLabel(item.accessoryBox)
                    if (item.accessoryBox.isBlank()) {
                        BigAction(Icons.Default.QrCodeScanner, "Scan the box they are in", onClick = { scanningAccessoryBox = true }, primary = false)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("In " + (accBox?.title ?: "${item.accessoryBox} (no box has this label yet)"), modifier = Modifier.weight(1f))
                            if (accBox != null) RowButton("Open", onClick = { nav.openMovable(accBox) })
                            RowButton("Clear", onClick = { edit { it.copy(accessoryBox = "") } }, forward = false)
                        }
                    }
                    HorizontalDivider(color = Night.Hairline)
                    SwitchRow("It has a serial number", true, { v -> deviceJustOn = false; edit { it.copy(electronic = v) } })
                }
            }

            Fold(
                "Size and handling", Pic.Size,
                summary = listOfNotNull(
                    sizeText(item, inches),
                    "Fragile".takeIf { item.fragile },
                    "Two people".takeIf { item.twoPerson },
                    "Keep upright".takeIf { item.keepUpright },
                    "Take apart".takeIf { item.needsDisassembly },
                    "Travels with me".takeIf { item.travelsWithMe },
                    "First night".takeIf { item.firstNight },
                    "Hazardous".takeIf { item.hazardous },
                    item.value?.let { "Worth " + money(currency, it) },
                ).joinToString(" · ").ifBlank { "No size yet" },
            ) {
                val unit = if (inches) "in" else "cm"
                fun shown(cm: Double?): Double? = cm?.let { if (inches) (it / 2.54 * 100).roundToLong() / 100.0 else it }
                fun toCm(v: Double?): Double? = v?.takeIf { it > 0 }?.let { if (inches) it * 2.54 else it }
                // All three can be said at once ("200 by 90 by 85"), so measuring needs no keyboard either.
                if (canSpeak()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Width, depth and height in $unit", style = MaterialTheme.typography.labelLarge, color = Night.TextDim, modifier = Modifier.weight(1f))
                    SpeakButton("Width, depth and height, in $unit", contentDescription = "Say its size", size = 40.dp) { said ->
                        val n = spokenNumbers(said)
                        if (n.isNotEmpty()) {
                            edit {
                                it.copy(
                                    widthCm = toCm(n.getOrNull(0)) ?: it.widthCm,
                                    depthCm = toCm(n.getOrNull(1)) ?: it.depthCm,
                                    heightCm = toCm(n.getOrNull(2)) ?: it.heightCm,
                                )
                            }
                            sizeSaid++
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberInput(shown(item.widthCm), { v -> edit { it.copy(widthCm = toCm(v)) } }, "Width $unit", Modifier.weight(1f), key = "$id-w-$unit-$sizeSaid")
                    NumberInput(shown(item.depthCm), { v -> edit { it.copy(depthCm = toCm(v)) } }, "Depth $unit", Modifier.weight(1f), key = "$id-d-$unit-$sizeSaid")
                    NumberInput(shown(item.heightCm), { v -> edit { it.copy(heightCm = toCm(v)) } }, "Height $unit", Modifier.weight(1f), key = "$id-h-$unit-$sizeSaid")
                }
                val handling = listOf(
                    Toggle("Fragile", Pic.Fragile, item.fragile) { v -> edit { it.copy(fragile = v) } },
                    Toggle("Two people", Pic.TwoPeople, item.twoPerson) { v -> edit { it.copy(twoPerson = v) } },
                    Toggle("Keep upright", Pic.Upright, item.keepUpright) { v -> edit { it.copy(keepUpright = v) } },
                    Toggle("Take apart", Pic.TakeApart, item.needsDisassembly) { v -> edit { it.copy(needsDisassembly = v) } },
                )
                // What only matters on the journey is offered when a journey is being run, or is already set.
                val journey = listOf(
                    Toggle("Travels with me", Pic.WithMe, item.travelsWithMe) { v -> edit { it.copy(travelsWithMe = v) } },
                    Toggle("First night", Pic.FirstNight, item.firstNight) { v -> edit { it.copy(firstNight = v) } },
                    Toggle("Hazardous", Pic.Hazard, item.hazardous) { v -> edit { it.copy(hazardous = v) } },
                ).filter { (moving && movesHouse) || it.on }
                ToggleTiles(handling + journey, columns = 2)
                if (item.hazardous) Hint("Gas bottles, paint and fuel cannot go in the $vehicle.")
                AmountPicker(item.value, { v -> details { it.copy(value = v) } }, "What it is worth", currency, presets = WORTH_PRESETS)
            }

            // Where it is now and any damage. Until it leaves the old home this is rarely wanted, so it
            // waits at the end of "More" instead of being a section of its own.
            val trip = data.trip(item.tripId)?.takeIf { item.stage != Stage.AT_ORIGIN }
            val travelled = item.stage != Stage.AT_ORIGIN || item.damaged
            val onTheMove: @Composable ColumnScope.() -> Unit = {
                Text("Where it is now", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                IconChoice(
                    options = Stage.entries.map { Choice(it, stageText(it, isBox = false, vehicle), it.icon()) },
                    selected = item.stage,
                    onPick = { s -> edit { it.copy(stage = s) } },
                    columns = 2,
                )
                if (trip != null) Hint("Travelled on ${trip.name}")
                if (item.needsDisassembly && travelled) {
                    CheckRow("Put back together", item.reassembled, { v -> edit { it.copy(reassembled = v) } })
                }
                HorizontalDivider(color = Night.Hairline)
                SwitchRow("Damaged in the move", item.damaged, { v -> edit { it.copy(damaged = v) } })
                if (item.damaged) {
                    DamageText(item.damageNote, { v -> edit { it.copy(damageNote = v) } }, key = "$id-damage")
                    PhotoStrip(item.id, PhotoKind.DAMAGE, label = "Damage photos")
                }
            }

            if (moving) {
                val steps = listOfNotNull(
                    item.prepEmptied, item.prepPhotos, item.prepDisassembled.takeIf { item.needsDisassembly },
                    item.prepHardware, item.prepCables, item.prepWrapped,
                    item.prepCablesLabelled.takeIf { electronic }, item.prepSignedOut.takeIf { electronic },
                )
                // Getting it ready includes labelling it, so its label lives here too.
                val offerLabel = movesHouse || data.uses(Part.LABELS) || item.label != null
                if (movesHouse || steps.any { it } || offerLabel) Fold(
                    "Getting it ready", Icons.Default.Checklist,
                    summary = "${steps.count { it }} of ${steps.size} done" + if (offerLabel) " · " + (item.label ?: "no label yet") else "",
                ) {
                    // Advice for this kind of device comes first: it says what "ready" means for it.
                    if (electronic) handlingTips(item.name).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft) }
                    CheckRow("Emptied", item.prepEmptied, { v -> edit { it.copy(prepEmptied = v) } })
                    CheckRow("Condition photos taken", item.prepPhotos, { v -> edit { it.copy(prepPhotos = v) } })
                    PhotoStrip(item.id, PhotoKind.CONDITION, label = "Condition before the move")
                    if (item.needsDisassembly) {
                        CheckRow("Taken apart", item.prepDisassembled, { v -> edit { it.copy(prepDisassembled = v) } })
                        PhotoStrip(item.id, PhotoKind.DISASSEMBLY, label = "Taking-apart steps")
                    }
                    CheckRow("Screws and fittings bagged", item.prepHardware, { v -> edit { it.copy(prepHardware = v) } })
                    CheckRow("Cables photographed", item.prepCables, { v -> edit { it.copy(prepCables = v) } })
                    PhotoStrip(item.id, PhotoKind.CABLES, label = "Cables and connections")
                    if (electronic) {
                        CheckRow("Cables labelled and bagged", item.prepCablesLabelled, { v -> edit { it.copy(prepCablesLabelled = v) } })
                        CheckRow("Backed up and ready to unplug", item.prepSignedOut, { v -> edit { it.copy(prepSignedOut = v) } })
                    }
                    CheckRow("Wrapped and protected", item.prepWrapped, { v -> edit { it.copy(prepWrapped = v) } })
                    if (offerLabel) {
                        HorizontalDivider(color = Night.Hairline)
                        val label = item.label
                        if (label != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Its label", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                                    Text(label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                RowButton("Remove label", onClick = { edit { it.copy(label = null) } }, forward = false)
                            }
                        } else {
                            BigAction(Icons.Default.QrCodeScanner, "Scan a printed label", onClick = { scanning = true }, primary = false)
                            BigAction(
                                Icons.Default.Edit, "Use a handwritten code", primary = false,
                                onClick = {
                                    val code = store.nextHandCode()
                                    edit { it.copy(label = code) }
                                    toast(context, "Write $code on the item with a marker.")
                                },
                            )
                        }
                    }
                }

                // Once it has left the old home, or was damaged, how it fares is a section of its own.
                // Opened when it first appears because damage was just reported, so what happened can be added at once.
                if (travelled) Fold(
                    "On the move", Pic.Where, open = item.damaged, key = "move",
                    summary = data.statusText(item.toMovable()) + (trip?.let { " · ${it.name}" } ?: "") +
                        if (item.damaged) " · " + item.damageNote.ifBlank { "Damaged" } else "",
                ) { onTheMove() }
            }

            // Notes, and what is rarely wanted: the room it came from, whether it is a device, deleting it.
            Fold(
                "More", Pic.Notes,
                summary = listOfNotNull(
                    "From ${origin?.name ?: "no room"}",
                    item.notes.lineSequence().firstOrNull { it.isNotBlank() },
                ).joinToString(" · "),
                key = "more",
            ) {
                QuickText(
                    item.notes, { v -> edit { it.copy(notes = v) } }, label = "Notes", prompt = "Anything to note about it?",
                    key = "$id-notes", empty = "No notes",
                )
                Text("Room in the old home", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                RoomTiles(Home.ORIGIN, item.originRoomId, onPick = { rid ->
                    edit { cur ->
                        val mapped = store.data.value.room(rid)?.mapsTo
                        cur.copy(
                            originRoomId = rid,
                            destRoomId = cur.destRoomId ?: if (cur.disposition == Disposition.MOVE) mapped else null,
                        )
                    }
                })
                if (offerDevice && !electronic) {
                    SwitchRow("It has a serial number", false, { v -> deviceJustOn = v; edit { it.copy(electronic = v) } }, sub = "Adds make, model and serial number")
                }
                if (moving && movesHouse && !travelled) {
                    HorizontalDivider(color = Night.Hairline)
                    onTheMove()
                }
                // Not undone, so it is the last thing in the last section: deleting it, or asking an Organiser to.
                if (deleteOffered(item)) HorizontalDivider(color = Night.Hairline)
                DeleteOrAsk(
                    item, "Delete item", "Delete this item?",
                    "\"${item.title()}\" and your photos of it will be removed from every phone at the next sync.",
                    Modifier.align(Alignment.CenterHorizontally),
                    // The Store deletes the photos with it, the ones this person may delete, only once it may delete the item.
                    onDelete = { store.tryDelete(item).okOrSay(context) },
                    onDeleted = { nav.popBackStack() },
                )
            }
        }
    }

    if (scanning) {
        ScanDialog(
            title = "Scan the label for this item",
            onCode = { code ->
                val current = store.data.value
                val taken = current.findByLabel(code)
                when {
                    code.isBlank() -> Unit
                    code.startsWith("HMSYNC", ignoreCase = true) -> toast(context, "That is a sync code, not a label.")
                    taken != null && taken.id != id -> toast(context, "Label $code is already on ${taken.title}. Use a different label.")
                    else -> {
                        edit { it.copy(label = code) }
                        scanning = false
                    }
                }
            },
            onClose = { scanning = false },
        )
    }

    if (scanningSerial) {
        ScanDialog(
            title = "Scan the serial number barcode",
            raw = true,
            formats = ScanFormats.SERIALS,
            typeLabel = "Or type the serial number",
            onCode = { text ->
                val serial = text.trim()
                if (serial.isNotEmpty()) {
                    details { it.copy(serial = serial) }
                    serialScans++
                    toast(context, "Serial number: $serial. Check it against the label.")
                    scanningSerial = false
                }
            },
            onClose = { scanningSerial = false },
        )
    }

    if (scanningContainer) {
        ScanDialog(
            title = "Scan the container's label",
            onCode = { code ->
                val hit = store.data.value.findByLabel(code)
                when {
                    code.isBlank() -> Unit
                    code.startsWith("HMSYNC", ignoreCase = true) -> toast(context, "That is a sync code, not a label.")
                    hit == null -> {
                        newContainer = code
                        scanningContainer = false
                    }
                    !hit.isBox -> toast(context, "Label $code is on ${hit.title}, which is not a container.")
                    else -> if (store.putInto(id, hit.id).okOrSay(context)) scanningContainer = false
                }
            },
            onClose = { scanningContainer = false },
        )
    }

    newContainer?.let { code ->
        ConfirmDialog(
            title = "New container $code?",
            text = "Nothing has the label $code yet. Start a new container with it and put ${item.title()} in it?",
            confirm = "Start it",
            danger = false,
            onConfirm = {
                val box = store.createContainer(code, item.originRoomId)
                store.putInto(id, box.id).okOrSay(context)
            },
            onDismiss = { newContainer = null },
        )
    }

    if (scanningAccessoryBox) {
        ScanDialog(
            title = "Scan the box the accessories are in",
            onCode = { code ->
                if (code.isNotBlank()) {
                    edit { it.copy(accessoryBox = code.trim()) }
                    scanningAccessoryBox = false
                }
            },
            onClose = { scanningAccessoryBox = false },
        )
    }


}

/** The numbers in what was said, in order: "200 by 90 by 85" gives 200, 90 and 85. A decimal comma counts as a point. */
internal fun spokenNumbers(said: String): List<Double> =
    Regex("\\d+(?:[.,]\\d+)?").findAll(said).map { it.value.replace(',', '.').toDouble() }.toList()

/** An empty circle until it has gone, then a tick: the same picture as on the Sell, donate, bin list. */
private fun goneIcon(gone: Boolean) = if (gone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked

/** Likely amounts for what something sold for: the asking price and a few steps below it. */
internal fun soldPresets(asking: Double?): List<Double> =
    if (asking != null && asking >= 10) listOf(1.0, 0.9, 0.8, 0.75, 0.5).map { Math.round(asking * it).toDouble() } else PRICE_PRESETS

/** "120 × 74 × 205 cm", with a ? for a side not measured yet. Null when nothing is measured. */
private fun sizeText(item: Item, inches: Boolean): String? {
    val sides = listOf(item.widthCm, item.depthCm, item.heightCm)
    if (sides.all { it == null }) return null
    return sides.joinToString(" × ") { cm -> cm?.let { fmtNum(if (inches) it / 2.54 else it) } ?: "?" } + if (inches) " in" else " cm"
}

/**
 * What the item is packed in. Loose, it offers "Put in a container": scan the container's
 * label, or tap one of the open containers from the same room. Packed, it says which
 * container it is inside and where that is now, with "Take out".
 */
@Composable
private fun ContainerBlock(item: Item, container: Box?, data: AppData, onScan: () -> Unit, onOpen: (Box) -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    var choosing by rememberSaveable(item.id) { mutableStateOf(false) }
    // Once it is packed, by a tap or by a scan, the choice is made: taking it out again starts afresh.
    LaunchedEffect(container?.id) { if (container != null) choosing = false }
    if (container != null) {
        // "Take out" sits beside where it is, or under it on a small phone, so the container's name is not cut short.
        val stacked = narrowScreen(360f)
        GlassCard(Modifier.fillMaxWidth(), tone = GlassTone.Mint, shape = RoundedCornerShape(22.dp)) {
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    (if (stacked) Modifier.fillMaxWidth() else Modifier.weight(1f)).clip(RoundedCornerShape(16.dp)).clickable { onOpen(container) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContainerThumb(container, data, 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Inside " + container.nameAndCode(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            data.whereIs(container.toMovable()), style = MaterialTheme.typography.bodySmall, color = Night.TextSoft,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                RowButton("Take out", onClick = { store.takeOut(item.id).okOrSay(context) }, forward = false)
            }
        }
        return
    }
    val near = data.liveBoxes.filter { it.originRoomId == item.originRoomId && !it.sealed && it.stage == Stage.AT_ORIGIN }.sortedWith(CODE_ORDER)
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { choosing = !choosing }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Pic.Box, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Text("Put in a container", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                if (choosing) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (choosing) "Close" else "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (choosing) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BigAction(Icons.Default.QrCodeScanner, "Scan the container's label", onClick = onScan)
                if (near.isNotEmpty()) {
                    Text(
                        "Or one that is open in " + (data.room(item.originRoomId)?.name ?: "no room"),
                        style = MaterialTheme.typography.labelLarge, color = Night.TextDim,
                    )
                    near.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), shadow = 0.dp)
                                .clickable { if (store.putInto(item.id, b.id).okOrSay(context)) choosing = false }
                                .heightIn(min = 52.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ContainerThumb(b, data, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.nameAndCode(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (b.contents.isNotBlank()) {
                                    Text(b.contents.lineSequence().first(), style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            Icon(Icons.Default.Add, contentDescription = null, tint = Night.Mint)
                        }
                    }
                }
            }
        }
    }
}

/** The doorway check as a flag and a few plain words. */
@Composable
private fun FitLine(item: Item, dest: Room?, fit: Fit, inches: Boolean, onTakeApart: () -> Unit, onAddDoor: () -> Unit) {
    if (dest == null) return
    val door = dest.doorWidthCm
    val doorText = door?.let { if (inches) "${fmtNum(it / 2.54)} in" else "${fmtNum(it)} cm" } ?: ""
    val scheme = MaterialTheme.colorScheme
    // Words and the one thing to do side by side where they fit; the button goes under them on a small phone.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        when (fit) {
            Fit.UNKNOWN -> if (door == null || door <= 0) {
                Hint("No door width for ${dest.name} yet, so the fit is not checked.")
                GlassChip("Add it", onClick = onAddDoor)
            } else {
                IconFlag(Pic.Size, "Fit not checked")
                Hint("Add its size to check the $doorText door.")
            }
            Fit.OK -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(18.dp))
                Text("Fits through the $doorText door", color = scheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
            Fit.TIGHT -> {
                IconFlag(Icons.Default.Warning, "Tight fit")
                Text("through the $doorText door", style = MaterialTheme.typography.bodyMedium)
            }
            Fit.NO -> if (item.needsDisassembly) {
                IconFlag(Pic.TakeApart, "Take apart")
                Text("to get through the $doorText door", style = MaterialTheme.typography.bodyMedium)
            } else {
                IconFlag(Icons.Default.Warning, "Won't fit", error = true)
                Text("through the $doorText door", style = MaterialTheme.typography.bodyMedium)
                GlassChip("Mark: take apart", icon = Pic.TakeApart, onClick = onTakeApart)
            }
        }
    }
}
