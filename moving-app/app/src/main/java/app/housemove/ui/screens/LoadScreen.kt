package app.housemove.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/**
 * Put one thing on [tripId] (or take it along by hand with null) and describe what happened. [inside] says
 * that the label scanned was on something packed in [m] ("Lamp is inside L012"), so the banner explains why
 * a container was loaded. The Store says no when the thing is not where the trip starts ([whyNotLoad]).
 */
private fun mdLoadOne(store: Store, m: Movable, tripId: String?, inside: String? = null): MdResult {
    val done = store.loadOnto(tripId, listOf(m.id))
    if (done is Outcome.Refused) return MdResult(MdTone.WARN, done.reason, inside ?: "")
    val now = store.data.value
    val trip = now.trip(tripId)
    val open = m.isBox && now.box(m.id)?.sealed == false
    val goes = if (trip?.to == Place.STORAGE) "→ ${Place.STORAGE.label}" else "→ " + (now.room(m.destRoomId)?.name ?: "no room yet")
    val detail = listOf(goes, trip?.name.orEmpty(), if (open) "still open" else "").filter { it.isNotBlank() }
    return MdResult(MdTone.GOOD, if (inside != null) "$inside: loaded ${mdShort(m)}" else "Loaded: ${m.title}", detail.joinToString(" · "))
}

/**
 * Load-out: scan or tick everything as it goes on the van or truck, trip by trip. A trip goes to the new
 * home, to the storage unit, or brings things back from it; what can go on is what is where the trip starts.
 */
@Composable
fun LoadScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val device = deviceInfo()
    val vehicle = data.vehicle()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var result by remember { mutableStateOf<MdResult?>(null) }
    // Goes up with every scan, so the answer to it can be brought into view.
    var scans by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    val trips = data.liveTrips
    val trip = data.trip(device.currentTripId) ?: trips.firstOrNull()
    val fromStorage = trip?.from == Place.STORAGE
    val toStorage = trip?.to == Place.STORAGE
    // Each thing once: a container stands for everything packed inside it.
    val all = data.movables()
    val carried = remember(data) { data.carriedCounts() }
    // What can go on this trip: at the old home, or at the storage unit for a trip back from it.
    val startStage = if (fromStorage) Stage.STORED else Stage.AT_ORIGIN
    val waiting = all.filter { it.stage == startStage }
    val loadedCount = if (fromStorage) all.count { it.tripId == trip?.id && it.stage != Stage.STORED } else all.count { it.stage != Stage.AT_ORIGIN }
    val ofAll = if (fromStorage) loadedCount + waiting.size else all.size
    val forVan = if (fromStorage) waiting else waiting.filter { !it.travelsWithMe }
    val withMe = if (fromStorage) emptyList() else waiting.filter { it.travelsWithMe }.sortedBy { it.title }
    val openBoxes = data.liveBoxes.filter { !it.sealed }.map { it.id }.toSet()
    val originRooms = data.roomsOf(Home.ORIGIN)
    val known = originRooms.map { it.id }.toSet()
    // Things decided Store come first on a trip to the storage unit.
    val storeIds = data.liveItems.filter { it.disposition == Disposition.STORE }.map { it.id }.toSet()
    val grouped = forVan.groupBy { m -> m.originRoomId?.takeIf { it in known } }
    // First-night things sort to the end of each room so they are loaded last.
    val order = compareBy<Movable> { if (toStorage) it.id !in storeIds else false }.thenByDescending { it.priority.ordinal }.thenBy { it.title }
    val groups = buildList<Pair<Room?, List<Movable>>> {
        originRooms.forEach { r -> grouped[r.id]?.let { add(r to it.sortedWith(order)) } }
        grouped[null]?.let { add(null to it.sortedWith(order)) }
    }
    val onTrip = all.filter { it.stage == Stage.LOADED && trip != null && it.tripId == trip.id }.sortedBy { it.title }

    /** The trip things go on now: the one chosen, or a first ordinary one. */
    fun tripNow(): String = trip?.id ?: mdEnsureTrip(store)

    // Any code will do: the label of an item inside a container loads the container, and says so.
    val onScan: (String) -> Unit = { text ->
        val found = mdFind(store.data.value, text)
        val m = found?.carrier
        result = when {
            found == null || m == null -> MdResult(MdTone.BAD, "Unknown label " + labelCodeOf(text), "Pack it as a box first, or check the code.")
            m.stage == startStage -> mdLoadOne(store, m, tripNow(), found.inside)
            m.stage == Stage.LOADED -> MdResult(MdTone.WARN, "Already on the $vehicle", found.inside ?: m.title)
            m.stage == Stage.STORED -> MdResult(MdTone.WARN, "In storage", (found.inside ?: m.title) + ". Bring it back on a trip from storage.")
            else -> MdResult(MdTone.WARN, "Already delivered", found.inside ?: m.title)
        }
        scans++
    }

    Screen("Load the $vehicle") {
        if (all.isEmpty()) {
            // Before there is anything to load: one picture, one line, one button.
            val canPack = data.uses(Part.BOXES)
            EmptyState(
                Icons.Default.LocalShipping, "Nothing to load yet",
                text = "Boxes you pack and items marked Move appear here.",
                action = if (canPack) "Pack a box" else null,
                onAction = if (canPack) ({ nav.open("pack") }) else null,
            )
        } else {
            // Only the scan button stays put. The count, what just happened and the list scroll under it,
            // so the list keeps most of even a small screen with large text.
            MdScanAction(scanning, if (narrowScreen()) "Scan labels" else "Scan labels as they go on") { scanning = !scanning }
            if (scanning) MdScanner(onScan)
            // A scan is answered at the top of the list: bring it back into view if the list was scrolled down.
            LaunchedEffect(scans) { if (scans > 0) listState.scrollToItem(0) }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(top = 2.dp, bottom = 16.dp)) {
                result?.let { r -> item(key = "result") { MdBanner(r, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) } }
                item(key = "head") {
                    ProgressCard(
                        Ratio(loadedCount, ofAll),
                        if (fromStorage) "$loadedCount of $ofAll back from storage" else "$loadedCount of ${all.size} loaded",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        sub = when {
                            toStorage -> "This trip goes to the storage unit"
                            fromStorage -> "This trip goes from storage to ${data.move.destName}"
                            else -> null
                        },
                    ) {
                        // Which trip things go on; a new one for each run of the vehicle.
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            trips.forEach { t ->
                                GlassChip(t.name, icon = t.chipIcon(), selected = t.id == trip?.id, onClick = { store.setDevice { it.copy(currentTripId = t.id) } })
                            }
                            AddTripChips(data) { from, to ->
                                store.addTrip(from, to).valueOrNull()?.let { t -> store.setDevice { it.copy(currentTripId = t.id) } }
                                tab = 0
                            }
                        }
                    }
                }
                stickyHeader(key = "tabs") {
                    GlassTabRow(tab, Modifier.groundBand()) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("To load (${waiting.size})") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("On ${trip?.name ?: "this trip"} (${onTrip.size})") })
                    }
                }
                if (tab == 0) {
                    if (waiting.isEmpty()) item(key = "empty") {
                        EmptyState(Icons.Default.CheckCircle, if (fromStorage) "Nothing left in storage" else "Everything is on the $vehicle")
                    }
                    groups.forEach { (room, list) ->
                        item(key = "h" + (room?.id ?: "none")) {
                            ListHeading(room?.name ?: "No room", MdHeadingPadding, count = list.size) {
                                RowButton(
                                    "Load all",
                                    onClick = {
                                        val done = store.loadOnto(tripNow(), list.map { it.id })
                                        result = if (done is Outcome.Refused) MdResult(MdTone.WARN, done.reason)
                                        else MdResult(MdTone.GOOD, "Loaded ${list.size} from ${room?.name ?: "no room"}")
                                    },
                                    forward = false,
                                )
                            }
                        }
                        items(list, key = { "m" + it.id }) { m ->
                            MdThingRow(m, open = m.id in openBoxes, carried = carried[m.id] ?: 0, note = if (toStorage && m.id in storeIds) "Decided: Store" else "") {
                                RowButton("Load", onClick = { result = mdLoadOne(store, m, tripNow()) })
                            }
                        }
                    }
                    if (withMe.isNotEmpty()) {
                        item(key = "hwithme") { ListHeading("Travels with me, not in the $vehicle", MdHeadingPadding, count = withMe.size) }
                        items(withMe, key = { "w" + it.id }) { m ->
                            MdThingRow(m, open = m.id in openBoxes, carried = carried[m.id] ?: 0) {
                                RowButton(
                                    "With me",
                                    onClick = {
                                        result = if (store.loadOnto(null, listOf(m.id)).ok) MdResult(MdTone.GOOD, "With you: ${m.title}", "Not in the $vehicle")
                                        else MdResult(MdTone.WARN, "${m.title} has already left")
                                    },
                                )
                            }
                        }
                    }
                } else {
                    if (onTrip.isEmpty()) item(key = "tripempty") {
                        if (trip == null) EmptyState(Icons.Default.LocalShipping, "No trip yet", text = "One starts when you load the first thing.")
                        else EmptyState(Icons.Default.LocalShipping, "Nothing is on ${trip.name} yet")
                    }
                    items(onTrip, key = { "t" + it.id }) { m ->
                        MdThingRow(m, open = m.id in openBoxes, carried = carried[m.id] ?: 0) {
                            RowButton(
                                "Undo",
                                onClick = {
                                    // One step back: to where the trip started, the old home or the storage unit.
                                    store.putBack(listOf(m.id))
                                    result = MdResult(MdTone.WARN, "Taken off: ${m.title}")
                                },
                                forward = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adding a trip, one tap each: an ordinary one to the new home; where the move uses a storage unit, one to it;
 * and once anything is in storage, one bringing things back from it.
 */
@Composable
private fun AddTripChips(data: AppData, onAdd: (Place, Place) -> Unit) {
    GlassChip("Add trip", icon = Icons.Default.Add, onClick = { onAdd(Place.OLD_HOME, Place.NEW_HOME) })
    if (data.offersStorage()) GlassChip("Add a trip to storage", icon = Icons.Default.Warehouse, onClick = { onAdd(Place.OLD_HOME, Place.STORAGE) })
    if (data.inStorage().isNotEmpty() && data.move.kind != MoveKind.STORAGE) {
        GlassChip(BRING_BACK_TRIP_NAME, icon = Icons.Default.Undo, onClick = { onAdd(Place.STORAGE, Place.NEW_HOME) })
    }
}
