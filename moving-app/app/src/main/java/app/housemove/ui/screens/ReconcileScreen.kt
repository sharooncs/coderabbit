package app.housemove.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.housemove.data.Ratio
import app.housemove.data.Stage
import app.housemove.data.movables
import app.housemove.data.vehicle
import app.housemove.ui.*

/** Before the van or truck goes back: is everything that left the old home now in the new one? */
@Composable
fun ReconcileScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val vehicle = data.vehicle()
    // Each thing once: a container stands for everything packed inside it.
    val all = data.movables()
    val carried = remember(data) { mdCarried(data) }
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
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
            item(key = "status") {
                val banner = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                when {
                    all.isEmpty() -> EmptyState(
                        Icons.Default.FactCheck, "Nothing to check yet",
                        text = "Boxes you pack and items marked Move appear here.",
                    )
                    allIn -> MdBanner(
                        MdResult(MdTone.GOOD, "Everything is accounted for", "$arrived delivered. Nothing left on the $vehicle or at the old home."),
                        banner,
                    )
                    else -> MdBanner(
                        MdResult(
                            if (onVan.isNotEmpty() && arrived > 0) MdTone.BAD else MdTone.WARN,
                            "Not everything is in yet",
                            "${onVan.size} still on the $vehicle · ${neverLoaded.size} still at the old home",
                        ),
                        banner,
                    )
                }
            }
            // With things still on the van, getting them off is the thing to do.
            if (onVan.isNotEmpty()) item(key = "unload") {
                MdMainAction(
                    Icons.Default.Download, "Unload the $vehicle", onClick = { nav.open("unload") },
                    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (all.isNotEmpty()) item(key = "counts") {
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), sheen = true) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            MdRingStat(Ratio(left.size, all.size), "Left the old home", 76.dp, Modifier.weight(1f))
                            MdRingStat(Ratio(arrived, all.size), "Delivered", 76.dp, Modifier.weight(1f))
                        }
                        if (trips.isNotEmpty() || noTrip.isNotEmpty()) {
                            HorizontalDivider(color = Night.Hairline)
                            Text("Delivered, trip by trip", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                trips.forEach { t ->
                                    val onTrip = left.filter { it.tripId == t.id }
                                    MdRingStat(Ratio(onTrip.count { mdArrived(it.stage) }, onTrip.size), t.name, 56.dp, Modifier.widthIn(min = 72.dp, max = 96.dp))
                                }
                                if (noTrip.isNotEmpty()) {
                                    MdRingStat(Ratio(noTrip.count { mdArrived(it.stage) }, noTrip.size), "With you", 56.dp, Modifier.widthIn(min = 72.dp, max = 96.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (onVan.isNotEmpty()) {
                item(key = "hvan") { MdGroupHeader("Still on the $vehicle", onVan.size) }
                items(onVan, key = { "v" + it.id }) { m ->
                    MdThingRow(m, carried = carried[m.id] ?: 0) {
                        MdRowButton("Delivered", onClick = { mdSetStage(store, m, Stage.DELIVERED) })
                    }
                }
            }

            if (neverLoaded.isNotEmpty()) {
                item(key = "hnever") { MdGroupHeader("Never loaded, still at the old home", neverLoaded.size) }
                items(neverLoaded, key = { "n" + it.id }) { m -> MdThingRow(m, carried = carried[m.id] ?: 0) }
            }

            if (withMe.isNotEmpty()) {
                item(key = "hwithme") { MdGroupHeader("Travelling with you, not arrived yet", withMe.size) }
                items(withMe, key = { "w" + it.id }) { m ->
                    MdThingRow(m, carried = carried[m.id] ?: 0) {
                        MdRowButton("Arrived", onClick = { mdSetStage(store, m, Stage.DELIVERED) })
                    }
                }
            }

            if (damaged.isNotEmpty()) {
                item(key = "hdamaged") {
                    MdGroupHeader("Damaged", damaged.size) {
                        MdRowButton("Damage report", onClick = { nav.open("reports") }, forward = false)
                    }
                }
                items(damaged, key = { "d" + it.id }) { m ->
                    val note = (if (m.isBox) data.box(m.id)?.damageNote else data.item(m.id)?.damageNote).orEmpty()
                    MdThingRow(m, showStage = true, note = note, carried = carried[m.id] ?: 0)
                }
            }
        }
    }
}

/** A count as a ring with "done/total" in the middle and what it counts underneath. */
@Composable
private fun MdRingStat(ratio: Ratio, label: String, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val done = ratio.total > 0 && ratio.done >= ratio.total
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Ring(ratio, size = size, stroke = if (size > 60.dp) 7.dp else 5.dp, color = if (done) scheme.tertiary else scheme.primary) {
            Text(
                "${ratio.done}/${ratio.total}",
                style = if (size > 60.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}
