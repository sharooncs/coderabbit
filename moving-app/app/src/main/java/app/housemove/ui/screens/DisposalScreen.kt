package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/**
 * Tracker for everything that is leaving before the move: to sell, to donate, to bin.
 *
 * Marking something listed or gone, and what it sold for, is carrying a decision out:
 * the buttons are there only for someone who may (an Organiser, or a Decider whose
 * suggestion for that item was approved). Everyone else sees how each thing stands.
 * Prices are picked from likely amounts or stepped up and down, never typed.
 */
@Composable
fun DisposalScreen() {
    val store = LocalStore.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val data = appData()
    val device = deviceInfo() // whose phone this is decides what is offered below
    // A Helper is shown no prices: for them this is the list of things to hand over.
    if (!data.seesHidden(device)) {
        HandoverList()
        return
    }
    val me = store.me
    val mayDecide = store.can(Action.DECIDE)
    val maySuggest = store.can(Action.SUGGEST)
    val now = today()
    val currency = data.move.currency
    val seen = rememberSeenAnswers()

    val groups = listOf(
        Disposition.SELL to "To sell",
        Disposition.DONATE to "To donate",
        Disposition.BIN to "To bin or recycle",
    ).map { (d, title) ->
        Triple(d, title, data.liveItems.filter { it.disposition == d }.sortedWith(compareBy({ it.gone }, { it.deadlineDay ?: Long.MAX_VALUE }, { it.name.lowercase() })))
    }
    val everything = groups.flatMap { it.third }
    val selling = groups.first().third
    val waiting = data.waitingProposals().filter { it.kind == ProposalKind.ITEM_DECISION }.groupBy { it.targetId }

    Screen("Sell, donate, bin") {
        if (everything.isEmpty()) {
            ScrollColumn {
                EmptyState(
                    Icons.Default.Sell, "Nothing is leaving yet",
                    text = "Things marked Sell, Donate or Bin are tracked here until they are gone.",
                    action = if (data.uses(Part.TRIAGE) && maySuggest) "Decide what to keep" else null,
                    onAction = { nav.open("triage") },
                )
            }
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "totals") {
                val gone = everything.count { it.gone }
                val late = everything.count { !it.gone && (it.deadlineDay ?: Long.MAX_VALUE) < now }
                ProgressCard(
                    Ratio(gone, everything.size), if (gone == everything.size) "All gone" else "$gone of ${everything.size} gone",
                    sub = if (selling.isEmpty()) null else "Asking " + money(currency, selling.sumOf { it.price ?: 0.0 }) +
                        " · sold " + money(currency, selling.filter { it.gone }.sumOf { it.soldFor ?: it.price ?: 0.0 }),
                ) {
                    if (late > 0) IconFlag(Icons.Default.Schedule, "$late past ${if (late == 1) "its" else "their"} date", error = true)
                }
            }
            groups.forEach { (d, title, list) ->
                if (list.isNotEmpty()) {
                    item(key = "head-" + d.name) {
                        ListHeading(title, icon = d.icon(), trailing = "${list.count { it.gone }} of ${list.size} gone")
                    }
                    items(list, key = { it.id }) { x ->
                        val declined = data.declinedOn(x.id, me, seen.ids, ProposalKind.ITEM_DECISION)
                        DisposalRow(
                            x = x,
                            data = data,
                            now = now,
                            waiting = waiting[x.id].orEmpty(),
                            declined = declined,
                            onSeen = { declined?.let { seen.mark(it.id) } },
                            mayDecide = mayDecide,
                            maySuggest = maySuggest,
                            mayCarryOut = data.mayCarryOut(me, x),
                            onOpen = { nav.open("item/${x.id}") },
                            onListed = { v -> store.carryOut(x.id, listed = v).okOrSay(context) },
                            onGone = { v ->
                                // What it went for starts at the asking price: right most of the time, one tap to change.
                                store.carryOut(x.id, gone = v, soldFor = if (v && x.disposition == Disposition.SELL) x.soldFor ?: x.price else null).okOrSay(context)
                            },
                            onSoldFor = { v -> if (v != null) store.carryOut(x.id, soldFor = v).okOrSay(context) },
                            onAsking = { v ->
                                if (mayDecide) store.decide(x.id, Disposition.SELL, v).okOrSay(context)
                                else store.decideOrSuggest(context, x.id, Disposition.SELL, v)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One thing that is leaving: its photo, when it should be gone, and how far it has got.
 * Something for sale carries its price as a chip; tapping it opens the amounts to pick from.
 */
@Composable
private fun DisposalRow(
    x: Item,
    data: AppData,
    now: Long,
    waiting: List<Proposal>,
    declined: Proposal?,
    onSeen: () -> Unit,
    mayDecide: Boolean,
    maySuggest: Boolean,
    mayCarryOut: Boolean,
    onOpen: () -> Unit,
    onListed: (Boolean) -> Unit,
    onGone: (Boolean) -> Unit,
    onSoldFor: (Double?) -> Unit,
    onAsking: (Double?) -> Unit,
) {
    val currency = data.move.currency
    val selling = x.disposition == Disposition.SELL
    val deadline = x.deadlineDay
    val late = !x.gone && deadline != null && deadline < now
    // The price is open for changing: the asking price until it has gone, then what it went for.
    var pricing by rememberSaveable(x.id) { mutableStateOf(false) }
    val mayPrice = if (x.gone) mayCarryOut else maySuggest
    // A Decider's new asking price is put together here and sent as one suggestion.
    var draft by remember(x.id, x.price) { mutableStateOf(x.price) }
    GlassCard(Modifier.fillMaxWidth(), tone = if (late) GlassTone.Red else GlassTone.Plain, shape = RoundedCornerShape(22.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Something that has gone fades back, so what is still here stands out.
                // On a small screen or with large text the picture gives way to the words, and the words get shorter.
                val narrow = narrowScreen()
                ItemPhoto(x.id, Modifier.size(if (narrow) 48.dp else 68.dp).alpha(if (x.gone) 0.45f else 1f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        x.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (x.gone) Night.TextDim else Night.Text,
                    )
                    val line = when {
                        late -> "Late: was due ${fmtDayShort(deadline)}"
                        x.gone && !selling -> data.itemState(x).first
                        !x.gone && deadline != null -> "By ${fmtDayShort(deadline)}" + if (narrow) "" else " (${relDay(deadline, now)})"
                        else -> null
                    }
                    if (line != null) {
                        Text(
                            line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (late) Night.RedPale else Night.TextDim,
                        )
                    }
                    // Who hands it over, and how, once it is arranged or done.
                    if (x.forHandover() && (x.handoverTo != null || x.handedOver || x.paid)) {
                        Text(
                            if (x.handedOver) "Handed over" else listOfNotNull(data.handoverWho(x.id, data.person(LocalStore.current.me?.id)), data.handoverMoney(x.id)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = if (x.handedOver) Night.MintLight else Night.TextDim,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (selling) {
                        val text = if (x.gone) (if (narrow) "" else "Sold for ") + (x.soldFor?.let { money(currency, it) } ?: "?")
                        else x.price?.let { (if (narrow) "" else "Asking ") + money(currency, it) } ?: if (mayPrice) "Set a price" else "No price yet"
                        val icon = if (x.gone) Icons.Default.Payments else Icons.Default.Sell
                        if (mayPrice) GlassChip(text, icon = icon, selected = pricing, onClick = { pricing = !pricing })
                        else GlassChip(text, Modifier.padding(top = 6.dp), icon = icon, tone = if (x.gone) GlassTone.Mint else GlassTone.Plain)
                    }
                }
            }
            if (mayCarryOut) {
                if (selling) MarkButton(Icons.Default.Campaign, "Listed", x.listed, "Listed for sale: ${x.title()}", onChange = onListed)
                MarkButton(Icons.Default.RadioButtonUnchecked, "Gone", x.gone, "Gone: ${x.title()}", onChange = onGone)
            } else if (x.gone || x.listed) {
                // Not theirs to carry out: how it stands, as a mark rather than a button.
                GlassChip(
                    if (x.gone) data.itemState(x).first else "Listed", Modifier.padding(end = 8.dp),
                    icon = if (x.gone) Icons.Default.Check else Icons.Default.Campaign, tone = if (x.gone) GlassTone.Mint else GlassTone.Plain,
                )
            }
        }
        if (pricing && mayPrice && selling) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    x.gone -> AmountPicker(x.soldFor, onSoldFor, "Sold for", currency, presets = soldPresets(x.price))
                    mayDecide -> AmountPicker(x.price, onAsking, "Asking price", currency)
                    else -> {
                        AmountPicker(draft, { draft = it }, "Suggest an asking price", currency)
                        val changed = draft != null && draft != x.price
                        RowButton(
                            "Suggest " + (draft?.let { money(currency, it) } ?: "a price"),
                            onClick = { if (changed) { onAsking(draft); pricing = false } },
                            modifier = Modifier.fillMaxWidth().enabledLook(changed), icon = Icons.Default.Send, look = RowButtonLook.Primary,
                        )
                    }
                }
            }
        }
        waiting.forEach { p ->
            Box(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) { Suggestion(p, data) }
        }
        // What this person suggested for it was turned down: they are told why, once.
        if (declined != null) {
            DeclinedNote(declined, data, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), what = data.proposalText(declined), onSeen = onSeen)
        }
    }
}

/**
 * The things decided Sell or Donate as a Helper sees them: each to be handed over, with "Paid" or "Not paid"
 * (and the amount only for the one handing it over), and "Handed over" for anyone. No prices.
 */
@Composable
private fun HandoverList() {
    val nav = LocalNav.current
    val data = appData()
    val me = LocalStore.current.me
    val all = data.liveItems.filter { it.forHandover() && !it.gone }
        .sortedWith(compareBy({ it.handedOver }, { it.handoverTo != me?.id }, { it.title().lowercase() }))
    Screen("Hand-overs") {
        if (all.isEmpty()) {
            ScrollColumn {
                EmptyState(Icons.Default.Handshake, "Nothing to hand over", text = "Things the Organisers decide to sell or donate appear here, to be handed over.")
            }
        } else LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "totals") {
                val done = all.count { it.handedOver }
                ProgressCard(Ratio(done, all.size), if (done == all.size) "All handed over" else "$done of ${all.size} handed over")
            }
            items(all, key = { it.id }) { x -> HandoverRow(x) }
        }
    }
}
