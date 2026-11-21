package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.*
import app.housemove.ui.*

/** What a first night needs, as categories: the list shows which of them a first-night container covers. */
private val FIRST_NIGHT_NEEDS = listOf("Bedding", "Kitchen", "Bathroom", "Tools")

/** Three short lists of things that need handling differently from the main load. */
@Composable
fun ListsScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val vehicle = data.vehicle()

    val travel = data.liveBoxes.filter { it.travelsWithMe }.map { it.toMovable() } +
        data.liveItems.filter { it.travelsWithMe }.map { it.toMovable() }
    val firstNightBoxes = data.liveBoxes.filter { it.priority == Priority.FIRST_NIGHT }
    val firstNight = firstNightBoxes.map { it.toMovable() } + data.liveItems.filter { it.firstNight }.map { it.toMovable() }
    val hazardous = data.liveBoxes.filter { it.hazardous }.map { it.toMovable() } +
        data.liveItems.filter { it.hazardous }.map { it.toMovable() }
    val nothing = travel.isEmpty() && firstNight.isEmpty() && hazardous.isEmpty()

    fun changeBox(id: String, f: (Box) -> Box) {
        store.data.value.box(id)?.let { store.put(f(it)) }
    }

    fun changeItem(id: String, f: (Item) -> Item) {
        store.data.value.item(id)?.let { store.put(f(it)) }
    }

    Screen("Special lists") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (nothing) {
                item(key = "empty") {
                    val boxes = data.uses(Part.BOXES)
                    EmptyState(
                        Icons.Default.Star, "Nothing on these lists yet",
                        text = "Open a box or item and switch on \"Travels with me\", \"First night\" or \"Hazardous\".",
                        action = if (boxes) "Go to the boxes" else "Go to the items",
                        onAction = { nav.goTab(if (boxes) "boxes" else "items") },
                    )
                }
            }
            item(key = "travel") {
                ListPanel(
                    "Travels with me", Pic.WithMe, "Stays with you: documents, valuables, medication, chargers, keys.", travel, data,
                ) { m ->
                    if (m.isBox) changeBox(m.id) { it.copy(travelsWithMe = false) }
                    else changeItem(m.id) { it.copy(travelsWithMe = false) }
                }
            }
            item(key = "night") {
                ListPanel(
                    "First night", Pic.FirstNight, "Loaded last, off first.", firstNight, data,
                    needs = FIRST_NIGHT_NEEDS, covered = firstNightBoxes.map { data.categoryOf(it) }.toSet(),
                ) { m ->
                    if (m.isBox) changeBox(m.id) { it.copy(priority = Priority.DAY_1) }
                    else changeItem(m.id) { it.copy(firstNight = false) }
                }
            }
            item(key = "hazard") {
                ListPanel(
                    "Hazardous", Pic.Hazard, "Not in the $vehicle: gas, paint, fuel, aerosols, bleach, plants.", hazardous, data, warning = true,
                ) { m ->
                    if (m.isBox) changeBox(m.id) { it.copy(hazardous = false) }
                    else changeItem(m.id) { it.copy(hazardous = false) }
                }
            }
        }
    }
}

/**
 * One list as a glass panel: its picture, how many are on it, and the things themselves
 * with their photos. [needs] are categories the list ought to cover; each shows a tick
 * once something on the list is of that category ([covered]).
 */
@Composable
private fun ListPanel(
    title: String,
    icon: ImageVector,
    hint: String,
    things: List<Movable>,
    data: AppData,
    warning: Boolean = false,
    needs: List<String> = emptyList(),
    covered: Set<String> = emptySet(),
    onRemove: (Movable) -> Unit,
) {
    val nav = LocalNav.current
    GlassCard(Modifier.fillMaxWidth(), tone = if (warning && things.isNotEmpty()) GlassTone.Red else GlassTone.Plain, shape = RoundedCornerShape(26.dp), sheen = true) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassOrb(
                icon, null, size = 44.dp,
                kind = when {
                    things.isEmpty() -> OrbKind.Plain
                    warning -> OrbKind.Amber
                    else -> OrbKind.Selected
                },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (things.isEmpty()) title else "$title (${things.size})", style = MaterialTheme.typography.titleMedium)
                Hint(hint)
            }
        }
        if (needs.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                needs.forEach { c ->
                    val has = c in covered
                    // A tick as well as the tint: packed for the first night, or still to pack.
                    GlassChip(
                        c, icon = if (has) Icons.Default.Check else categoryPicture(c), tone = if (has) GlassTone.Mint else GlassTone.Plain,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = if (has) "$c: packed for the first night" else "$c: nothing packed for the first night yet" },
                    )
                }
            }
        }
        things.forEach { m ->
            HorizontalDivider(color = Night.Hairline)
            val box = if (m.isBox) data.box(m.id) else null
            Row(
                Modifier.fillMaxWidth().clickable { nav.openMovable(m) }.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (box != null) ContainerThumb(box, data, 48.dp) else OwnerThumb(m.id, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(box?.let { data.categoryOf(it) }?.takeIf { it != "Other" }, data.whereIs(m)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onRemove(m) }) {
                    Icon(Icons.Default.Close, contentDescription = "Take ${m.title} off the $title list")
                }
            }
        }
    }
}

/** What to pack when, and how far along each old-home room and each kind of thing is. */
@Composable
fun PackOrderScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val rooms = data.roomsOf(Home.ORIGIN)
    // Containers inside another are sealed, loaded and counted with the one they are in.
    val boxes = data.topBoxes()
    val byCategory = boxes.groupBy { data.categoryOf(it) }

    Screen("Packing order") {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (rooms.isNotEmpty()) {
                item(key = "progress") {
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp), tone = GlassTone.Mint, shape = RoundedCornerShape(26.dp), sheen = true) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Ring(Ratio(rooms.count { it.packed }, rooms.size), size = 72.dp, stroke = 8.dp) {
                                Text(
                                    "${rooms.count { it.packed }}", style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text("of ${rooms.size} room${if (rooms.size == 1) "" else "s"} fully packed", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${boxes.count { it.sealed }} of ${boxes.size} box${if (boxes.size == 1) "" else "es"} sealed",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                item(key = "no-rooms") {
                    EmptyState(
                        Pic.Room, "No rooms yet",
                        text = "Add the rooms of your old home to track packing room by room.",
                        action = "Add rooms", onAction = { nav.open("rooms") },
                    )
                }
            }
            // The same progress by what is in the boxes: books done, kitchen barely started.
            if (byCategory.size > 1) {
                item(key = "categories") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BOX_CATEGORIES.filter { it in byCategory }.forEach { c ->
                            val list = byCategory.getValue(c)
                            CategoryTile(c, list.count { it.sealed }, list.size)
                        }
                    }
                }
            }
            item(key = "order") {
                Fold(
                    "Suggested order", Icons.Default.FormatListNumbered, summary = "Least used first, everyday things last",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    PACK_ORDER.forEachIndexed { i, (what, timing) ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                "${i + 1}.",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(28.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(what, style = MaterialTheme.typography.bodyLarge)
                                Hint(timing)
                            }
                        }
                    }
                }
            }
            items(rooms, key = { it.id }) { room ->
                val fromHere = boxes.filter { it.originRoomId == room.id }
                RoomPackRow(
                    room, data.room(room.mapsTo), Ratio(fromHere.count { it.sealed }, fromHere.size),
                    onPacked = { v -> store.data.value.room(room.id)?.let { store.put(it.copy(packed = v)) } },
                    onPack = {
                        store.setDevice { it.copy(currentRoomId = room.id) }
                        nav.open("pack")
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** One category: its picture and name, sealed of begun, and a bar. */
@Composable
private fun CategoryTile(category: String, sealed: Int, total: Int) {
    val done = sealed == total
    Column(
        Modifier.width(96.dp).glass(RoundedCornerShape(18.dp), shadow = 5.dp)
            .clearAndSetSemantics { contentDescription = "$category: $sealed of $total sealed" }
            .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(categoryPicture(category), contentDescription = null, tint = Night.Mint, modifier = Modifier.size(15.dp))
            Text(category, style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$sealed/$total", fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            // Done is said with a tick as well as with the colour of the bar.
            if (done) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Night.MintLight, modifier = Modifier.size(14.dp))
        }
        Meter(sealed.toFloat() / total, Modifier.fillMaxWidth(), color = if (done) Night.MintLight else Night.AmberDeep)
    }
}

/** One old-home room: a ring of its boxes sealed, where they go, and the switch that says it is finished. */
@Composable
private fun RoomPackRow(room: Room, to: Room?, sealed: Ratio, onPacked: (Boolean) -> Unit, onPack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clickable(enabled = !room.packed, onClick = onPack)
                    .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A finished room shows a full green ring, whatever its boxes say.
                Ring(if (room.packed) Ratio(1, 1) else sealed, size = 48.dp, stroke = 5.dp, color = if (room.packed) scheme.tertiary else scheme.primary) {
                    if (room.packed) Icon(Icons.Default.Check, contentDescription = null, tint = scheme.tertiary)
                    else Text("${sealed.done}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Hint(if (sealed.total == 0) "No boxes yet" else "${sealed.done} of ${sealed.total} sealed")
                        if (to != null) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "goes to",
                                tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp),
                            )
                            RoomBadge(to)
                        }
                    }
                    if (!room.packed) {
                        Text("Pack a box from here", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    }
                }
            }
            Column(
                Modifier.toggleable(value = room.packed, role = Role.Switch, onValueChange = onPacked)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Switch(checked = room.packed, onCheckedChange = null)
                Text("Fully packed", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
