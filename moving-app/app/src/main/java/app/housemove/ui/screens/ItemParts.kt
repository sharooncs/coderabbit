package app.housemove.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/*
 * What the item screens share (Items, Item, Walk-through, Triage, Room plan, Sell-donate-bin,
 * Devices), so they look alike and follow the same two rules:
 *
 *   No typing    ItemNamer      a name by one tap on a chip suited to the room, or by saying it
 *                AmountPicker   a price by likely amounts and minus / plus
 *                QuickText      a few words by chips or by saying them; the keyboard is behind "Edit"
 *
 *   Roles        decideOrSuggest   an Organiser decides, a Decider's same tap makes a suggestion
 *                WaitingTag        the amber callout on anything with a suggestion waiting
 *                ProposalReview    the same suggestion as an Organiser sees it: Approve, Decline
 *
 * Who may do what is always asked of the Store, never worked out here.
 */

// ---- words --------------------------------------------------------------------------

internal fun money(currency: String, amount: Double): String = (currency.trim() + " " + fmtNum(amount)).trim()

/** The Store said no: tell the person why. Returns true when the call went through. */
internal fun <T> Outcome<T>.orSay(context: Context): Boolean {
    reason?.let { toast(context, it) }
    return ok
}

private val PLACEHOLDER_NAME = Regex("^(.* )?item \\d+$", RegexOption.IGNORE_CASE)

/** Is this still the name the app gave it ("Kitchen item 3"), or none at all? */
internal fun Item.hasPlaceholderName(): Boolean = name.isBlank() || PLACEHOLDER_NAME.matches(name.trim())

private val ROOM_ITEM_NAMES: List<Pair<List<String>, List<String>>> = listOf(
    listOf("kitchen", "utility", "laundry", "pantry") to
        listOf("Fridge-freezer", "Washing machine", "Dishwasher", "Cooker", "Microwave", "Tumble dryer", "Kitchen table", "Chairs", "Bin"),
    listOf("dining") to listOf("Dining table", "Dining chairs", "Sideboard", "Display cabinet", "Rug", "Lamp"),
    listOf("living", "lounge", "sitting", "family", "front room") to
        listOf("Sofa", "Armchair", "TV", "TV stand", "Coffee table", "Bookcase", "Lamp", "Rug", "Speakers"),
    listOf("office", "study") to listOf("Desk", "Office chair", "Computer", "Monitor", "Printer", "Bookcase", "Filing cabinet", "Lamp"),
    listOf("nursery", "playroom", "kid", "child") to listOf("Cot", "Bed", "Toy chest", "Wardrobe", "Chest of drawers", "Changing table", "Bookcase"),
    listOf("bed") to listOf("Bed", "Mattress", "Wardrobe", "Chest of drawers", "Bedside table", "Dressing table", "Mirror", "Lamp", "Desk"),
    listOf("bath", "shower", "ensuite", "en-suite", "toilet", "wc") to listOf("Bathroom cabinet", "Mirror", "Laundry basket", "Shelf unit", "Scales"),
    listOf("garage", "shed", "workshop", "garden") to
        listOf("Bike", "Lawnmower", "Ladder", "Tool chest", "Workbench", "Barbecue", "Garden table", "Garden chairs"),
    listOf("loft", "attic", "cellar", "basement", "stor") to listOf("Suitcases", "Christmas tree", "Camping gear", "Exercise bike", "Spare mattress", "Shelving"),
    listOf("hall", "landing", "porch") to listOf("Coat stand", "Shoe rack", "Console table", "Mirror", "Pram", "Rug"),
)

private val ANY_ROOM_NAMES = listOf("Table", "Chair", "Cabinet", "Shelf unit", "Lamp", "Mirror", "Rug", "Bike")

/** Names of the big things usually found in a room like [roomName], to offer as one-tap chips. */
internal fun itemNameChips(roomName: String?): List<String> {
    val n = roomName?.lowercase() ?: return ANY_ROOM_NAMES
    return ROOM_ITEM_NAMES.firstOrNull { (words, _) -> words.any { it in n } }?.second ?: ANY_ROOM_NAMES
}

/** What a suggestion asks for, short enough for a callout on the item itself: "Sell for £ 80", "To Kitchen". */
internal fun AppData.proposalText(p: Proposal): String = when (p.kind) {
    ProposalKind.ITEM_DECISION -> (p.disposition ?: Disposition.UNDECIDED).label + (p.price?.let { " for " + money(move.currency, it) } ?: "")
    ProposalKind.ITEM_ROOM -> "To " + (room(p.roomId)?.name ?: "no room")
    ProposalKind.QUESTION -> p.note.ifBlank { "What should happen to it?" }
    else -> describe(p)
}

/** Suggestions and questions still waiting about one item, oldest first. */
internal fun AppData.waitingFor(itemId: String, vararg kinds: ProposalKind): List<Proposal> =
    proposalsFor(itemId).filter { it.state == ProposalState.WAITING && (kinds.isEmpty() || it.kind in kinds) }.reversed()

/** How an item stands, in a word or two, and the tone that goes with it. */
internal fun AppData.itemState(x: Item): Pair<String, GlassTone> = when (x.disposition) {
    Disposition.UNDECIDED -> "Undecided" to GlassTone.Amber
    Disposition.MOVE -> when (x.stage) {
        Stage.AT_ORIGIN -> "Move" to GlassTone.Plain
        Stage.DONE -> "In place" to GlassTone.Mint
        else -> stageText(x.stage, isBox = false, vehicle()) to GlassTone.Plain
    }
    Disposition.SELL -> if (x.gone) "Sold" to GlassTone.Mint else (if (x.listed) "Listed" else "To sell") to GlassTone.Plain
    Disposition.DONATE -> if (x.gone) "Donated" to GlassTone.Mint else "Donate" to GlassTone.Plain
    Disposition.BIN -> if (x.gone) "Binned" to GlassTone.Mint else "Bin" to GlassTone.Plain
    Disposition.STORE -> "Store" to GlassTone.Plain
}

// ---- deciding, whoever is holding the phone ------------------------------------------

/**
 * Decide what happens to an item, or suggest it, whichever this phone's person may do.
 * An Organiser's tap is the decision, and a thing marked Move gets the room its old room
 * is linked to (a room that is only a likely match is left for the room plan to offer).
 * Anyone else's tap becomes a suggestion that waits for approval. A waiting question
 * about the item is answered by it. Says why when the Store refuses.
 */
internal fun Store.decideOrSuggest(context: Context, itemId: String, d: Disposition, price: Double? = null): Boolean {
    val now = data.value
    val item = now.item(itemId) ?: return false
    val question = now.waitingFor(itemId, ProposalKind.QUESTION).firstOrNull()
    if (can(Action.DECIDE)) {
        val asking = price ?: item.price
        val out = if (question != null && d != Disposition.UNDECIDED) answer(question.id, d, asking) else decide(itemId, d, asking)
        if (out.ok && d == Disposition.MOVE) {
            val saved = data.value.item(itemId)
            val linked = data.value.room(saved?.originRoomId)?.mapsTo
            if (saved != null && saved.destRoomId == null && linked != null) planRoom(itemId, linked)
        }
        return out.orSay(context)
    }
    val mine = now.waitingFor(itemId, ProposalKind.ITEM_DECISION).any { it.by == me?.id && it.disposition == d && it.price == price }
    if (mine) {
        toast(context, "You have already suggested that. It is waiting for approval.")
        return false
    }
    val out = if (question != null) answer(question.id, d, price) else suggestDecision(itemId, d, price)
    return out.orSay(context)
}

/** Choose the new-home room of an item, or suggest it, whichever this phone's person may do. */
internal fun Store.planOrSuggestRoom(context: Context, itemId: String, roomId: String?): Boolean {
    if (can(Action.DECIDE)) return planRoom(itemId, roomId).orSay(context)
    val mine = data.value.waitingFor(itemId, ProposalKind.ITEM_ROOM).any { it.by == me?.id && it.roomId == roomId }
    if (mine) {
        toast(context, "You have already suggested that. It is waiting for approval.")
        return false
    }
    return suggestRoom(itemId, roomId).orSay(context)
}

// ---- suggestions on the thing they are about --------------------------------------------

/**
 * The amber callout on anything with a suggestion waiting: "Waiting for approval", and who
 * suggested what. [compact] is the same callout drawn smaller, for a card in a grid.
 */
@Composable
internal fun WaitingTag(p: Proposal, data: AppData, modifier: Modifier = Modifier, compact: Boolean = false) {
    val me = LocalStore.current.me?.id
    val who = if (p.by == me) "You" else data.personName(p.by).ifBlank { "Someone" }
    val question = p.kind == ProposalKind.QUESTION
    val title = if (question) "Needs a decision" else "Waiting for approval"
    val value = if (question) who + " asked" + (if (p.note.isBlank()) "" else ": " + p.note) else who + ": " + data.proposalText(p)
    val icon = if (question) Icons.Default.QuestionMark else Icons.Default.HourglassTop
    if (!compact) {
        GlassTag(title = title, value = value, icon = icon, modifier = modifier, kind = OrbKind.Amber)
        return
    }
    Row(
        modifier.glass(CircleShape, shadow = 4.dp).padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(24.dp).drawBehind { drawOrb(OrbKind.Amber, halo = false) }, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Night.OnAmber, modifier = Modifier.size(13.dp))
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, color = Night.AmberPale, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = Night.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A waiting suggestion as an Organiser sees it, on the thing it is about: who asks for what,
 * with Approve and Decline right there. Declining needs no reason; the microphone takes one.
 * A question has nothing to approve: it is answered by deciding, or dismissed.
 */
@Composable
internal fun ProposalReview(p: Proposal, data: AppData, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val context = LocalContext.current
    val question = p.kind == ProposalKind.QUESTION
    val who = data.personName(p.by).ifBlank { "Someone" }
    GlassCard(modifier.fillMaxWidth(), tone = GlassTone.Amber, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).drawBehind { drawOrb(OrbKind.Amber, ring = false) }, contentAlignment = Alignment.Center) {
                    Icon(
                        if (question) Icons.Default.QuestionMark else Icons.Default.HourglassTop, contentDescription = null,
                        tint = Night.OnAmber, modifier = Modifier.size(18.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        if (question) "Needs a decision" else "Waiting for approval",
                        style = MaterialTheme.typography.bodySmall, color = Night.AmberPale, maxLines = 1,
                    )
                    Text(
                        if (question) "$who asked" else who + ": " + data.proposalText(p),
                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (p.note.isNotBlank()) Text("“${p.note}”", style = MaterialTheme.typography.bodySmall, color = Night.TextSoft)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (question) {
                    Text("Choose what happens to it to answer.", style = MaterialTheme.typography.bodySmall, color = Night.TextSoft, modifier = Modifier.weight(1f))
                } else {
                    // Tight padding, so both words still fit side by side on a small phone with large text.
                    Button(
                        onClick = { store.approve(p.id).orSay(context) }, modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text("Approve", maxLines = 1) }
                }
                OutlinedButton(
                    onClick = { store.decline(p.id).orSay(context) },
                    modifier = (if (question) Modifier else Modifier.weight(1f)).heightIn(min = 44.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Night.Text),
                    contentPadding = PaddingValues(horizontal = if (question) 16.dp else 6.dp),
                ) { Text(if (question) "Dismiss" else "Decline", maxLines = 1) }
                if (!question) {
                    SpeakButton("Why not?", contentDescription = "Decline and say why", size = 40.dp) { why -> store.decline(p.id, why).orSay(context) }
                }
            }
        }
    }
}

/** What became of this person's latest suggestion about an item, once it is no longer waiting. Nothing when there is none. */
@Composable
internal fun MyLastAnswer(itemId: String, data: AppData, modifier: Modifier = Modifier) {
    val me = LocalStore.current.me?.id ?: return
    val last = data.proposalsFor(itemId).firstOrNull { it.by == me } ?: return
    if (last.state == ProposalState.WAITING) return
    val by = data.personName(last.decidedBy)
    val (text, tone, icon) = when (last.state) {
        ProposalState.APPROVED -> Triple(
            if (last.kind == ProposalKind.QUESTION) "Answered" + (if (by.isBlank()) "" else " by $by") else "Approved" + (if (by.isBlank()) "" else " by $by") + ": " + data.proposalText(last),
            GlassTone.Mint, Icons.Default.CheckCircle,
        )
        ProposalState.DECLINED -> Triple(
            "Declined" + (if (by.isBlank()) "" else " by $by") + (if (last.reason.isBlank()) "" else ": " + last.reason),
            GlassTone.Red, Icons.Default.Block,
        )
        else -> Triple(last.state.label + ": " + data.proposalText(last), GlassTone.Plain, Icons.Default.Info)
    }
    Row(
        modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), tone, shadow = 0.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tone.accent(), modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

// ---- pictures and marks ---------------------------------------------------------------

/** An item's first photo with rounded corners, or a quiet glass panel with a chair when there is none yet. */
@Composable
internal fun ItemPhoto(itemId: String, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp), iconSize: Dp = 28.dp) {
    val photo = appData().photosOf(itemId).firstOrNull()
    if (photo != null) PhotoThumb(photo, modifier.clip(shape)) else {
        Box(
            modifier.clip(shape)
                .background(Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x0DFFFFFF), Color(0x1F62E3BB))))
                .border(1.dp, Night.Hairline, shape)
                .semantics { contentDescription = "No photo yet" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Pic.Item, contentDescription = null, tint = Night.TextDim, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * A small mark that sits on a photograph. Glass would vanish against a bright picture,
 * so it is a dark pill with a light picture and word, like the buttons on the camera.
 */
@Composable
internal fun PhotoMark(icon: ImageVector, text: String, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Row(
        modifier.clip(CircleShape).background(Color(0xCC081220)).border(1.dp, Color.White.copy(alpha = 0.32f), CircleShape)
            .padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, color = tint, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The colour a state is written in on a [PhotoMark]. */
internal fun GlassTone.onPhoto(): Color = when (this) {
    GlassTone.Mint -> Night.MintLight
    GlassTone.Amber -> Night.Amber
    GlassTone.Red -> Night.Red
    else -> Color.White
}

/**
 * A round on/off mark with a word under it, for the end of a row: "Listed", "Gone".
 * On is mint glass with a tick, so it does not depend on colour alone.
 */
@Composable
internal fun MarkButton(icon: ImageVector, label: String, checked: Boolean, description: String, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Column(
        modifier.widthIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.size(42.dp).glass(CircleShape, if (checked) GlassTone.Mint else GlassTone.Plain, shadow = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (checked) Icons.Default.Check else icon, contentDescription = null,
                tint = if (checked) Night.MintPale else Night.TextDim, modifier = Modifier.size(20.dp),
            )
        }
        Text(
            label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
            color = if (checked) Night.MintPale else Night.TextDim, fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

/** The number a list screen is about, as a ring with a sentence beside it and room for a few marks. */
@Composable
internal fun SummaryCard(ratio: Ratio, title: String, modifier: Modifier = Modifier, extra: @Composable ColumnScope.() -> Unit = {}) {
    GlassCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), sheen = true) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val done = ratio.total > 0 && ratio.done >= ratio.total
            Ring(ratio, size = 64.dp, color = if (done) Night.MintLight else Night.Mint) {
                if (done) Icon(Icons.Default.Check, contentDescription = null, tint = Night.MintLight)
                else Text("${ratio.done}/${ratio.total}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                extra()
            }
        }
    }
}

// ---- entering things without the keyboard ------------------------------------------------

/** A row of one-tap chips that scrolls sideways when there are more than fit. */
@Composable
internal fun ChipStrip(options: List<String>, modifier: Modifier = Modifier, selected: (String) -> Boolean = { false }, onPick: (String) -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> GlassChip(o, selected = selected(o), onClick = { onPick(o) }) }
    }
}

/**
 * The name of an item without a keyboard. While it still has the name the app gave it,
 * chips offer what is usually found in its room: one tap names it. The microphone takes
 * a spoken name at any time, and the pencil opens a field for typing one.
 * [showName] is off where the name is already written next to this.
 */
@Composable
internal fun ItemNamer(item: Item, modifier: Modifier = Modifier) {
    val store = LocalStore.current
    val data = appData()
    var editing by rememberSaveable(item.id) { mutableStateOf(false) }
    // Bumped when the name is set by voice, so the text field picks the new value up.
    var heard by remember { mutableIntStateOf(0) }
    val unnamed = item.hasPlaceholderName()
    fun rename(v: String) {
        store.data.value.item(item.id)?.let { store.put(it.copy(name = v)) }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                item.title(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (unnamed) Night.TextDim else Night.Text, modifier = Modifier.weight(1f),
            )
            // Once it has a name of its own, the pencil alone is enough: fewer buttons down a long list.
            if (unnamed || editing) {
                SpeakButton("What is it?", contentDescription = "Say the name of ${item.title()}", size = 40.dp) { said ->
                    rename(said.replaceFirstChar { it.uppercase() })
                    heard++
                }
            }
            GlassOrb(
                if (editing) Icons.Default.Check else Icons.Default.Edit,
                if (editing) "Done naming ${item.title()}" else "Edit the name of ${item.title()}",
                size = 40.dp, onClick = { editing = !editing },
            )
        }
        if (editing) {
            TextInput(value = item.name, onChange = { rename(it) }, label = "Name", key = "${item.id}-name-$heard")
        } else if (unnamed) {
            ChipStrip(itemNameChips(data.room(item.originRoomId)?.name)) { rename(it) }
        }
    }
}

/**
 * A few words without a keyboard: chips that add or take away the usual ones, and the
 * microphone for anything else. What is set is written above them; the pencil opens a
 * field, already filled in, for changing it by typing.
 */
@Composable
internal fun QuickText(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    prompt: String,
    key: String,
    modifier: Modifier = Modifier,
    chips: List<String> = emptyList(),
    empty: String = "Nothing yet",
    /** A list of separate things (chips, "and" and commas split it) rather than a sentence or two. */
    list: Boolean = chips.isNotEmpty(),
) {
    var editing by rememberSaveable(key) { mutableStateOf(false) }
    val have = contentsLines(value).map { it.lowercase() }.toSet()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                if (!editing) Text(value.ifBlank { empty }, style = MaterialTheme.typography.bodyLarge, color = if (value.isBlank()) Night.TextDim else Night.Text)
            }
            SpeakButton(prompt, contentDescription = "Say: $label", size = 40.dp) { said ->
                val heard = said.replaceFirstChar { it.uppercase() }
                onChange(if (list) addContents(value, heard) else if (value.isBlank()) heard else "$value, $heard")
            }
            GlassOrb(
                if (editing) Icons.Default.Check else Icons.Default.Edit, if (editing) "Done: $label" else "Edit: $label",
                size = 40.dp, onClick = { editing = !editing },
            )
        }
        if (editing) {
            TextInput(value = value, onChange = onChange, label = label, key = "$key-field", singleLine = false)
        } else if (chips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { c ->
                    val on = c.lowercase() in have
                    GlassChip(c, selected = on, onClick = { onChange(if (on) removeContents(value, c) else addContents(value, c)) })
                }
            }
        }
    }
}

private fun stepFor(v: Double): Double = when {
    v < 20 -> 1.0
    v < 100 -> 5.0
    v < 500 -> 10.0
    v < 2000 -> 50.0
    else -> 100.0
}

internal val PRICE_PRESETS = listOf(5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)

/**
 * A sum of money without a keyboard: likely amounts to tap, and minus and plus to
 * adjust in sensible steps. Null means none has been set.
 */
@Composable
internal fun AmountPicker(
    value: Double?,
    onChange: (Double?) -> Unit,
    label: String,
    currency: String,
    modifier: Modifier = Modifier,
    presets: List<Double> = PRICE_PRESETS,
) {
    val v = value ?: 0.0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Night.TextDim)
                Text(
                    if (value == null) "Not set" else money(currency, value),
                    style = MaterialTheme.typography.headlineSmall, color = if (value == null) Night.TextDim else Night.Text, maxLines = 1,
                )
            }
            GlassOrb(Icons.Default.Remove, "$label: less", size = 44.dp, onClick = {
                val down = v - stepFor(v - 0.01)
                onChange(if (down <= 0.0) null else down)
            })
            Spacer(Modifier.width(8.dp))
            GlassOrb(Icons.Default.Add, "$label: more", size = 44.dp, onClick = { onChange(v + stepFor(v)) })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            presets.distinct().forEach { p -> GlassChip(money(currency, p), selected = value == p, onClick = { onChange(p) }) }
        }
    }
}

/** A date as a chip: tap to choose it. Small enough to sit beside other chips. */
@Composable
internal fun DateChip(day: Long?, label: String, modifier: Modifier = Modifier, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    GlassChip(
        if (day == null) label else "$label ${fmtDayShort(day)}", modifier, icon = Icons.Default.Event,
        tone = if (day != null && day < today()) GlassTone.Red else GlassTone.Plain, onClick = { open = true },
    )
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (day ?: today()) * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Math.floorDiv(it, 86_400_000L)) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onPick(null); open = false }) { Text("Clear") }
                    TextButton(onClick = { open = false }) { Text("Cancel") }
                }
            },
        ) { DatePicker(state = state) }
    }
}

/** A small heading over a group of rows, with a picture and an optional count at the end. */
@Composable
internal fun GroupHeading(icon: ImageVector, title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Night.Mint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = Night.TextDim, textAlign = TextAlign.End)
    }
}
