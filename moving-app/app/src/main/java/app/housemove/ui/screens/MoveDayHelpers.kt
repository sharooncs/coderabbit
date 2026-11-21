package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.housemove.data.Movable
import app.housemove.data.Stage
import app.housemove.data.Store
import app.housemove.data.Trip
import app.housemove.data.newId

// Shared by the move-day screens (load, unload, reconcile, sweep, unpack).

/** Colour of a result banner: green = done, amber = nothing to do, red = problem. */
internal enum class MdTone(val bg: Color, val fg: Color) {
    GOOD(Color(0xFF2E7D32), Color.White),
    WARN(Color(0xFFF9A825), Color.Black),
    BAD(Color(0xFFC62828), Color.White),
}

internal data class MdResult(val tone: MdTone, val title: String, val detail: String = "")

/** True once something has reached the new home. */
internal fun mdArrived(stage: Stage) = stage == Stage.DELIVERED || stage == Stage.DONE

/** Move one box or item to [stage]. The trip is kept unless [tripId] is given. */
internal fun mdSetStage(store: Store, m: Movable, stage: Stage, tripId: String? = m.tripId) {
    if (m.isBox) store.updateBoxes(listOf(m.id)) { it.copy(stage = stage, tripId = tripId) }
    else store.updateItems(listOf(m.id)) { it.copy(stage = stage, tripId = tripId) }
}

/** Move many boxes and items to [stage] on [tripId] in as few writes as possible. */
internal fun mdSetStageAll(store: Store, list: List<Movable>, stage: Stage, tripId: String?) {
    val boxes = list.filter { it.isBox }.map { it.id }
    val items = list.filter { !it.isBox }.map { it.id }
    if (boxes.isNotEmpty()) store.updateBoxes(boxes) { it.copy(stage = stage, tripId = tripId) }
    if (items.isNotEmpty()) store.updateItems(items) { it.copy(stage = stage, tripId = tripId) }
}

internal fun mdSetDamage(store: Store, m: Movable, damaged: Boolean, note: String) {
    if (m.isBox) store.updateBoxes(listOf(m.id)) { it.copy(damaged = damaged, damageNote = note) }
    else store.updateItems(listOf(m.id)) { it.copy(damaged = damaged, damageNote = note) }
}

/**
 * The trip things are being loaded onto: this phone's current trip if it still
 * exists, otherwise the first trip, otherwise a new "Trip 1".
 */
internal fun mdEnsureTrip(store: Store): String {
    val data = store.data.value
    data.trip(store.device.value.currentTripId)?.let { return it.id }
    val trip = data.liveTrips.firstOrNull() ?: Trip(id = newId(), name = "Trip 1", sort = 1).also { store.put(it) }
    store.setDevice { it.copy(currentTripId = trip.id) }
    return trip.id
}

/** Large coloured strip that says what just happened. Readable at arm's length. */
@Composable
internal fun MdBanner(result: MdResult, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(result.tone.bg).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(result.title, color = result.tone.fg, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (result.detail.isNotBlank()) {
            Text(result.detail, color = result.tone.fg, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Grey heading above a group of rows, with an optional button on the right. */
@Composable
internal fun MdGroupHeader(title: String, count: Int, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$title ($count)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}
