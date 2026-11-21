package app.housemove.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*
import kotlin.math.roundToLong

/** Everything about one catalogued item, editable in place. */
@Composable
fun ItemScreen(id: String) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val item = data.item(id)

    if (item == null) {
        Screen("Item") {
            EmptyHint("This item is no longer here. It may have been deleted on this phone or another one.")
        }
        return
    }

    var confirmDelete by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanningSerial by remember { mutableStateOf(false) }
    // Bumped when a scan fills the serial number in, so the text field picks the new value up.
    var serialScans by remember { mutableIntStateOf(0) }
    val electronic = item.isElectronic()

    // Always edit the newest copy in the store, so two quick changes never overwrite each other.
    fun edit(f: (Item) -> Item) {
        store.data.value.item(id)?.let { store.put(f(it)) }
    }

    val moving = item.disposition == Disposition.MOVE
    val origin = data.room(item.originRoomId)
    val dest = data.room(item.destRoomId)
    val money = data.move.currency.trim()

    Screen(
        title = item.title(),
        actions = {
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete item")
            }
        },
    ) {
        ScrollColumn {
            Section("Photos") {
                PhotoStrip(item.id, PhotoKind.GENERAL, label = "Photos of the item")
            }

            Section("Basics") {
                TextInput(
                    value = item.name,
                    onChange = { v -> edit { it.copy(name = v) } },
                    label = "What is it?",
                    key = "$id-name",
                    mic = true,
                    placeholder = "e.g. Oak wardrobe",
                )
                Text("Room in the old home", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RoomPicker(Home.ORIGIN, item.originRoomId, onPick = { rid ->
                    edit { cur ->
                        val mapped = store.data.value.room(rid)?.mapsTo
                        cur.copy(
                            originRoomId = rid,
                            destRoomId = cur.destRoomId ?: if (cur.disposition == Disposition.MOVE) mapped else null,
                        )
                    }
                })
                TextInput(
                    value = item.notes,
                    onChange = { v -> edit { it.copy(notes = v) } },
                    label = "Notes",
                    key = "$id-notes",
                    singleLine = false,
                    mic = true,
                )
                SwitchRow(
                    "It has a make, model and serial number", electronic, { v -> edit { it.copy(electronic = v) } },
                    sub = "TVs, computers, appliances, tools, bikes. Worth recording for insurance, warranty and theft.",
                )
            }

            if (electronic) {
                Section("Make, model and serial number") {
                    TextInput(item.make, { v -> edit { it.copy(make = v) } }, "Make", key = "$id-make", placeholder = "e.g. Samsung")
                    TextInput(item.model, { v -> edit { it.copy(model = v) } }, "Model", key = "$id-model", placeholder = "e.g. QE55Q60")
                    TextInput(item.serial, { v -> edit { it.copy(serial = v.trim()) } }, "Serial number", key = "$id-serial-$serialScans")
                    OutlinedButton(onClick = { scanningSerial = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan the serial number barcode")
                    }
                    Hint("The label is usually on the back or underneath. If its barcode will not scan, a clear photo does the job.")
                    PhotoStrip(item.id, PhotoKind.SERIAL, label = "Photo of the serial label")
                    PhotoStrip(item.id, PhotoKind.RECEIPT, label = "Receipt or warranty card")
                    DateButton(item.boughtDay, { d -> edit { it.copy(boughtDay = d) } }, "Bought", Modifier.fillMaxWidth())
                    DateButton(item.warrantyDay, { d -> edit { it.copy(warrantyDay = d) } }, "Warranty until", Modifier.fillMaxWidth())
                }

                Section("What travels with it") {
                    TextInput(
                        item.accessories, { v -> edit { it.copy(accessories = v) } }, "Remote, leads, stand, screws…",
                        key = "$id-acc", singleLine = false, mic = true,
                    )
                    CheckRow(
                        "Bagged and marked with this item's name", item.accessoriesPacked, { v -> edit { it.copy(accessoriesPacked = v) } },
                        sub = "Tape the bag to the item, or note the box it went in below.",
                    )
                    TextInput(item.accessoryBox, { v -> edit { it.copy(accessoryBox = v.trim()) } }, "Box they are in (label, optional)", key = "$id-accbox")
                    val accBox = data.findByLabel(item.accessoryBox)
                    if (item.accessoryBox.isNotBlank()) {
                        if (accBox != null) TextButton(onClick = { nav.openMovable(accBox) }) { Text("Open ${accBox.title}") }
                        else Hint("No box or item has the label ${item.accessoryBox} yet.")
                    }
                }

                val tips = handlingTips(item.name)
                if (tips.isNotEmpty()) {
                    Section("Moving it safely") {
                        tips.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }

            Section("What happens to it?") {
                ChipPicker(
                    options = Disposition.entries,
                    selected = item.disposition,
                    label = { it.label },
                    onPick = { d ->
                        edit { cur ->
                            val mapped = store.data.value.room(cur.originRoomId)?.mapsTo
                            cur.copy(
                                disposition = d,
                                destRoomId = if (d == Disposition.MOVE && cur.destRoomId == null) mapped else cur.destRoomId,
                            )
                        }
                    },
                )
                when (item.disposition) {
                    Disposition.UNDECIDED -> Hint("Decide now, so you don't pack and carry something you don't want.")
                    Disposition.MOVE -> Hint("It is coming with you. Pick its new room below.")
                    Disposition.SELL -> {
                        SwitchRow("Listed for sale", item.listed, { v -> edit { it.copy(listed = v) } })
                        NumberInput(
                            value = item.price,
                            onChange = { v -> edit { it.copy(price = v) } },
                            label = if (money.isEmpty()) "Asking price" else "Asking price ($money)",
                            modifier = Modifier.fillMaxWidth(),
                            key = "$id-price",
                        )
                        DateButton(item.deadlineDay, { d -> edit { it.copy(deadlineDay = d) } }, "Sell by")
                        SwitchRow("Sold and gone", item.gone, { v -> edit { it.copy(gone = v) } }, sub = "Tick when the buyer has collected it.")
                    }
                    Disposition.DONATE -> {
                        DateButton(item.deadlineDay, { d -> edit { it.copy(deadlineDay = d) } }, "Donate by")
                        SwitchRow("Gone", item.gone, { v -> edit { it.copy(gone = v) } }, sub = "Tick when it has been dropped off or collected.")
                    }
                    Disposition.BIN -> {
                        DateButton(item.deadlineDay, { d -> edit { it.copy(deadlineDay = d) } }, "Get rid of it by")
                        SwitchRow("Gone", item.gone, { v -> edit { it.copy(gone = v) } }, sub = "Tick when it has been taken away.")
                    }
                    Disposition.STORE -> Hint("Going into storage. It is not counted in the van load for the new home.")
                }
            }

            if (moving) {
                Section("Where it goes in the new home") {
                    RoomPicker(Home.DEST, item.destRoomId, onPick = { rid -> edit { it.copy(destRoomId = rid) } })
                    FitLine(item, dest, data.fitOf(item), data.move.useInches)
                }
            }

            Section("Size and handling") {
                val inches = data.move.useInches
                val unit = if (inches) "in" else "cm"
                fun shown(cm: Double?): Double? = cm?.let { if (inches) (it / 2.54 * 100).roundToLong() / 100.0 else it }
                fun toCm(v: Double?): Double? = v?.takeIf { it > 0 }?.let { if (inches) it * 2.54 else it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberInput(shown(item.widthCm), { v -> edit { it.copy(widthCm = toCm(v)) } }, "Width $unit", Modifier.weight(1f), key = "$id-w-$unit")
                    NumberInput(shown(item.depthCm), { v -> edit { it.copy(depthCm = toCm(v)) } }, "Depth $unit", Modifier.weight(1f), key = "$id-d-$unit")
                    NumberInput(shown(item.heightCm), { v -> edit { it.copy(heightCm = toCm(v)) } }, "Height $unit", Modifier.weight(1f), key = "$id-h-$unit")
                }
                Hint("Measure the widest points. The sizes are used to check doorways and estimate the van load.")
                NumberInput(
                    value = item.value,
                    onChange = { v -> edit { it.copy(value = v) } },
                    label = if (money.isEmpty()) "What it is worth" else "What it is worth ($money)",
                    modifier = Modifier.fillMaxWidth(),
                    key = "$id-value",
                )
                SwitchRow("Fragile", item.fragile, { v -> edit { it.copy(fragile = v) } })
                SwitchRow("Needs two people to lift", item.twoPerson, { v -> edit { it.copy(twoPerson = v) } })
                SwitchRow(
                    "Keep upright", item.keepUpright, { v -> edit { it.copy(keepUpright = v) } },
                    sub = "TVs, fridges and freezers. Shown when loading and unloading.",
                )
                SwitchRow(
                    "Needs taking apart", item.needsDisassembly, { v -> edit { it.copy(needsDisassembly = v) } },
                    sub = "To get it out, through a door, or into the van.",
                )
            }

            if (moving) {
                val steps = listOfNotNull(
                    item.prepEmptied, item.prepPhotos, item.prepDisassembled.takeIf { item.needsDisassembly },
                    item.prepHardware, item.prepCables, item.prepWrapped,
                    item.prepCablesLabelled.takeIf { electronic }, item.prepSignedOut.takeIf { electronic },
                )
                Section("Getting it ready (${steps.count { it }} of ${steps.size})") {
                    CheckRow("Emptied", item.prepEmptied, { v -> edit { it.copy(prepEmptied = v) } }, sub = "Drawers, shelves and cupboards cleared out.")
                    CheckRow(
                        "Condition photos taken", item.prepPhotos, { v -> edit { it.copy(prepPhotos = v) } },
                        sub = "All sides, plus any marks it already has.",
                    )
                    PhotoStrip(item.id, PhotoKind.CONDITION, label = "Condition before the move")
                    if (item.needsDisassembly) {
                        CheckRow(
                            "Taken apart", item.prepDisassembled, { v -> edit { it.copy(prepDisassembled = v) } },
                            sub = "Photograph each step so you can rebuild it in reverse.",
                        )
                        PhotoStrip(item.id, PhotoKind.DISASSEMBLY, label = "Taking-apart steps")
                    }
                    CheckRow(
                        "Screws and fittings bagged", item.prepHardware, { v -> edit { it.copy(prepHardware = v) } },
                        sub = "Write \"${item.label ?: item.title()}\" on the bag and tape it to the item.",
                    )
                    CheckRow(
                        "Cables photographed", item.prepCables, { v -> edit { it.copy(prepCables = v) } },
                        sub = "For anything with wires: photograph the back before unplugging.",
                    )
                    PhotoStrip(item.id, PhotoKind.CABLES, label = "Cables and connections")
                    if (electronic) {
                        CheckRow(
                            "Cables labelled and bagged", item.prepCablesLabelled, { v -> edit { it.copy(prepCablesLabelled = v) } },
                            sub = "Tag each lead with what it plugs into. Keep them with the remote.",
                        )
                        CheckRow(
                            "Backed up and ready to unplug", item.prepSignedOut, { v -> edit { it.copy(prepSignedOut = v) } },
                            sub = "Computers, consoles and smart TVs: back up first. Take batteries out of remotes for storage.",
                        )
                    }
                    CheckRow("Wrapped and protected", item.prepWrapped, { v -> edit { it.copy(prepWrapped = v) } })
                }

                Section("Special handling") {
                    SwitchRow(
                        "Travels with me", item.travelsWithMe, { v -> edit { it.copy(travelsWithMe = v) } },
                        sub = "Goes in your own car, not the van.",
                    )
                    SwitchRow(
                        "Needed on the first night", item.firstNight, { v -> edit { it.copy(firstNight = v) } },
                        sub = "Loaded last so it comes off first.",
                    )
                    SwitchRow(
                        "Hazardous / not in the van", item.hazardous, { v -> edit { it.copy(hazardous = v) } },
                        sub = "Gas bottles, paint, fuel and similar need moving separately.",
                    )
                }

                Section("Label") {
                    val label = item.label
                    if (label != null) {
                        Text(label, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        Hint("This code is on the item. Scan it, or type it, when loading and unloading.")
                        TextButton(onClick = { edit { it.copy(label = null) } }) { Text("Remove label") }
                    } else {
                        Hint("A label lets you scan this item on move day. Without one you can still tick it off by hand.")
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
                    }
                }

                Section("Where it is now") {
                    ChipPicker(
                        options = Stage.entries,
                        selected = item.stage,
                        label = { stageText(it, isBox = false) },
                        onPick = { s -> edit { it.copy(stage = s) } },
                    )
                    val trip = data.trip(item.tripId)
                    if (trip != null && item.stage != Stage.AT_ORIGIN) Hint("Travelled on: ${trip.name}")
                    if (item.needsDisassembly) {
                        CheckRow(
                            "Put back together", item.reassembled, { v -> edit { it.copy(reassembled = v) } },
                            sub = "Use the taking-apart photos above, last one first.",
                        )
                    }
                }

                Section("Damage") {
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

            Hint(
                "From ${origin?.name ?: "no room"}" + if (moving) " to ${dest?.name ?: "no room yet"}" else "",
                Modifier.padding(bottom = 24.dp),
            )
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

/** Plain-words result of the doorway check. */
@Composable
private fun FitLine(item: Item, dest: Room?, fit: Fit, inches: Boolean) {
    val door = dest?.doorWidthCm
    val doorText = door?.let { if (inches) "${fmtNum(it / 2.54)} in" else "${fmtNum(it)} cm" } ?: ""
    val hasSize = item.widthCm != null && item.depthCm != null && item.heightCm != null
    when (fit) {
        Fit.UNKNOWN -> Hint(
            when {
                dest == null -> "Pick a room to check whether it will fit through the door."
                door == null || door <= 0 -> "To check the fit, add the narrowest door width for ${dest.name} under More → Rooms."
                !hasSize -> "Enter the width, depth and height below to check it fits through the $doorText door."
                else -> "Not checked yet."
            }
        )
        Fit.OK -> Text(
            "Fits through the $doorText door to ${dest?.name ?: "its room"}.",
            color = MaterialTheme.colorScheme.tertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Fit.TIGHT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Flag("Tight fit")
            Text(
                "Only just fits through the $doorText door. Measure again and protect the door frame.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
        Fit.NO -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Flag("Won't fit", error = !item.needsDisassembly)
            Text(
                if (item.needsDisassembly) "Too big for the $doorText door in one piece. It is marked to be taken apart, so that is covered."
                else "Too big for the $doorText door in one piece. Mark it as needing taking apart, or pick another room.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
