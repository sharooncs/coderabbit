package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import app.housemove.ui.icons.filled.Undo
import app.housemove.ui.icons.filled.Warehouse
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.FactCheck
import app.housemove.ui.icons.filled.Lightbulb
import app.housemove.ui.icons.filled.LocalShipping
import app.housemove.ui.icons.filled.PhotoCamera
import app.housemove.ui.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.housemove.data.AppData
import app.housemove.data.Home
import app.housemove.data.Part
import app.housemove.data.Ratio
import app.housemove.data.Stage
import app.housemove.data.fmtDay
import app.housemove.data.movables
import app.housemove.data.progress
import app.housemove.data.relDay
import app.housemove.data.today
import app.housemove.data.uses
import app.housemove.data.vehicle
import app.housemove.ui.EmptyState
import app.housemove.ui.Fold
import app.housemove.ui.JOURNEY
import app.housemove.ui.JourneyStep
import app.housemove.ui.LocalNav
import app.housemove.ui.Pic
import app.housemove.ui.Screen
import app.housemove.ui.ScrollColumn
import app.housemove.ui.StepRow
import app.housemove.ui.TopActions
import app.housemove.ui.appData
import app.housemove.ui.deviceInfo
import app.housemove.ui.open
import app.housemove.data.*
import app.housemove.ui.*

/** The steps of the day, in order: everything in the Move day phase, then Unpack. */
private fun mdSteps(data: AppData): List<JourneyStep> =
    (JOURNEY[4].steps + JOURNEY[5].steps.first()).filter { it.shownFor(data) }

/** How far along a step is, for the steps that count something. */
private fun mdStepRatio(route: String, data: AppData): Ratio? = when (route) {
    "load" -> data.progress().loaded
    "sweep" -> data.roomsOf(Home.ORIGIN).let { rooms -> Ratio(rooms.count { it.swept }, rooms.size) }
    "unload" -> data.progress().delivered
    "unpack" -> data.progress().unpacked
    else -> null
}

/**
 * Reconcile is finished once everything that went in the van has arrived and nothing was
 * left behind, as Reconcile itself counts it: what travels with the person does not hold it up.
 */
private fun mdAllIn(data: AppData): Boolean {
    val all = data.movables()
    // What is at the storage unit is accounted for too: in storage, neither missing nor delivered.
    return all.isNotEmpty() && all.all { mdArrived(it.stage) || it.stage == Stage.STORED || it.carriedByHand() } &&
        all.any { mdArrived(it.stage) || it.stage == Stage.STORED }
}

/**
 * The step to do now, which gets the big button. Before anything moves it is simply the first
 * step not finished. Once things are moving, where they are decides: with everything on the
 * van it is the sweep and the new home's record, then unloading; with some of it delivered it
 * is getting the rest off; with more still at the old home it is the next load. So a step
 * that was skipped, such as the photos of the old home, is not asked for all day.
 */
fun mdCurrentStep(data: AppData, routes: List<String>, finished: List<Boolean>, now: Long = today()): Int {
    val firstOpen = finished.indexOf(false)
    if (firstOpen < 0) return -1
    fun open(vararg wanted: String): Int? = wanted.firstNotNullOfOrNull { r -> routes.indexOf(r).takeIf { it >= 0 && !finished[it] } }
    val all = data.movables()
    // What travels with someone is neither left at the old home nor on the van (the Store's carriedByHand and onTheVan).
    val atOld = all.count { it.stage == Stage.AT_ORIGIN && !it.carriedByHand() }
    val onVan = all.count { it.onTheVan() }
    val arrived = all.count { mdArrived(it.stage) }
    val moveDay = data.move.moveDay
    return when {
        onVan > 0 && arrived > 0 -> open("unload")
        onVan > 0 && atOld == 0 -> open("sweep", "records/DEST", "unload")
        onVan > 0 -> open("load")
        atOld > 0 && (arrived > 0 || (moveDay != null && moveDay <= now)) -> open("load")
        arrived > 0 -> open("reconcile", "unpack")
        else -> null
    } ?: firstOpen
}

/** Move-day hub: the steps of the day in order, each with a live count, and the one to do now as the big button. */
@Composable
fun MoveDayScreen() {
    val data = appData()
    val device = deviceInfo()
    val nav = LocalNav.current
    val steps = mdSteps(data)
    val finished = steps.map { step ->
        val ratio = mdStepRatio(step.route, data)
        step.done(data) || (ratio != null && ratio.total > 0 && ratio.done >= ratio.total) ||
            (step.route == "reconcile" && mdAllIn(data))
    }
    val current = mdCurrentStep(data, steps.map { it.route }, finished)

    Screen("Move day", back = false, tab = "moveday", actions = { TopActions() }) {
        ScrollColumn {
            if (steps.isEmpty()) {
                EmptyState(
                    Icons.Default.LocalShipping, "Nothing to do here",
                    text = "Loading, home records and unpacking are switched off for this move.",
                )
            } else {
                // The date and how much of the day is done. With no date yet, tapping it sets one, as on Home.
                val day = data.move.moveDay
                val stepsDone = finished.count { it }
                ProgressCard(
                    Ratio(stepsDone, steps.size),
                    if (day == null) "No move day set" else fmtDay(day),
                    Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = day == null) { nav.open("settings") }.padding(4.dp),
                    sub = listOf(
                        if (day == null) "Tap to set the date" else relDay(day).replaceFirstChar { it.uppercase() },
                        "$stepsDone of ${steps.size} steps done",
                    ).joinToString(" · "),
                )

                // The steps in order on one glass panel. The one to do now is the big button, in its place in the order.
                GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), sheen = true) {
                    Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        steps.forEachIndexed { i, step ->
                            if (i == current) {
                                MdMainAction(
                                    step.icon, step.title(data), onClick = { nav.open(step.route) },
                                    Modifier.padding(horizontal = 2.dp, vertical = 6.dp), sub = step.status(data, device),
                                )
                            } else {
                                StepRow(
                                    step.icon, step.title(data), onClick = { nav.open(step.route) },
                                    ratio = mdStepRatio(step.route, data),
                                    status = step.status(data, device),
                                    done = finished[i],
                                )
                            }
                        }
                    }
                }

                StorageTrips()

                val vehicle = data.vehicle()
                val tips = listOfNotNull(
                    Icons.Default.Sync to "Sync all phones the evening before, so everyone has the same list.",
                    (Icons.Default.PhotoCamera to "At the new home, photograph every room and read the meters before anything is carried in.")
                        .takeIf { data.uses(Part.RECORDS) },
                    (Pic.FirstNight to "First-night boxes go on the $vehicle last, so they come off first.")
                        .takeIf { data.uses(Part.MOVE_DAY) && data.uses(Part.BOXES) },
                    (Icons.Default.FactCheck to "Check Reconcile before the $vehicle goes back.").takeIf { data.uses(Part.MOVE_DAY) },
                )
                Fold("Before you start", Icons.Default.Lightbulb, summary = countOf(tips.size, "tip") + " for the day") {
                    tips.forEach { (icon, text) -> MdTip(icon, text) }
                }
            }
        }
    }
}

@Composable
private fun MdTip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The storage unit on Move day, where the move uses one: how many things are in it, a trip to it for what is
 * still at the old home, and "Bring back from storage" to the new home once anything is there.
 */
@Composable
private fun StorageTrips() {
    val data = appData()
    val store = LocalStore.current
    val nav = LocalNav.current
    if (!data.offersStorage() || !data.uses(Part.MOVE_DAY)) return
    val stored = data.inStorage()
    val atOld = data.movables().count { it.stage == Stage.AT_ORIGIN && !it.carriedByHand() }
    val going = data.liveItems.count { it.disposition == Disposition.STORE && data.carrierOf(it.toMovable()).stage == Stage.AT_ORIGIN }
    Section(Place.STORAGE.label) {
        Text(
            listOfNotNull(
                if (stored.isEmpty()) "Nothing in storage yet" else countOf(stored.size, "thing") + " in storage",
                if (going > 0) "$going decided Store, still at the old home" else null,
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (atOld > 0) RowButton("Trip to storage", onClick = { mdStorageTrip(store, fromStorage = false); nav.open("load") }, forward = false, icon = Icons.Default.Warehouse)
            if (stored.isNotEmpty()) RowButton(BRING_BACK_TRIP_NAME, onClick = { mdStorageTrip(store, fromStorage = true); nav.open("load") }, icon = Icons.Default.Undo)
        }
    }
}
