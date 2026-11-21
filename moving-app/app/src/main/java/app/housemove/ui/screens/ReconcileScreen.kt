package app.housemove.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.housemove.data.Ratio
import app.housemove.data.Stage
import app.housemove.data.movables
import app.housemove.ui.Hint
import app.housemove.ui.LocalStore
import app.housemove.ui.MovableRow
import app.housemove.ui.ProgressRow
import app.housemove.ui.Screen
import app.housemove.ui.Section
import app.housemove.ui.appData

/** Before the van goes back: is everything that left the old home now in the new one? */
@Composable
fun ReconcileScreen() {
    val store = LocalStore.current
    val data = appData()
    val all = data.movables()
    val onVan = all.filter { it.stage == Stage.LOADED }.sortedBy { it.title }
    val neverLoaded = all.filter { it.stage == Stage.AT_ORIGIN && !it.travelsWithMe }.sortedBy { it.title }
    val withMe = all.filter { it.stage == Stage.AT_ORIGIN && it.travelsWithMe }.sortedBy { it.title }
    val damaged = all.filter { it.damaged }.sortedBy { it.title }
    val arrived = all.count { mdArrived(it.stage) }
    val allIn = onVan.isEmpty() && neverLoaded.isEmpty() && arrived > 0
    val trips = data.liveTrips
    val tripIds = trips.map { it.id }.toSet()
    val left = all.filter { it.stage != Stage.AT_ORIGIN }
    val noTrip = left.filter { it.tripId == null || it.tripId !in tripIds }

    Screen("Reconcile") {
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "status") {
                when {
                    all.isEmpty() -> Text(
                        "Nothing to check yet. Boxes you pack and items marked Move appear here.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    allIn -> MdBanner(
                        MdResult(MdTone.GOOD, "Everything is accounted for", "$arrived delivered. Nothing left on the van or at the old home.")
                    )
                    else -> MdBanner(
                        MdResult(
                            if (onVan.isNotEmpty() && arrived > 0) MdTone.BAD else MdTone.WARN,
                            "Not everything is in yet",
                            "${onVan.size} still on the van · ${neverLoaded.size} still at the old home",
                        )
                    )
                }
            }
            item(key = "counts") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Hint("Check this before the van goes back.")
                    Section("Overall") {
                        ProgressRow("Left the old home", Ratio(left.size, all.size))
                        ProgressRow("Delivered", Ratio(arrived, all.size))
                    }
                    Section("By trip") {
                        if (trips.isEmpty() && noTrip.isEmpty()) Hint("No trips recorded yet.")
                        trips.forEach { t ->
                            val onTrip = left.filter { it.tripId == t.id }
                            ProgressRow("${t.name}: delivered", Ratio(onTrip.count { mdArrived(it.stage) }, onTrip.size))
                        }
                        if (noTrip.isNotEmpty()) {
                            ProgressRow("With you / no trip: delivered", Ratio(noTrip.count { mdArrived(it.stage) }, noTrip.size))
                        }
                    }
                }
            }

            item(key = "hvan") { MdGroupHeader("Still on the van", onVan.size) }
            items(onVan, key = { "v" + it.id }) { m ->
                MovableRow(m, trailing = {
                    FilledTonalButton(onClick = { mdSetStage(store, m, Stage.DELIVERED) }) { Text("Delivered") }
                })
            }

            item(key = "hnever") { MdGroupHeader("Never loaded — still at the old home", neverLoaded.size) }
            items(neverLoaded, key = { "n" + it.id }) { m -> MovableRow(m) }

            if (withMe.isNotEmpty()) {
                item(key = "hwithme") { MdGroupHeader("Travelling with you — not marked as arrived", withMe.size) }
                items(withMe, key = { "w" + it.id }) { m ->
                    MovableRow(m, trailing = {
                        FilledTonalButton(onClick = { mdSetStage(store, m, Stage.DELIVERED) }) { Text("Arrived") }
                    })
                }
            }

            item(key = "hdamaged") { MdGroupHeader("Damaged", damaged.size) }
            items(damaged, key = { "d" + it.id }) { m -> MovableRow(m) }
            if (damaged.isNotEmpty()) item(key = "damagehint") {
                Hint("Open each one to add photos and a note. The damage report is under More → Reports.", Modifier.padding(16.dp))
            }
        }
    }
}
