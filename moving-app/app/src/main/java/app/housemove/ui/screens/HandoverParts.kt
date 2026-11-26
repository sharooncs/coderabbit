package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/*
 * Handing a sold or donated thing over to the buyer or the charity (design 2.1, section 2).
 *
 * An Organiser, or a Decider whose suggestion was approved, arranges it: who hands it over (a Helper, or
 * anyone), whether the buyer has paid, and what to collect when not. Every Helper sees "Paid" or "Not paid";
 * only the one handing it over sees the amount (the other phones are not sent it). Anyone in the move taps
 * "Handed over" when it has gone.
 */

/** The words for a hand-over's money: "Paid", "Not paid · collect £80", "Not paid", or null for a donation. */
internal fun AppData.handoverMoney(itemId: String): String? {
    val paid = paidLabel(itemId) ?: return null
    val amount = handoverOf(itemId)?.amount
    return if (amount != null) "$paid · collect ${money(move.currency, amount)}" else paid
}

/** Who hands it over, in words: "Tom hands it over", "You hand it over", "Anyone can hand it over". */
internal fun AppData.handoverWho(itemId: String, me: Person?): String {
    val to = item(itemId)?.handoverTo ?: return "Anyone can hand it over"
    return if (to == me?.id) "You hand it over" else "${personName(to).ifBlank { "Someone" }} hands it over"
}

/**
 * The hand-over of an item decided to be sold or donated, on its own page: arranged by whoever may, seen by
 * everyone, and "Handed over" for anyone in the move.
 */
@Composable
internal fun HandoverCard(item: Item, modifier: Modifier = Modifier) {
    if (!item.forHandover()) return
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val me = store.me
    val mayArrange = data.mayArrangeHandover(me, item) && !item.handedOver
    val selling = item.disposition == Disposition.SELL
    val currency = data.move.currency
    var arranging by rememberSaveable(item.id) { mutableStateOf(false) }

    GlassCard(modifier.fillMaxWidth(), tone = if (item.handedOver) GlassTone.Mint else GlassTone.Plain, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Handshake, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (item.handedOver) "Handed over" else "Hand-over", style = MaterialTheme.typography.titleMedium)
                    Text(data.handoverWho(item.id, me), style = MaterialTheme.typography.bodySmall, color = Night.TextSoft)
                }
                data.handoverMoney(item.id)?.let { m ->
                    GlassChip(m, icon = if (item.paid) Icons.Default.CheckCircle else Icons.Default.Payments, tone = if (item.paid) GlassTone.Mint else GlassTone.Amber)
                }
            }
            if (mayArrange && arranging) {
                // Who: the Helpers first, then everyone else in the move; tapping the chosen one again leaves it to anyone.
                Text("Who hands it over", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                val people = data.livePeople.filter { !it.removed }.sortedWith(compareBy({ it.role != Role.HELPER }, { it.shownName() }))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    people.forEach { p ->
                        GlassChip(
                            if (p.id == me?.id) "Me" else p.shownName(), icon = p.role.icon(), selected = item.handoverTo == p.id,
                            onClick = { store.arrangeHandover(item.id, to = if (item.handoverTo == p.id) null else p.id).okOrSay(context) },
                        )
                    }
                }
                if (selling) {
                    Text("Has the buyer paid?", style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                    ChipPicker(listOf(true, false), item.paid, label = { if (it) "Paid" else "Not paid" }, onPick = { v -> store.setPaid(item.id, v).okOrSay(context) })
                    if (!item.paid) {
                        AmountPicker(
                            data.handoverOf(item.id)?.amount,
                            { v -> store.arrangeHandover(item.id, paid = false, amount = v).okOrSay(context) },
                            "To collect", currency, presets = soldPresets(data.hiddenOf(item.id)?.price ?: item.price),
                        )
                    }
                }
                RowButton("Done", onClick = { arranging = false }, forward = false, icon = Icons.Default.Check)
            }
            // The two things to do, side by side: arranging it (for whoever may) and saying it has gone.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (data.mayHandOver(me, item)) {
                    if (item.handedOver) {
                        RowButton("Not handed over yet", onClick = { store.handOver(item.id, false).okOrSay(context) }, forward = false, icon = Icons.Default.Undo)
                    } else {
                        RowButton("Handed over", onClick = { store.handOver(item.id).okOrSay(context) }, icon = Icons.Default.Handshake, look = RowButtonLook.Primary)
                    }
                }
                if (mayArrange && !arranging) RowButton("Arrange", onClick = { arranging = true }, forward = false, icon = Icons.Default.Edit)
            }
        }
    }
}

/**
 * One hand-over in a list (Home, the list of things going): its photo, its name, who hands it over and the
 * money as this phone may show it, and "Handed over".
 */
@Composable
internal fun HandoverRow(item: Item, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val me = store.me
    GlassCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        FlowRow(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).widthIn(min = 180.dp).clip(RoundedCornerShape(16.dp)).clickable { nav.open("item/${item.id}") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ItemPhoto(item.id, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            if (item.disposition == Disposition.DONATE) "Donation" else data.handoverMoney(item.id),
                            data.handoverWho(item.id, me).takeIf { item.handoverTo != me?.id },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.handedOver) GlassChip("Handed over", icon = Icons.Default.Check, tone = GlassTone.Mint)
            else if (data.mayHandOver(me, item)) {
                RowButton("Handed over", onClick = { store.handOver(item.id).okOrSay(context) }, icon = Icons.Default.Handshake, look = RowButtonLook.Primary)
            }
        }
    }
}
