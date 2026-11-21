package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (everything.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Section("Nothing is leaving yet") {
                            Text("Items you mark as Sell, Donate or Bin show up here, so you can track them until they are actually gone.")
                            Button(onClick = { nav.open("triage") }) { Text("Decide in Triage") }
                        }
                    }
                }
            } else {
                item(key = "totals") {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
                        Section("Summary") {
                            ProgressRow("Gone", Ratio(everything.count { it.gone }, everything.size))
                            if (selling.isNotEmpty()) {
                                Row {
                                    Text("Asking total", modifier = Modifier.weight(1f))
                                    Text(money(currency, selling.sumOf { it.price ?: 0.0 }), style = MaterialTheme.typography.titleMedium)
                                }
                                Row {
                                    Text("Sold so far", modifier = Modifier.weight(1f))
                                    Text(money(currency, selling.filter { it.gone }.sumOf { it.price ?: 0.0 }), style = MaterialTheme.typography.titleMedium)
                                }
                            }
                            val late = everything.count { !it.gone && (it.deadlineDay ?: Long.MAX_VALUE) < now }
                            if (late > 0) {
                                Text(
                                    "$late past ${if (late == 1) "its" else "their"} date and still here.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
                groups.forEach { (d, title, list) ->
                    if (list.isNotEmpty()) {
                        item(key = "head-" + d.name) {
                            Text(
                                "$title (${list.count { it.gone }} of ${list.size} gone)",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                            )
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
                            HorizontalDivider(Modifier.padding(start = 76.dp))
                        }
                    }
                }
                item(key = "tip") {
                    Hint(
                        "Set a date on each item (open it to add one). Anything unsold a week before the move is usually better donated than carried.",
                        Modifier.padding(16.dp),
                    )
                }
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
    val deadline = x.deadlineDay
    val late = !x.gone && deadline != null && deadline < now
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OwnerThumb(x.id, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(x.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val price = x.price
            if (selling) {
                Text(
                    if (price != null) money(currency, price) else "No price yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (deadline == null) "No date set" else "By ${fmtDayShort(deadline)} (${relDay(deadline, now)})",
                style = MaterialTheme.typography.bodySmall,
                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selling) TickBox("Listed", x.listed, onListed)
        TickBox("Gone", x.gone, onGone)
    }
}

/** A small labelled checkbox stacked vertically, for the end of a row. */
@Composable
private fun TickBox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(
        Modifier.clickable { onChange(!checked) }.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
