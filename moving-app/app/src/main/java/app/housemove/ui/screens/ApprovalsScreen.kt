package app.housemove.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.housemove.data.*
import app.housemove.ui.*

/** The decisions an Organiser can answer a question with. */
private val ANSWERS = listOf(Disposition.MOVE, Disposition.SELL, Disposition.DONATE, Disposition.BIN, Disposition.STORE)
    .map { Choice(it, it.label, it.icon()) }

/** One-tap reasons for saying no, most likely first for each kind of suggestion. */
private fun declineReasons(kind: ProposalKind): List<String> = when (kind) {
    ProposalKind.ITEM_DECISION -> listOf("We're keeping it", "Not yet", "Different price", "Let's talk first")
    ProposalKind.ITEM_ROOM -> listOf("Won't fit there", "Not yet", "Let's talk first")
    ProposalKind.COST -> listOf("Too expensive", "Not needed", "Not yet", "Let's talk first")
    ProposalKind.TASK -> listOf("Not needed", "Already planned", "Let's talk first")
    ProposalKind.QUESTION -> listOf("Not sure yet", "Ask me in person")
}

/**
 * Suggestions and questions. For someone who may approve, this is the inbox of what is
 * waiting; for everyone else it is the list of what they suggested or asked and what
 * became of it.
 */
@Composable
fun ApprovalsScreen() {
    if (allowed(Action.APPROVE)) Inbox() else Mine()
}

// ---- the inbox of someone who may approve --------------------------------------------------

@Composable
private fun Inbox() {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val waiting = data.waitingProposals()
    val approvable = waiting.filter { it.kind != ProposalKind.QUESTION }
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    val chosen = approvable.filter { it.id in picked }
    var declining by rememberSaveable { mutableStateOf<String?>(null) }

    fun approve(list: List<Proposal>) {
        var done = 0
        var refused: String? = null
        var last = ""
        for (p in list) {
            when (val o = store.approve(p.id)) {
                is Outcome.Ok -> {
                    done++
                    last = data.describe(p)
                }
                is Outcome.Refused -> refused = o.reason
            }
        }
        picked = emptyList()
        toast(
            context,
            when {
                done == 0 -> refused ?: "Nothing to approve."
                refused != null -> "Approved $done. One was left: $refused"
                done == 1 -> "Approved: $last"
                else -> "Approved $done suggestions"
            },
        )
    }

    Screen("Approvals", tab = "home", subtitle = data.move.name.ifBlank { "Your move" }) {
        if (waiting.isEmpty()) {
            ScrollColumn {
                EmptyState(
                    Icons.Default.TaskAlt, "Nothing is waiting",
                    text = if (data.shared()) "What the others suggest or ask lands here for you to decide." else "When other people are on the move, what they suggest lands here.",
                )
                DecidedLately(data)
            }
            return@Screen
        }
        // Stays put while the list scrolls: how many, and approving several at once.
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 4.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chosen.isEmpty()) {
                Text("${waiting.size} waiting", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (approvable.size > 1) GlassChip("Select all", icon = Icons.Default.DoneAll, onClick = { picked = approvable.map { it.id } })
            } else {
                Text("${chosen.size} selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                GlassChip("Clear", onClick = { picked = emptyList() })
                Row(
                    Modifier.mintPill().clickable(role = SemanticsRole.Button) { approve(chosen) }.heightIn(min = 44.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = null, tint = Night.OnMint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Approve ${chosen.size}", style = MaterialTheme.typography.labelLarge, color = Night.OnMint)
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            waiting.forEach { p ->
                key(p.id) {
                    WaitingCard(
                        p,
                        selected = p.id in picked,
                        onSelect = if (p.kind == ProposalKind.QUESTION || approvable.size < 2) null else ({ picked = if (p.id in picked) picked - p.id else picked + p.id }),
                        declining = declining == p.id,
                        onDeclining = { declining = if (it) p.id else null },
                        onApprove = { approve(listOf(p)) },
                    )
                }
            }
            DecidedLately(data)
        }
    }
}

/** One waiting suggestion or question: the photo, who, what, and the two big answers. */
@Composable
private fun WaitingCard(
    p: Proposal,
    selected: Boolean,
    onSelect: (() -> Unit)?,
    declining: Boolean,
    onDeclining: (Boolean) -> Unit,
    onApprove: () -> Unit,
) {
    val store = LocalStore.current
    val context = LocalContext.current
    val data = appData()
    val question = p.kind == ProposalKind.QUESTION
    val what = data.describe(p)
    GlassCard(Modifier.fillMaxWidth(), tone = if (selected) GlassTone.Mint else GlassTone.Plain, shape = RoundedCornerShape(26.dp), sheen = true) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                ProposalPicture(p, Modifier.size(76.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Asker(p, if (question) "asks" else "suggests")
                    Text(what, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    standsNow(data, p)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (p.note.isNotBlank()) Text("“${p.note}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = Night.TextSoft)
                }
                if (onSelect != null) {
                    // An empty ring until chosen, so it never looks ticked when it is not.
                    GlassOrb(
                        if (selected) Icons.Default.Check else Icons.Default.RadioButtonUnchecked, (if (selected) "Selected: " else "Select: ") + what,
                        kind = if (selected) OrbKind.Primary else OrbKind.Plain, size = 36.dp, onClick = onSelect,
                    )
                } else if (question && !declining) {
                    GlassChip("Not now", onClick = { onDeclining(true) })
                }
            }
            if (question) {
                // An Organiser's answer is the decision.
                // Five in a row where the words fit; on a narrow screen or with large text, three.
                IconChoice(
                    ANSWERS, selected = null, columns = if (tightScreen()) 3 else 5,
                    onPick = { d -> if (store.answer(p.id, d).orSay(context) != null) toast(context, "Answered: ${d.label}") },
                )
            } else if (!declining) {
                // Side by side where both words fit whole; one above the other where they would not.
                if (tightScreen()) {
                    BigAction(Icons.Default.Check, "Approve", onClick = onApprove)
                    BigAction(Icons.Default.Close, "Decline", onClick = { onDeclining(true) }, primary = false)
                } else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigAction(Icons.Default.Check, "Approve", onClick = onApprove, modifier = Modifier.weight(1f))
                    BigAction(Icons.Default.Close, "Decline", onClick = { onDeclining(true) }, modifier = Modifier.weight(1f), primary = false)
                }
            }
            if (declining) DeclinePanel(p, onDone = { onDeclining(false) })
        }
    }
}

/** Saying no: a few reasons that decline with one tap, or a few words spoken or typed. */
@Composable
private fun DeclinePanel(p: Proposal, onDone: () -> Unit) {
    val store = LocalStore.current
    val context = LocalContext.current
    var words by rememberSaveable(p.id) { mutableStateOf("") }
    var typing by rememberSaveable(p.id) { mutableStateOf(false) }
    var spoken by rememberSaveable(p.id) { mutableIntStateOf(0) }
    fun decline(reason: String) {
        if (store.decline(p.id, reason).orSay(context) != null) toast(context, if (reason.isBlank()) "Declined" else "Declined: ${reason.trim()}")
        onDone()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = Night.Hairline)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Why not? One tap declines it.", style = MaterialTheme.typography.labelLarge, color = Night.AmberPale, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Cancel") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            declineReasons(p.kind).forEach { r -> GlassChip(r, onClick = { decline(r) }) }
            GlassChip("No reason", onClick = { decline("") })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpeakButton("Why not?", contentDescription = "Say why") { said ->
                words = said
                spoken++
            }
            if (typing) TextInput(words, { words = it }, "Your reason", Modifier.weight(1f), key = "${p.id}-$spoken")
            else GlassChip("Type a reason", icon = Icons.Default.Edit, onClick = { typing = true })
        }
        if (words.isNotBlank()) {
            BigAction(Icons.Default.Close, "Decline: “${words.trim()}”", onClick = { decline(words) }, primary = false)
        }
    }
}

/** Who suggested or asked, and how long ago. */
@Composable
private fun Asker(p: Proposal, verb: String) {
    val data = appData()
    val mine = p.by == deviceInfo().personId
    Row(verticalAlignment = Alignment.CenterVertically) {
        PersonOrb(data.person(p.by), size = 22.dp, me = mine)
        Spacer(Modifier.width(6.dp))
        Text(
            (if (mine) "You" else data.personName(p.by).ifBlank { "Someone" }) + " " + (if (mine) verb.removeSuffix("s") else verb) + " · " + agoText(p.createdAt),
            style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** How the thing a suggestion is about stands at the moment, for comparison. */
private fun standsNow(data: AppData, p: Proposal): String? = when (p.kind) {
    ProposalKind.ITEM_DECISION, ProposalKind.QUESTION -> data.item(p.targetId)?.let { i ->
        "Now: " + i.disposition.label + if (i.disposition == Disposition.SELL && i.price != null) " for " + (data.move.currency + " " + fmtNum(i.price)).trim() else ""
    }
    ProposalKind.ITEM_ROOM -> data.item(p.targetId)?.let { "Now: " + (data.room(it.destRoomId)?.name ?: "no room") }
    ProposalKind.COST -> data.expense(p.targetId)?.let { "Now: " + (data.move.currency + " " + fmtNum(it.amount)).trim() }
        ?: p.expense?.category?.ifBlank { null }?.let { "New cost · $it" } ?: "New cost"
    ProposalKind.TASK -> if (data.task(p.targetId) != null) "Changes a task" else "New task"
}

/** The photo of the item a suggestion is about; a picture of its kind when it is about a cost or a task. */
@Composable
private fun ProposalPicture(p: Proposal, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val data = appData()
    val icon: ImageVector? = when (p.kind) {
        ProposalKind.COST -> Icons.Default.Payments
        ProposalKind.TASK -> Icons.Default.Checklist
        else -> null
    }
    if (icon != null) {
        Box(modifier.glass(RoundedCornerShape(14.dp), shadow = 0.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Night.Mint, modifier = Modifier.fillMaxSize(0.42f))
        }
    } else {
        val item = data.item(p.targetId)
        OwnerThumb(
            p.targetId,
            modifier.clip(RoundedCornerShape(14.dp)).then(if (item != null) Modifier.clickable(onClickLabel = "Open ${item.title()}") { nav.open("item/${item.id}") } else Modifier),
        )
    }
}

// ---- what became of suggestions --------------------------------------------------------------

private fun ProposalState.tone(): GlassTone = when (this) {
    ProposalState.WAITING -> GlassTone.Amber
    ProposalState.APPROVED -> GlassTone.Mint
    ProposalState.DECLINED -> GlassTone.Red
    ProposalState.OVERTAKEN, ProposalState.NOT_CHOSEN -> GlassTone.Plain
}

private fun ProposalState.icon(): ImageVector = when (this) {
    ProposalState.WAITING -> Icons.Default.HourglassTop
    ProposalState.APPROVED -> Icons.Default.CheckCircle
    ProposalState.DECLINED -> Icons.Default.Cancel
    ProposalState.OVERTAKEN -> Icons.Default.SwapHoriz
    ProposalState.NOT_CHOSEN -> Icons.Default.CallSplit
}

/** What became of one suggestion or question, in a word, with who decided. */
private fun outcomeText(data: AppData, p: Proposal): String {
    val by = data.personName(p.decidedBy).ifBlank { "an Organiser" }
    return when (p.state) {
        ProposalState.WAITING -> if (p.kind == ProposalKind.QUESTION) "Waiting for an answer" else "Waiting for approval"
        ProposalState.APPROVED -> (if (p.kind == ProposalKind.QUESTION) "Answered by " else "Approved by ") + by
        ProposalState.DECLINED -> "Declined by $by"
        ProposalState.OVERTAKEN, ProposalState.NOT_CHOSEN -> p.state.label
    }
}

/** For Organisers: the last few suggestions that were decided, folded away. */
@Composable
private fun DecidedLately(data: AppData) {
    val decided = data.liveProposals.filter { it.state != ProposalState.WAITING }.sortedByDescending { it.decidedAt }.take(8)
    if (decided.isEmpty()) return
    Fold("Decided lately", Icons.Default.History, summary = decided.take(2).joinToString(" · ") { data.describe(it) }) {
        decided.forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(data.describe(p), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassChip(outcomeText(data, p), icon = p.state.icon(), tone = p.state.tone())
                    Text(
                        "from " + data.personName(p.by).ifBlank { "someone" }, style = MaterialTheme.typography.bodySmall,
                        color = Night.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (p.reason.isNotBlank()) Text("“${p.reason}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = Night.TextSoft)
            }
        }
    }
}

/** For Deciders and Helpers: everything they suggested or asked, newest first, and how each ended. */
@Composable
private fun Mine() {
    val nav = LocalNav.current
    val data = appData()
    val me = myPerson()
    val helper = myRole() == Role.HELPER
    val mine = data.proposalsBy(me?.id)
    val counts = data.proposalCounts(me?.id)
    Screen(if (helper) "My questions" else "My suggestions", tab = "home", subtitle = data.move.name.ifBlank { "Your move" }) {
        if (mine.isEmpty()) {
            EmptyState(
                if (helper) Icons.Default.HelpOutline else Icons.Default.Lightbulb,
                if (helper) "No questions yet" else "No suggestions yet",
                text = if (helper) "Not sure what should happen to something? Ask from its page and an Organiser answers."
                else "Suggest what happens to a thing from its page. Once an Organiser approves, you can carry it out.",
                action = "Open items", onAction = { nav.goTab("items") },
            )
            return@Screen
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProposalState.entries.forEach { s ->
                    val n = counts[s] ?: 0
                    if (n > 0) GlassChip("$n ${s.label.lowercase()}", icon = s.icon(), tone = s.tone())
                }
            }
            mine.forEach { p -> key(p.id) { OutcomeCard(p, me) } }
        }
    }
}

@Composable
private fun OutcomeCard(p: Proposal, me: Person?) {
    val nav = LocalNav.current
    val data = appData()
    val question = p.kind == ProposalKind.QUESTION
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), sheen = p.state == ProposalState.WAITING) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                ProposalPicture(p, Modifier.size(60.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(data.describe(p), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (question) "Asked " else "Suggested ") + agoText(p.createdAt), style = MaterialTheme.typography.bodySmall,
                        color = Night.TextDim, maxLines = 1,
                    )
                    if (p.note.isNotBlank()) Text("“${p.note}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = Night.TextSoft)
                }
            }
            when (p.state) {
                ProposalState.WAITING -> WaitingTag(p)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlassChip(outcomeText(data, p), icon = p.state.icon(), tone = p.state.tone())
                        if (question && p.state == ProposalState.APPROVED) {
                            data.item(p.targetId)?.let { GlassChip(it.disposition.label, icon = it.disposition.icon()) }
                        }
                    }
                    if (p.reason.isNotBlank()) Text("“${p.reason}”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    if (p.state == ProposalState.OVERTAKEN) {
                        Text("An Organiser decided this directly while it waited.", style = MaterialTheme.typography.bodySmall, color = Night.TextDim)
                    }
                    // An approved suggestion is the go-ahead to carry it out; the item's own page is where that is done.
                    val item = if (p.kind == ProposalKind.ITEM_DECISION && p.state == ProposalState.APPROVED) data.item(p.targetId) else null
                    if (item != null && !item.gone && item.disposition != Disposition.MOVE && data.mayCarryOut(me, item)) {
                        GlassPill(Modifier.fillMaxWidth(), tone = GlassTone.Mint, onClick = { nav.open("item/${item.id}") }) {
                            Icon(item.disposition.icon(), contentDescription = null, tint = Night.MintPale, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text("You can carry it out now", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ChevronRight, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}
