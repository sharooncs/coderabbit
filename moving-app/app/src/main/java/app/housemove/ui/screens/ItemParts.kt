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
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.*
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
 *                QuickText      a few words by chips or by saying them; the keyboard is behind "Edit"
 *
 *   Roles        decideOrSuggest   an Organiser decides, a Decider's same tap makes a suggestion
 *                MyLastAnswer      what became of this person's own suggestion about an item
 *
 * The waiting tag, Approve and Decline, and the price picker are in Shared.kt: the packing
 * and move-day screens use the same ones. Who may do what is always asked of the Store.
 */

// ---- words --------------------------------------------------------------------------

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

/**
 * Suggestions and questions still waiting about one item, oldest first. Requests to delete it
 * are left out: they are not answered by deciding, and are shown by [DeleteRequests].
 */
internal fun AppData.waitingFor(itemId: String, vararg kinds: ProposalKind): List<Proposal> =
    proposalsFor(itemId).filter { it.state == ProposalState.WAITING && !it.deletes && (kinds.isEmpty() || it.kind in kinds) }.reversed()

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
        return out.okOrSay(context)
    }
    val mine = now.waitingFor(itemId, ProposalKind.ITEM_DECISION).any { it.by == me?.id && it.disposition == d && it.price == price }
    if (mine) {
        toast(context, "You have already suggested that. It is waiting for approval.")
        return false
    }
    val out = if (question != null) answer(question.id, d, price) else suggestDecision(itemId, d, price)
    return out.okOrSay(context)
}

/** Choose the new-home room of an item, or suggest it, whichever this phone's person may do. */
internal fun Store.planOrSuggestRoom(context: Context, itemId: String, roomId: String?): Boolean {
    if (can(Action.DECIDE)) return planRoom(itemId, roomId).okOrSay(context)
    val mine = data.value.waitingFor(itemId, ProposalKind.ITEM_ROOM).any { it.by == me?.id && it.roomId == roomId }
    if (mine) {
        toast(context, "You have already suggested that. It is waiting for approval.")
        return false
    }
    return suggestRoom(itemId, roomId).okOrSay(context)
}

/**
 * What a Helper has in place of the decision tiles: one tap tells the Organisers this
 * thing needs them. The microphone asks with a few words of why; on a phone with no
 * speech a keyboard button takes its place. [onAsked] runs once the question is sent.
 */
@Composable
internal fun AskForDecision(itemId: String, primary: Boolean, modifier: Modifier = Modifier, onAsked: () -> Unit = {}) {
    val store = LocalStore.current
    val context = LocalContext.current
    var typing by rememberSaveable(itemId) { mutableStateOf(false) }
    fun ask(note: String = "") {
        if (store.ask(itemId, note).okOrSay(context)) onAsked()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigAction(Icons.Default.QuestionMark, "Needs a decision", onClick = { ask() }, modifier = Modifier.weight(1f), primary = primary)
            SayOrType(
                "What should they know about it?", "Ask with a spoken note", "Ask with a typed note",
                typing, onType = { typing = !typing }, size = 56.dp,
            ) { ask(it) }
        }
        if (typing) TypedLine("What should they know about it?", "Ask") { ask(it) }
    }
}

// ---- what became of a suggestion ----------------------------------------------------------

/** What became of this person's latest suggestion about an item, once it is no longer waiting. Nothing when there is none. */
@Composable
internal fun MyLastAnswer(itemId: String, data: AppData, modifier: Modifier = Modifier) {
    val me = LocalStore.current.me?.id ?: return
    val last = data.proposalsFor(itemId).firstOrNull { it.by == me } ?: return
    if (last.state == ProposalState.WAITING) return
    // Turned down: the same note, in the same words, as on the lists, and kept here as the item's record.
    if (last.state == ProposalState.DECLINED) {
        DeclinedNote(last, data, modifier, what = data.proposalWords(last).takeIf { !last.isQuestion })
        return
    }
    val by = data.personName(last.decidedBy)
    val (text, tone, icon) = when (last.state) {
        ProposalState.APPROVED -> Triple(
            if (last.isQuestion) "Answered" + (if (by.isBlank()) "" else " by $by") else "Approved" + (if (by.isBlank()) "" else " by $by") + ": " + data.proposalWords(last),
            GlassTone.Mint, Icons.Default.CheckCircle,
        )
        ProposalState.DECLINED -> Triple(
            "Declined" + (if (by.isBlank()) "" else " by $by") + (if (last.reason.isBlank()) "" else ": " + last.reason),
            GlassTone.Red, Icons.Default.Block,
        )
        else -> Triple(last.state.label + ": " + data.proposalWords(last), GlassTone.Plain, Icons.Default.Info)
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
 *
 * [showName] is off where the name is already written next to this: on the item's own
 * page it is the title, and the pencil is in the bar ([typing] then says whether the
 * field is open).
 */
@Composable
internal fun ItemNamer(item: Item, modifier: Modifier = Modifier, showName: Boolean = true, typing: Boolean? = null) {
    val store = LocalStore.current
    val data = appData()
    var own by rememberSaveable(item.id) { mutableStateOf(false) }
    val editing = typing ?: own
    // Bumped when the name is set by a chip or by voice, so the text field picks the new value up.
    var named by remember { mutableIntStateOf(0) }
    val unnamed = item.hasPlaceholderName()
    fun rename(v: String) {
        store.data.value.item(item.id)?.let { store.put(it.copy(name = v)) }
    }
    val mic: @Composable (Dp) -> Unit = { size ->
        SpeakButton("What is it?", contentDescription = "Say the name of ${item.title()}", size = size) { said ->
            rename(said.replaceFirstChar { it.uppercase() })
            named++
        }
    }
    val entry: @Composable (Modifier) -> Unit = { m ->
        if (editing) TextInput(value = item.name, onChange = { rename(it) }, label = "Name", modifier = m, key = "${item.id}-name-$named")
        else ChipStrip(itemNameChips(data.room(item.originRoomId)?.name), m) { rename(it) }
    }
    if (!showName) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            entry(Modifier.weight(1f))
            mic(44.dp)
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                item.title(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (unnamed) Night.TextDim else Night.Text, modifier = Modifier.weight(1f),
            )
            // Once it has a name of its own, the pencil alone is enough: fewer buttons down a long list.
            if (unnamed || editing) mic(40.dp)
            GlassOrb(
                if (editing) Icons.Default.Check else Icons.Default.Edit,
                if (editing) "Done naming ${item.title()}" else "Edit the name of ${item.title()}",
                size = 40.dp, onClick = { own = !own },
            )
        }
        if (editing || unnamed) entry(Modifier.fillMaxWidth())
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

private val DAMAGE_CHIPS = listOf("Scratched", "Dented", "Cracked", "Broken", "Torn", "Wet", "Part missing")

/** What happened to something that was damaged: the usual words to tap, or said, or typed behind the pencil. */
@Composable
internal fun DamageText(value: String, onChange: (String) -> Unit, key: String, modifier: Modifier = Modifier) {
    QuickText(
        value, onChange, label = "What happened?", prompt = "What happened to it?",
        key = key, modifier = modifier, chips = DAMAGE_CHIPS, empty = "Not described yet",
    )
}

/** A date as a chip: tap to choose it. Small enough to sit beside other chips. */
@Composable
internal fun DateChip(day: Long?, label: String, modifier: Modifier = Modifier, onPick: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    GlassChip(
        if (day == null) label else "$label ${fmtDayShort(day)}", modifier, icon = Icons.Default.Event,
        tone = if (day != null && day < today()) GlassTone.Red else GlassTone.Plain, onClick = { open = true },
    )
    if (open) DatePickerPanel(day, label, allowClear = true, onPick = onPick, onClose = { open = false })
}

