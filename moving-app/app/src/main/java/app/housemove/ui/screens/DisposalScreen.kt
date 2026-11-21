package app.housemove.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

private fun money(currency: String, amount: Double): String = (currency.trim() + " " + fmtNum(amount)).trim()

/** Tracker for everything that is leaving before the move: to sell, to donate, to bin. */
@Composable
fun DisposalScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val data = appData()
    val now = today()
    val currency = data.move.currency

    val groups = listOf(
        Disposition.SELL to "To sell",
        Disposition.DONATE to "To donate",
        Disposition.BIN to "To bin or recycle",
    ).map { (d, title) ->
        Triple(d, title, data.liveItems.filter { it.disposition == d }.sortedWith(compareBy({ it.gone }, { it.deadlineDay ?: Long.MAX_VALUE }, { it.name.lowercase() })))
    }
    val everything = groups.flatMap { it.third }
    val selling = groups.first().third

    fun edit(itemId: String, f: (Item) -> Item) {
        store.data.value.item(itemId)?.let { store.put(f(it)) }
    }

    Screen("Sell, donate, bin") {
        if (everything.isEmpty()) {
            ScrollColumn {
                EmptyState(
                    Icons.Default.Sell, "Nothing is leaving yet",
                    text = "Things you mark Sell, Donate or Bin are tracked here until they are gone.",
                    action = if (data.uses(Part.TRIAGE)) "Decide what to keep" else null,
                    onAction = { nav.open("triage") },
                )
            }
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "totals") {
                val gone = everything.count { it.gone }
                val late = everything.count { !it.gone && (it.deadlineDay ?: Long.MAX_VALUE) < now }
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), shadow = 5.dp).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Ring(Ratio(gone, everything.size), size = 64.dp, color = MaterialTheme.colorScheme.tertiary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$gone of ${everything.size} gone", style = MaterialTheme.typography.titleMedium)
                        if (selling.isNotEmpty()) {
                            Text(
                                "Asking " + money(currency, selling.sumOf { it.price ?: 0.0 }) +
                                    " · sold " + money(currency, selling.filter { it.gone }.sumOf { it.price ?: 0.0 }),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (late > 0) IconFlag(Icons.Default.Schedule, "$late past ${if (late == 1) "its" else "their"} date", error = true)
                    }
                }
            }
            groups.forEach { (d, title, list) ->
                if (list.isNotEmpty()) {
                    item(key = "head-" + d.name) {
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(d.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text(
                                "${list.count { it.gone }} of ${list.size} gone",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(list, key = { it.id }) { x ->
                        DisposalRow(
                            x = x,
                            selling = d == Disposition.SELL,
                            currency = currency,
                            now = now,
                            onOpen = { nav.open("item/${x.id}") },
                            onListed = { v -> edit(x.id) { it.copy(listed = v) } },
                            onGone = { v -> edit(x.id) { it.copy(gone = v) } },
                        )
                    }
                }
            }
            item(key = "tip") {
                Hint("Still unsold a week before the move? Donate it instead.", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun DisposalRow(
    x: Item,
    selling: Boolean,
    currency: String,
    now: Long,
    onOpen: () -> Unit,
    onListed: (Boolean) -> Unit,
    onGone: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val deadline = x.deadlineDay
    val late = !x.gone && deadline != null && deadline < now
    val price = x.price
    Row(
        Modifier.fillMaxWidth()
            .glass(RoundedCornerShape(18.dp), shadow = 5.dp)
            .then(if (late) Modifier.border(1.5.dp, scheme.error, RoundedCornerShape(18.dp)) else Modifier)
            .clickable(onClick = onOpen)
            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Something that has gone fades back, so what is still here stands out.
        OwnerThumb(x.id, Modifier.size(64.dp).alpha(if (x.gone) 0.45f else 1f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                x.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (x.gone) scheme.onSurfaceVariant else scheme.onSurface,
            )
            if (selling) {
                Text(
                    if (price != null) money(currency, price) else "No price yet",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (price != null) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (price != null) scheme.onSurface else scheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (late) IconFlag(Icons.Default.Schedule, "Late", error = true)
                Text(
                    if (deadline == null) "No date" else "By ${fmtDayShort(deadline)} (${relDay(deadline, now)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (late) scheme.error else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selling) StateButton(Icons.Default.Campaign, "Listed", x.listed, "Listed for sale: ${x.title()}", scheme.primary, scheme.onPrimary, onListed)
        StateButton(
            if (x.gone) Icons.Default.Check else Icons.Default.RadioButtonUnchecked, "Gone", x.gone, "Gone: ${x.title()}",
            scheme.tertiary, scheme.onTertiary, onGone,
        )
    }
}

/** A round on/off button with a picture and one word under it, for the end of a row. */
@Composable
private fun StateButton(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    description: String,
    onColor: androidx.compose.ui.graphics.Color,
    onContent: androidx.compose.ui.graphics.Color,
    onChange: (Boolean) -> Unit,
) {
    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconToggleButton(
            checked = checked,
            onCheckedChange = onChange,
            colors = IconButtonDefaults.filledIconToggleButtonColors(checkedContainerColor = onColor, checkedContentColor = onContent),
        ) { Icon(icon, contentDescription = description) }
        Text(
            label, style = MaterialTheme.typography.labelSmall,
            color = if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
