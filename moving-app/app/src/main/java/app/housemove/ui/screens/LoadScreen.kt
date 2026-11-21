package app.housemove.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.housemove.data.Ratio
import app.housemove.data.Room
import app.housemove.data.Stage
import app.housemove.data.Store
import app.housemove.data.Trip
import app.housemove.data.findByLabel
import app.housemove.data.labelFromQr
import app.housemove.data.movables
import app.housemove.data.newId
import app.housemove.ui.EmptyHint
import app.housemove.ui.Flag
import app.housemove.ui.Hint
import app.housemove.ui.LocalStore
import app.housemove.ui.MovableRow
import app.housemove.ui.ProgressRow
import app.housemove.ui.QrScanner
import app.housemove.ui.Screen
import app.housemove.ui.appData
import app.housemove.ui.deviceInfo

/** Put one thing on the current trip and describe what happened. */
private fun mdLoadOne(store: Store, m: Movable): MdResult {
    val tripId = mdEnsureTrip(store)
    mdSetStage(store, m, Stage.LOADED, tripId)
    val now = store.data.value
    val room = now.room(m.destRoomId)?.name ?: "no room yet"
    val open = m.isBox && now.box(m.id)?.sealed == false
    val trip = now.trip(tripId)?.name ?: ""
    val detail = listOf("→ $room", trip, if (open) "this box is still open" else "").filter { it.isNotBlank() }
    return MdResult(MdTone.GOOD, "Loaded: ${m.title}", detail.joinToString(" · "))
}

/** Load-out: scan or tick everything as it goes on the van, trip by trip. */
@Composable
fun LoadScreen() {
    val store = LocalStore.current
    val data = appData()
    val device = deviceInfo()
    var scanning by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var result by remember { mutableStateOf<MdResult?>(null) }

    val trips = data.liveTrips
    val trip = data.trip(device.currentTripId) ?: trips.firstOrNull()
    val all = data.movables()
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

    val onScan: (String) -> Unit = { text ->
        val code = labelFromQr(text)
        val m = store.data.value.findByLabel(code)
        result = when {
            m == null -> MdResult(MdTone.BAD, "Unknown label $code", "Nothing has this label yet. Pack it as a box first, or check the code.")
            m.stage == Stage.AT_ORIGIN -> mdLoadOne(store, m)
            m.stage == Stage.LOADED -> MdResult(MdTone.WARN, "Already on the van", m.title)
            else -> MdResult(MdTone.WARN, "Already delivered", m.title)
        }
    }

    Screen("Load the van") {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trips.forEach { t ->
                FilterChip(
                    selected = t.id == trip?.id,
                    onClick = { store.setDevice { it.copy(currentTripId = t.id) } },
                    label = { Text(t.name) },
                )
            }
            AssistChip(
                onClick = {
                    val n = trips.size + 1
                    val t = Trip(id = newId(), name = "Trip $n", sort = n)
                    store.put(t)
                    store.setDevice { it.copy(currentTripId = t.id) }
                },
                label = { Text("Add trip") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
        FilledTonalButton(
            onClick = { scanning = !scanning },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 52.dp),
        ) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (scanning) "Hide the scanner" else "Scan labels as they go on")
        }
        if (scanning) QrScanner(onText = onScan, modifier = Modifier.fillMaxWidth().height(240.dp))
        result?.let { MdBanner(it) }

        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("To load (${waiting.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("On ${trip?.name ?: "this trip"} (${onTrip.size})") })
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                item(key = "progress") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProgressRow("Loaded", Ratio(loadedCount, all.size))
                        Hint("First-night boxes go on last so they come off first.")
                    }
                }
                if (waiting.isEmpty()) item(key = "empty") {
                    EmptyHint(
                        if (all.isEmpty()) "Nothing to load yet. Boxes you pack and items marked Move appear here."
                        else "Everything is on the van."
                    )
                }
                groups.forEach { (room, list) ->
                    item(key = "h" + (room?.id ?: "none")) {
                        MdGroupHeader(room?.name ?: "No room", list.size) {
                            TextButton(onClick = {
                                mdSetStageAll(store, list, Stage.LOADED, mdEnsureTrip(store))
                                result = MdResult(MdTone.GOOD, "Loaded ${list.size} from ${room?.name ?: "no room"}")
                            }) { Text("Load all") }
                        }
                    }
                    items(list, key = { "m" + it.id }) { m ->
                        MovableRow(m, trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (m.id in openBoxes) {
                                    Flag("Open")
                                    Spacer(Modifier.width(8.dp))
                                }
                                FilledTonalButton(onClick = { result = mdLoadOne(store, m) }) { Text("Load") }
                            }
                        })
                    }
                }
                if (withMe.isNotEmpty()) {
                    item(key = "hwithme") { MdGroupHeader("Travels with me (not in the van)", withMe.size) }
                    items(withMe, key = { "w" + it.id }) { m ->
                        MovableRow(m, trailing = {
                            FilledTonalButton(onClick = {
                                mdSetStage(store, m, Stage.LOADED, null)
                                result = MdResult(MdTone.GOOD, "With you: ${m.title}", "Not in the van")
                            }) { Text("With me") }
                        })
                    }
                }
            } else {
                item(key = "triphint") {
                    Hint(
                        if (trip == null) "No trip yet. It is created when you load the first thing."
                        else "Everything loaded on ${trip.name}. Tap Undo if something came back off.",
                        Modifier.padding(16.dp),
                    )
                }
                if (onTrip.isEmpty() && trip != null) item(key = "tripempty") { EmptyHint("Nothing is on ${trip.name} yet.") }
                items(onTrip, key = { "t" + it.id }) { m ->
                    MovableRow(m, trailing = {
                        TextButton(onClick = {
                            mdSetStage(store, m, Stage.AT_ORIGIN, null)
                            result = MdResult(MdTone.WARN, "Taken off: ${m.title}")
                        }) { Text("Undo") }
                    })
                }
            }
        }
    }
}
