package app.housemove.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.Home
import app.housemove.data.Movable
import app.housemove.data.Part
import app.housemove.data.Ratio
import app.housemove.data.Room
import app.housemove.data.Stage
import app.housemove.data.Store
import app.housemove.data.Trip
import app.housemove.data.labelFromQr
import app.housemove.data.movables
import app.housemove.data.newId
import app.housemove.data.uses
import app.housemove.data.vehicle
import app.housemove.ui.*

/**
 * Put one thing on the current trip and describe what happened. [inside] says that the
 * label scanned was on something packed in [m] ("Lamp is inside L012"), so the banner
 * explains why a container was loaded.
 */
private fun mdLoadOne(store: Store, m: Movable, inside: String? = null): MdResult {
    val tripId = mdEnsureTrip(store)
    mdSetStage(store, m, Stage.LOADED, tripId)
    val now = store.data.value
    val room = now.room(m.destRoomId)?.name ?: "no room yet"
    val open = m.isBox && now.box(m.id)?.sealed == false
    val trip = now.trip(tripId)?.name ?: ""
    val detail = listOf("→ $room", trip, if (open) "still open" else "").filter { it.isNotBlank() }
    return MdResult(MdTone.GOOD, if (inside != null) "$inside: loaded ${mdShort(m)}" else "Loaded: ${m.title}", detail.joinToString(" · "))
}

/** Load-out: scan or tick everything as it goes on the van or truck, trip by trip. */
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

    val trips = data.liveTrips
    val trip = data.trip(device.currentTripId) ?: trips.firstOrNull()
    // Each thing once: a container stands for everything packed inside it.
    val all = data.movables()
    val carried = remember(data) { mdCarried(data) }
    val loadedCount = all.count { it.stage != Stage.AT_ORIGIN }
    val waiting = all.filter { it.stage == Stage.AT_ORIGIN }
    val forVan = waiting.filter { !it.travelsWithMe }
    val withMe = waiting.filter { it.travelsWithMe }.sortedBy { it.title }
    val openBoxes = data.liveBoxes.filter { !it.sealed }.map { it.id }.toSet()
    val originRooms = data.roomsOf(Home.ORIGIN)
    val known = originRooms.map { it.id }.toSet()
    val grouped = forVan.groupBy { m -> m.originRoomId?.takeIf { it in known } }
    // First-night things sort to the end of each room so they are loaded last.
    val order = compareByDescending<Movable> { it.priority.ordinal }.thenBy { it.title }
    val groups = buildList<Pair<Room?, List<Movable>>> {
        originRooms.forEach { r -> grouped[r.id]?.let { add(r to it.sortedWith(order)) } }
        grouped[null]?.let { add(null to it.sortedWith(order)) }
    }
    val onTrip = all.filter { it.stage == Stage.LOADED && trip != null && it.tripId == trip.id }.sortedBy { it.title }

    // Any code will do: the label of an item inside a container loads the container, and says so.
    val onScan: (String) -> Unit = { text ->
        val found = mdFind(store.data.value, text)
        val m = found?.carrier
        result = when {
            found == null || m == null -> MdResult(MdTone.BAD, "Unknown label " + labelFromQr(text), "Pack it as a box first, or check the code.")
            m.stage == Stage.AT_ORIGIN -> mdLoadOne(store, m, found.inside)
            m.stage == Stage.LOADED -> MdResult(MdTone.WARN, "Already on the $vehicle", found.inside ?: m.title)
            else -> MdResult(MdTone.WARN, "Already delivered", found.inside ?: m.title)
        }
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
            MdProgressHeader(
                Ratio(loadedCount, all.size),
                "$loadedCount of ${all.size} loaded",
                Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            ) {
                // Which trip things go on; a new one for each run of the vehicle.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    trips.forEach { t ->
                        GlassChip(t.name, selected = t.id == trip?.id, onClick = { store.setDevice { it.copy(currentTripId = t.id) } })
                    }
                    GlassChip(
                        "Add trip", icon = Icons.Default.Add,
                        onClick = {
                            val n = trips.size + 1
                            val t = Trip(id = newId(), name = "Trip $n", sort = n)
                            store.put(t)
                            store.setDevice { it.copy(currentTripId = t.id) }
                        },
                    )
                }
            }
            MdScanAction(scanning, "Scan labels as they go on") { scanning = !scanning }
            if (scanning) MdScanner(onScan)
            result?.let { MdBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }

            GlassTabRow(tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("To load (${waiting.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("On ${trip?.name ?: "this trip"} (${onTrip.size})") })
            }

            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                if (tab == 0) {
                    if (waiting.isEmpty()) item(key = "empty") { EmptyState(Icons.Default.CheckCircle, "Everything is on the $vehicle") }
                    groups.forEach { (room, list) ->
                        item(key = "h" + (room?.id ?: "none")) {
                            MdGroupHeader(room?.name ?: "No room", list.size) {
                                MdRowButton(
                                    "Load all",
                                    onClick = {
                                        mdSetStageAll(store, list, Stage.LOADED, mdEnsureTrip(store))
                                        result = MdResult(MdTone.GOOD, "Loaded ${list.size} from ${room?.name ?: "no room"}")
                                    },
                                    forward = false,
                                )
                            }
                        }
                        items(list, key = { "m" + it.id }) { m ->
                            MdThingRow(m, open = m.id in openBoxes, carried = carried[m.id] ?: 0) {
                                MdRowButton("Load", onClick = { result = mdLoadOne(store, m) })
                            }
                        }
                    }
                    if (withMe.isNotEmpty()) {
                        item(key = "hwithme") { MdGroupHeader("Travels with me, not in the $vehicle", withMe.size) }
                        items(withMe, key = { "w" + it.id }) { m ->
                            MdThingRow(m, open = m.id in openBoxes, carried = carried[m.id] ?: 0) {
                                MdRowButton(
                                    "With me",
                                    onClick = {
                                        mdSetStage(store, m, Stage.LOADED, null)
                                        result = MdResult(MdTone.GOOD, "With you: ${m.title}", "Not in the $vehicle")
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
                            MdRowButton(
                                "Undo",
                                onClick = {
                                    mdSetStage(store, m, Stage.AT_ORIGIN, null)
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
