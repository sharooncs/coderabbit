package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import kotlin.math.roundToLong

/** The decisions offered as tiles. Tapping the chosen one again takes the decision back. */
private val DECISIONS = listOf(Disposition.MOVE, Disposition.SELL, Disposition.DONATE, Disposition.BIN, Disposition.STORE)

/**
 * Everything about one catalogued item, editable in place. The photo, the name, the
 * decision and the new room are always showing; the rest waits in sections that open
 * when tapped, each saying in one line what is already set.
 */
@Composable
fun ItemScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val item = data.item(id)

    if (item == null) {
        Screen("Item") {
            EmptyState(Pic.Item, "This item is no longer here", text = "It may have been deleted on this phone or another one.")
        }
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanningSerial by remember { mutableStateOf(false) }
    // Bumped when a scan fills the serial number in, so the text field picks the new value up.
    var serialScans by remember { mutableIntStateOf(0) }
    // Set when "It has a make, model…" is switched on here, so its section appears already open.
    var deviceJustOn by rememberSaveable { mutableStateOf(false) }

    // Always edit the newest copy in the store, so two quick changes never overwrite each other.
    fun edit(f: (Item) -> Item) {
        store.data.value.item(id)?.let { store.put(f(it)) }
    }

    val moving = item.disposition == Disposition.MOVE
    val origin = data.room(item.originRoomId)
    val dest = data.room(item.destRoomId)
    val money = data.move.currency.trim()
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
    val config = LocalConfiguration.current
    val narrow = config.screenWidthDp / config.fontScale < 340

    Screen(
        title = item.title(),
        actions = {
            TopAction(Icons.Default.Delete, "Delete item") { confirmDelete = true }
        },
    ) {
        ScrollColumn {
            PhotoHero(item.id, PhotoKind.GENERAL, label = item.title(), height = 190.dp)

            TextInput(
                value = item.name,
                onChange = { v -> edit { it.copy(name = v) } },
                label = "What is it?",
                key = "$id-name",
                mic = true,
                placeholder = "e.g. Oak wardrobe",
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("What happens to it?", style = MaterialTheme.typography.titleMedium)
                IconChoice(
                    options = DECISIONS.map { Choice(it, it.label, it.icon()) },
                    selected = item.disposition,
                    onPick = { d ->
                        edit { cur ->
                            val next = if (cur.disposition == d) Disposition.UNDECIDED else d
                            val mapped = store.data.value.room(cur.originRoomId)?.mapsTo
                            cur.copy(
                                disposition = next,
                                destRoomId = if (next == Disposition.MOVE && cur.destRoomId == null) mapped else cur.destRoomId,
                            )
                        }
                    },
                    columns = if (narrow) 3 else 5,
                )
                when (item.disposition) {
                    Disposition.SELL -> {
                        ToggleTiles(
                            listOf(
                                Toggle("Listed for sale", Icons.Default.Campaign, item.listed) { v -> edit { it.copy(listed = v) } },
                                Toggle("Sold and gone", goneIcon(item.gone), item.gone) { v -> edit { it.copy(gone = v) } },
                            )
                        )
                        NumberInput(
                            value = item.price,
                            onChange = { v -> edit { it.copy(price = v) } },
                            label = if (money.isEmpty()) "Asking price" else "Asking price ($money)",
                            modifier = Modifier.fillMaxWidth(),
                            key = "$id-price",
                        )
                        DateButton(item.deadlineDay, { d -> edit { it.copy(deadlineDay = d) } }, "Sell by", Modifier.fillMaxWidth())
                    }
                    Disposition.DONATE, Disposition.BIN -> {
                        ToggleTiles(listOf(Toggle("Gone", goneIcon(item.gone), item.gone) { v -> edit { it.copy(gone = v) } }))
                        DateButton(
                            item.deadlineDay, { d -> edit { it.copy(deadlineDay = d) } },
                            if (item.disposition == Disposition.DONATE) "Donate by" else "Get rid of it by", Modifier.fillMaxWidth(),
                        )
                    }
                    Disposition.STORE -> Hint("Going into storage, so it is not counted in the $vehicle load.")
                    Disposition.MOVE, Disposition.UNDECIDED -> Unit
                }
            }

            if (moving && (movesHouse || dest != null)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Where it goes", style = MaterialTheme.typography.titleMedium)
                    RoomTiles(Home.DEST, item.destRoomId, onPick = { rid -> edit { it.copy(destRoomId = rid) } })
                    if (data.uses(Part.PLAN)) {
                        FitLine(
                            item, dest, data.fitOf(item), inches,
                            onTakeApart = { edit { it.copy(needsDisassembly = true) } },
                            onAddDoor = { nav.open("rooms") },
                        )
                    }
                }
            }

            // ---- everything else, closed until wanted ----------------------------------

            if (electronic) {
                Fold(
                    "Make, model and serial number", Pic.Device,
                    summary = listOf(item.makeModel(), if (item.serial.isBlank()) "" else "S/N ${item.serial}")
                        .filter { it.isNotBlank() }.joinToString(" · ")
                        .ifBlank { if (data.photosOf(id, PhotoKind.SERIAL).isNotEmpty()) "Serial label photographed" else "Nothing recorded yet" },
                    open = deviceJustOn,
                    key = "device-$deviceJustOn",
                ) {
                    TextInput(item.make, { v -> edit { it.copy(make = v) } }, "Make", key = "$id-make", placeholder = "e.g. Samsung")
                    TextInput(item.model, { v -> edit { it.copy(model = v) } }, "Model", key = "$id-model", placeholder = "e.g. QE55Q60")
                    TextInput(item.serial, { v -> edit { it.copy(serial = v.trim()) } }, "Serial number", key = "$id-serial-$serialScans")
                    OutlinedButton(onClick = { scanningSerial = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan the serial number barcode")
                    }
                    Hint("The label is usually on the back or underneath.")
                    PhotoStrip(item.id, PhotoKind.SERIAL, label = "Photo of the serial label")
                    PhotoStrip(item.id, PhotoKind.RECEIPT, label = "Receipt or warranty card")
                    DateButton(item.boughtDay, { d -> edit { it.copy(boughtDay = d) } }, "Bought", Modifier.fillMaxWidth())
                    DateButton(item.warrantyDay, { d -> edit { it.copy(warrantyDay = d) } }, "Warranty until", Modifier.fillMaxWidth())
                    SwitchRow("It has a make, model and serial number", true, { v -> deviceJustOn = false; edit { it.copy(electronic = v) } })
                }

                Fold(
                    "What travels with it", Icons.Default.Cable,
                    summary = item.accessories.lineSequence().firstOrNull { it.isNotBlank() }
                        ?.let { it + if (item.accessoriesPacked) " · bagged" else "" } ?: "Nothing listed",
                ) {
                    TextInput(
                        item.accessories, { v -> edit { it.copy(accessories = v) } }, "Remote, leads, stand, screws…",
                        key = "$id-acc", singleLine = false, mic = true,
                    )
                    CheckRow("Bagged and marked with this item's name", item.accessoriesPacked, { v -> edit { it.copy(accessoriesPacked = v) } })
                    TextInput(item.accessoryBox, { v -> edit { it.copy(accessoryBox = v.trim()) } }, "Box they are in (label, optional)", key = "$id-accbox")
                    val accBox = data.findByLabel(item.accessoryBox)
                    if (item.accessoryBox.isNotBlank()) {
                        if (accBox != null) TextButton(onClick = { nav.openMovable(accBox) }) { Text("Open ${accBox.title}") }
                        else Hint("No box or item has the label ${item.accessoryBox} yet.")
                    }
                }

                val tips = handlingTips(item.name)
                if (tips.isNotEmpty()) {
                    Fold("Moving it safely", Icons.Default.TipsAndUpdates, summary = tips.first()) {
                        tips.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            } else if (offerDevice) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Pic.Device, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.weight(1f)) {
                            SwitchRow(
                                "It has a make, model and serial number", false,
                                { v -> deviceJustOn = v; edit { it.copy(electronic = v) } },
                            )
                        }
                    }
                }
            }

            Fold(
                "Size and handling", Pic.Size,
                summary = listOfNotNull(
                    sizeText(item, inches),
                    item.value?.let { "Worth $money${fmtNum(it)}" },
                    "Fragile".takeIf { item.fragile },
                    "Two people".takeIf { item.twoPerson },
                    "Keep upright".takeIf { item.keepUpright },
                    "Take apart".takeIf { item.needsDisassembly },
                ).joinToString(" · ").ifBlank { "No size yet" },
            ) {
                val unit = if (inches) "in" else "cm"
                fun shown(cm: Double?): Double? = cm?.let { if (inches) (it / 2.54 * 100).roundToLong() / 100.0 else it }
                fun toCm(v: Double?): Double? = v?.takeIf { it > 0 }?.let { if (inches) it * 2.54 else it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberInput(shown(item.widthCm), { v -> edit { it.copy(widthCm = toCm(v)) } }, "Width $unit", Modifier.weight(1f), key = "$id-w-$unit")
                    NumberInput(shown(item.depthCm), { v -> edit { it.copy(depthCm = toCm(v)) } }, "Depth $unit", Modifier.weight(1f), key = "$id-d-$unit")
                    NumberInput(shown(item.heightCm), { v -> edit { it.copy(heightCm = toCm(v)) } }, "Height $unit", Modifier.weight(1f), key = "$id-h-$unit")
                }
                Hint("Measure the widest points. Used to check doors and the $vehicle load.")
                ToggleTiles(
                    listOf(
                        Toggle("Fragile", Pic.Fragile, item.fragile) { v -> edit { it.copy(fragile = v) } },
                        Toggle("Two people", Pic.TwoPeople, item.twoPerson) { v -> edit { it.copy(twoPerson = v) } },
                        Toggle("Keep upright", Pic.Upright, item.keepUpright) { v -> edit { it.copy(keepUpright = v) } },
                        Toggle("Take apart", Pic.TakeApart, item.needsDisassembly) { v -> edit { it.copy(needsDisassembly = v) } },
                    ),
                    columns = 2,
                )
                NumberInput(
                    value = item.value,
                    onChange = { v -> edit { it.copy(value = v) } },
                    label = if (money.isEmpty()) "What it is worth" else "What it is worth ($money)",
                    modifier = Modifier.fillMaxWidth(),
                    key = "$id-value",
                )
            }

            if (moving) {
                val steps = listOfNotNull(
                    item.prepEmptied, item.prepPhotos, item.prepDisassembled.takeIf { item.needsDisassembly },
                    item.prepHardware, item.prepCables, item.prepWrapped,
                    item.prepCablesLabelled.takeIf { electronic }, item.prepSignedOut.takeIf { electronic },
                )
                if (movesHouse || steps.any { it }) Fold("Getting it ready", Icons.Default.Checklist, summary = "${steps.count { it }} of ${steps.size} done") {
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
                    Hint("Write \"${item.label ?: item.title()}\" on the bag of screws and tape it to the item.")
                }

                if (movesHouse || item.travelsWithMe || item.firstNight || item.hazardous) Fold(
                    "Special handling", Icons.Default.Star,
                    summary = listOfNotNull(
                        "Travels with me".takeIf { item.travelsWithMe },
                        "First night".takeIf { item.firstNight },
                        "Hazardous".takeIf { item.hazardous },
                    ).joinToString(" · ").ifBlank { "Nothing special" },
                ) {
                    ToggleTiles(
                        listOf(
                            Toggle("Travels with me", Pic.WithMe, item.travelsWithMe) { v -> edit { it.copy(travelsWithMe = v) } },
                            Toggle("First night", Pic.FirstNight, item.firstNight) { v -> edit { it.copy(firstNight = v) } },
                            Toggle("Hazardous", Pic.Hazard, item.hazardous) { v -> edit { it.copy(hazardous = v) } },
                        )
                    )
                    Hint("Hazardous: gas bottles, paint and fuel cannot go in the $vehicle.")
                }

                if (movesHouse || data.uses(Part.LABELS) || item.label != null) Fold("Label", Pic.Label, summary = item.label ?: "No label yet") {
                    val label = item.label
                    if (label != null) {
                        Text(label, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Hint("Scan or type this code when loading and unloading.")
                        TextButton(onClick = { edit { it.copy(label = null) } }) { Text("Remove label") }
                    } else {
                        Button(onClick = { scanning = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Scan a printed label")
                        }
                        OutlinedButton(
                            onClick = {
                                val code = store.nextHandCode()
                                edit { it.copy(label = code) }
                                toast(context, "Write $code on the item with a marker.")
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Use a handwritten code")
                        }
                        Hint("With a label, this item can be scanned on move day.")
                    }
                }

                val trip = data.trip(item.tripId)?.takeIf { item.stage != Stage.AT_ORIGIN }
                if (movesHouse || item.stage != Stage.AT_ORIGIN) Fold(
                    "Where it is now", Pic.Where,
                    summary = stageText(item.stage, isBox = false, vehicle) + (trip?.let { " · ${it.name}" } ?: ""),
                ) {
                    IconChoice(
                        options = Stage.entries.map { Choice(it, stageText(it, isBox = false, vehicle), it.icon()) },
                        selected = item.stage,
                        onPick = { s -> edit { it.copy(stage = s) } },
                        columns = 2,
                    )
                    if (trip != null) Hint("Travelled on ${trip.name}")
                    if (item.needsDisassembly) {
                        CheckRow("Put back together", item.reassembled, { v -> edit { it.copy(reassembled = v) } })
                    }
                }

                if (movesHouse || item.damaged) Fold(
                    "Damage", Pic.Damage,
                    summary = if (item.damaged) item.damageNote.ifBlank { "Damaged in the move" } else "None",
                ) {
                    SwitchRow("Damaged in the move", item.damaged, { v -> edit { it.copy(damaged = v) } })
                    if (item.damaged) {
                        TextInput(
                            value = item.damageNote,
                            onChange = { v -> edit { it.copy(damageNote = v) } },
                            label = "What happened?",
                            key = "$id-damage",
                            singleLine = false,
                            mic = true,
                        )
                        PhotoStrip(item.id, PhotoKind.DAMAGE, label = "Damage photos")
                    }
                }
            }

            Fold(
                "Notes and old room", Pic.Notes,
                summary = listOfNotNull(
                    "From ${origin?.name ?: "no room"}",
                    item.notes.lineSequence().firstOrNull { it.isNotBlank() },
                ).joinToString(" · "),
            ) {
                TextInput(
                    value = item.notes,
                    onChange = { v -> edit { it.copy(notes = v) } },
                    label = "Notes",
                    key = "$id-notes",
                    singleLine = false,
                    mic = true,
                )
                Text("Room in the old home", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RoomTiles(Home.ORIGIN, item.originRoomId, onPick = { rid ->
                    edit { cur ->
                        val mapped = store.data.value.room(rid)?.mapsTo
                        cur.copy(
                            originRoomId = rid,
                            destRoomId = cur.destRoomId ?: if (cur.disposition == Disposition.MOVE) mapped else null,
                        )
                    }
                })
            }

            Spacer(Modifier.height(12.dp))
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
                    edit { it.copy(serial = serial) }
                    serialScans++
                    toast(context, "Serial number: $serial. Check it against the label.")
                    scanningSerial = false
                }
            },
            onClose = { scanningSerial = false },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this item?",
            text = "\"${item.title()}\" and its photos will be removed from every phone at the next sync.",
            onConfirm = {
                store.data.value.photosOf(id).forEach { store.delete(it) }
                store.delete(item)
                nav.popBackStack()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** An empty circle until it has gone, then a tick: the same picture as on the Sell, donate, bin list. */
private fun goneIcon(gone: Boolean) = if (gone) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked

/** "120 × 74 × 205 cm", with a ? for a side not measured yet. Null when nothing is measured. */
private fun sizeText(item: Item, inches: Boolean): String? {
    val sides = listOf(item.widthCm, item.depthCm, item.heightCm)
    if (sides.all { it == null }) return null
    return sides.joinToString(" × ") { cm -> cm?.let { fmtNum(if (inches) it / 2.54 else it) } ?: "?" } + if (inches) " in" else " cm"
}

/** The doorway check as a flag and a few plain words. */
@Composable
private fun FitLine(item: Item, dest: Room?, fit: Fit, inches: Boolean, onTakeApart: () -> Unit, onAddDoor: () -> Unit) {
    if (dest == null) return
    val door = dest.doorWidthCm
    val doorText = door?.let { if (inches) "${fmtNum(it / 2.54)} in" else "${fmtNum(it)} cm" } ?: ""
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (fit) {
            Fit.UNKNOWN -> if (door == null || door <= 0) {
                Hint("No door width for ${dest.name} yet, so the fit is not checked.", Modifier.weight(1f))
                TextButton(onClick = onAddDoor) { Text("Add it") }
            } else {
                IconFlag(Pic.Size, "Fit not checked")
                Hint("Add its size to check the $doorText door.", Modifier.weight(1f))
            }
            Fit.OK -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(18.dp))
                Text("Fits through the $doorText door", color = scheme.tertiary, style = MaterialTheme.typography.bodyMedium)
            }
            Fit.TIGHT -> {
                IconFlag(Icons.Default.Warning, "Tight fit")
                Text("through the $doorText door", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            Fit.NO -> if (item.needsDisassembly) {
                IconFlag(Pic.TakeApart, "Take apart")
                Text("to get through the $doorText door", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            } else {
                IconFlag(Icons.Default.Warning, "Won't fit", error = true)
                Text("the $doorText door", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onTakeApart) { Text("Mark: take apart") }
            }
        }
    }
}
