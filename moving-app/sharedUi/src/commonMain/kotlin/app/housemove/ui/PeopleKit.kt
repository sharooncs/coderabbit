package app.housemove.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.ChevronRight
import app.housemove.ui.icons.filled.DeleteOutline
import app.housemove.ui.icons.filled.FrontHand
import app.housemove.ui.icons.filled.Undo
import app.housemove.ui.icons.filled.HourglassTop
import app.housemove.ui.icons.filled.Key
import app.housemove.ui.icons.filled.Lightbulb
import app.housemove.ui.icons.filled.Person
import app.housemove.ui.icons.filled.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.housemove.data.Action
import app.housemove.data.AppData
import app.housemove.data.Outcome
import app.housemove.data.Person
import app.housemove.data.Proposal
import app.housemove.data.ProposalKind
import app.housemove.data.ProposalState
import app.housemove.data.Role
import app.housemove.data.can
import app.housemove.data.me
import app.housemove.data.personName
import app.housemove.data.roleOf
import app.housemove.sync.WifiSync

/*
 * People and roles, as every screen shows them:
 *
 *   Role.icon(), Role.line()   the picture of a role, and one line on what it can do
 *   PersonOrb                  someone's initial in a coloured ball (white for this phone's person)
 *   RoleChip                   a role as a small pill
 *   PersonPill                 a wide pill about one person: who this phone is, and the way to People
 *   WaitingTag                 the amber "Waiting for approval" callout for a suggestion's target, full size or compact,
 *                              with "Withdraw" for the person who made it
 *   waitingLines               the words it says, which a review card says too: one wording everywhere
 *   Proposal.isQuestion, .asksToDelete   a question to answer by deciding, and a request to delete something
 *   allowed(), myRole(), myPerson()  what the Store's rules say, re-read whenever people or roles change
 *   AppData.shared()           more than one person on the move, so roles are worth showing
 *   Outcome.orSay(context)     shows why the Store refused something, and hands back what it saved otherwise
 *   Outcome.okOrSay(context)   the same, for callers that only ask whether it went through
 *   tightScreen()              a narrow screen or large text: stack what would otherwise sit side by side
 *
 * Screens never work out a rule themselves: they ask with [allowed] (or `store.can`).
 */

fun Role.icon(): ImageVector = when (this) {
    Role.ORGANISER -> Icons.Default.Key
    Role.DECIDER -> Icons.Default.Lightbulb
    Role.HELPER -> Icons.Default.FrontHand
}

/** One line on what a role can do. */
fun Role.line(): String = when (this) {
    Role.ORGANISER -> "Runs the move: decides, approves, adds people."
    Role.DECIDER -> "Suggests what to keep, sell or buy. An Organiser approves."
    Role.HELPER -> "Photographs, packs, scans on and off the van, unpacks."
}

/** "an Organiser", "a Helper". */
fun Role.withArticle(): String = (if (this == Role.ORGANISER) "an " else "a ") + label

/** May this phone's person do [action]? Reads the Store's rules, and recomposes when people or roles change. */
@Composable
fun allowed(action: Action): Boolean = appData().can(deviceInfo(), action)

/** This phone's role in the move. */
@Composable
fun myRole(): Role = appData().roleOf(deviceInfo())

/** The person this phone belongs to. */
@Composable
fun myPerson(): Person? = appData().me(deviceInfo())

/** More than one person is on the move, so who may do what is worth showing. With one person it is only clutter. */
fun AppData.shared(): Boolean = livePeople.size > 1

/** The name to show for someone: their own, or a stand-in that still tells people apart. */
fun Person.shownName(): String = name.ifBlank { if (letter.isBlank()) "No name yet" else "Phone $letter" }

/** True on a narrow screen or with large text, where things side by side would break words: stack them instead. */
@Composable
fun tightScreen(): Boolean = screenDp().let { it.screenWidthDp / it.fontScale < 380f }

/** Shows why the Store refused, and hands back what it saved when it did not. Never fails silently. */
fun <T> Outcome<T>.orSay(context: Platform): T? {
    if (this is Outcome.Refused) toast(context, reason)
    return valueOrNull()
}

/** Shows why the Store refused. True when the call went through. */
fun <T> Outcome<T>.okOrSay(context: Platform): Boolean {
    if (this is Outcome.Refused) toast(context, reason)
    return ok
}

/**
 * The addresses another phone can reach this one at, for the code it shows: the same ones
 * [WifiSync.networkProblem] asks about, so none means no Wi-Fi ([WifiSync.NO_NETWORK]).
 * Tests provide "127.0.0.1" so two stores can meet on one machine.
 */
val LocalSyncAddresses = staticCompositionLocalOf<() -> List<String>> { { WifiSync.addresses() } }

/** What an invite's QR picture says, for tests that stand in for the camera. Screen readers do not read it. */
val InviteText = SemanticsPropertyKey<String>("InviteText")

// ---- a person as a ball ------------------------------------------------------------------

private val PERSON_TINTS = listOf(
    Color(0xFFB7A6FF), Color(0xFF8CC6FF), Color(0xFFFFB98A), Color(0xFFFF9FC6), Color(0xFFC3E68C), Color(0xFF7FDCE0), Color(0xFFFFDB8A),
)

/** The colour that stands for a person, from their letter, so it is the same on every phone. */
fun personTint(p: Person?): Color {
    val key = p?.letter?.trim()?.firstOrNull()?.uppercaseChar()?.let { it - 'A' } ?: (p?.id?.hashCode() ?: 0)
    return PERSON_TINTS[key.mod(PERSON_TINTS.size)]
}

/** A glass ball in one colour, lit from the upper left like the orbs in Glass.kt. */
private fun DrawScope.drawTintedOrb(tint: Color, lit: Boolean = true) {
    val d = size.minDimension
    val r = d / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val hot = Offset(c.x - r + d * 0.30f, c.y - r + d * 0.22f)
    val ball = Path().apply { addOval(Rect(c.x - r, c.y - r, c.x + r, c.y + r)) }
    clipPath(ball, ClipOp.Difference) {
        val sc = Offset(c.x, c.y + d * 0.12f)
        drawCircle(
            Brush.radialGradient(0f to Night.Shadow.copy(alpha = 0.5f), 0.62f to Night.Shadow.copy(alpha = 0.36f), 1f to Color.Transparent, center = sc, radius = r * 1.4f),
            r * 1.4f, sc,
        )
    }
    drawCircle(
        Brush.radialGradient(
            0f to lerp(Color.White, tint, if (lit) 0.15f else 0.7f), 0.45f to tint, 1f to lerp(tint, Color(0xFF1B2740), 0.45f),
            center = hot, radius = d * 1.05f,
        ),
        r, c,
    )
    val w = 1.dp.toPx()
    drawCircle(
        Brush.verticalGradient(0f to Color.White.copy(alpha = if (lit) 0.85f else 0.4f), 0.5f to Color.White.copy(alpha = 0.1f), 1f to Color.White.copy(alpha = 0.1f), startY = c.y - r, endY = c.y + r),
        r - w / 2f, c, style = Stroke(w),
    )
}

/**
 * Someone on the move: their initial in a ball of their own colour. The person this phone
 * belongs to ([me]) is the white ball, as in the top bar of Home. Someone removed is dimmed.
 * Decoration only: say the name next to it.
 */
@Composable
fun PersonOrb(person: Person?, modifier: Modifier = Modifier, size: Dp = 44.dp, me: Boolean = false) {
    val initial = person?.name?.trim()?.firstOrNull()?.uppercaseChar()
    val gone = person?.removed == true
    // Someone removed is a dull slate ball: drawn that way, not faded, so its shadow stays whole.
    val tint = if (gone) Night.SlateDeep else personTint(person)
    val ink = if (gone) Night.Text else Night.OnWhite
    // The letter is sized to the ball, not to the phone's text size: the ball does not grow with large text.
    val letter = with(LocalDensity.current) { (size * 0.4f).toSp() }
    Box(
        modifier.size(size).drawBehind { if (me && !gone) drawOrb(OrbKind.Selected, halo = false) else drawTintedOrb(tint, lit = !gone) },
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null) Text(initial.toString(), color = ink, fontSize = letter, lineHeight = letter * 1.25f, fontWeight = FontWeight.SemiBold, maxLines = 1)
        else Icon(Icons.Default.Person, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.5f))
    }
}

/** A role as a small pill with its picture. */
@Composable
fun RoleChip(role: Role, modifier: Modifier = Modifier, tone: GlassTone = GlassTone.Plain) {
    GlassChip(role.label, modifier, icon = role.icon(), tone = tone)
}

/**
 * A wide glass pill about one person: their ball, a line or two, and their role. On a
 * narrow screen or with large text the role moves under the lines, so nothing is cut
 * short. With [onClick] it is the way to somewhere (People, usually) and ends in an arrow.
 */
@Composable
fun PersonPill(
    person: Person?,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    me: Boolean = true,
    role: Role? = person?.role,
    sheen: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val tight = tightScreen()
    GlassPill(
        modifier.fillMaxWidth(), onClick = onClick, sheen = sheen,
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 12.dp),
    ) {
        PersonOrb(person, size = 44.dp, me = me)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Night.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (tight && role != null) RoleChip(role, Modifier.padding(top = 2.dp))
        }
        if (!tight && role != null) {
            Spacer(Modifier.width(8.dp))
            RoleChip(role)
        }
        if (onClick != null) Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Night.TextDim, modifier = Modifier.padding(start = 2.dp))
    }
}

/**
 * A question about what should happen to an item, answered by deciding it. A request to
 * delete something is made as a question too ([Proposal.deletes]), but it is approved or
 * declined like a suggestion, so it is not one of these.
 */
val Proposal.isQuestion: Boolean get() = kind == ProposalKind.QUESTION && !deletes

/** Asks for something to be deleted: a request to delete it ([Store.askToDelete]), or a suggested change that deletes a cost or a task. */
val Proposal.asksToDelete: Boolean
    get() = deletes || (kind == ProposalKind.COST && expense?.deleted == true) || (kind == ProposalKind.TASK && task?.deleted == true)

/**
 * The two lines a waiting suggestion, question or request is announced with, in the same
 * words wherever it is shown (on its target, in a review card): that it waits, and who
 * wants what.
 *
 *   "Waiting for approval"   "Suggested by Dee", or with [what] "Dee: Sell for £ 80"
 *   "Waiting for approval"   "Hal asked to delete it", "Dee suggested deleting it"
 *   "Waiting for an answer"  "Hal asked: one leg is broken"
 *
 * The person this phone belongs to ([meId]) is "you".
 */
fun waitingLines(p: Proposal, data: AppData, meId: String?, what: String?): Pair<String, String> {
    val who = if (meId != null && p.by == meId) "you" else data.personName(p.by).ifBlank { "someone" }
    val name = who.replaceFirstChar { it.uppercase() }
    return when {
        p.deletes -> "Waiting for approval" to "$name asked to delete it"
        p.asksToDelete -> "Waiting for approval" to "$name suggested deleting it"
        p.isQuestion -> "Waiting for an answer" to (name + " asked" + if (p.note.isBlank()) "" else ": " + p.note)
        else -> "Waiting for approval" to if (what.isNullOrBlank()) "Suggested by $who" else "$name: $what"
    }
}

/** The picture of something waiting: a question mark for a question, a bin for a request to delete, an hourglass for a suggestion. */
fun Proposal.waitingIcon(): ImageVector = when {
    asksToDelete -> Icons.Default.DeleteOutline
    isQuestion -> Icons.Default.QuestionMark
    else -> Icons.Default.HourglassTop
}

/**
 * The amber callout on anything with a suggestion, question or request waiting, in the
 * words of [waitingLines]. [what] is what it would change, when the row it sits on does
 * not already say so. The person who made it can take it back here with "Withdraw".
 * [compact] is the same callout drawn smaller, for a card in a grid or a row of a list;
 * it leaves Withdraw to the page the card opens.
 */
@Composable
fun WaitingTag(proposal: Proposal, modifier: Modifier = Modifier, compact: Boolean = false, what: String? = null) {
    val me = deviceInfo().personId
    val (title, value) = waitingLines(proposal, appData(), me, what)
    val icon = proposal.waitingIcon()
    val mine = me.isNotEmpty() && proposal.by == me && proposal.state == ProposalState.WAITING
    if (compact) {
        // In a narrow card the hourglass and one word say that it waits; who wants what gets the room.
        Row(
            modifier.glass(RoundedCornerShape(16.dp), shadow = 4.dp).semantics(mergeDescendants = true) { contentDescription = "$title. $value" }
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(24.dp).drawBehind { drawOrb(OrbKind.Amber, halo = false) }, contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Night.OnAmber, modifier = Modifier.size(13.dp))
            }
            Text(
                value, color = Night.Text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
        }
        return
    }
    val store = LocalStore.current
    val context = LocalPlatform.current
    Row(
        modifier.glass(RoundedCornerShape(24.dp), shadow = 8.dp).padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = if (mine) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(36.dp).drawBehind { drawOrb(OrbKind.Amber, ring = false) }, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Night.OnAmber, modifier = Modifier.size(18.dp))
        }
        // With Withdraw at its end the tag takes the width, so the button sits at the far side.
        Column(Modifier.weight(1f, fill = mine)) {
            Text(title, color = Night.AmberPale, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, color = Night.Text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!proposal.isQuestion && proposal.note.isNotBlank()) {
                Text("“${proposal.note}”", style = MaterialTheme.typography.bodySmall, color = Night.TextSoft)
            }
        }
        if (mine) {
            PillButton(
                "Withdraw", primary = false, icon = Icons.Default.Undo,
                onClick = { if (store.withdraw(proposal.id).okOrSay(context)) toast(context, "Withdrawn") },
            )
        }
    }
}
