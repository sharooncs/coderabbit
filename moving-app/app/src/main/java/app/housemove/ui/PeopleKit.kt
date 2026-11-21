package app.housemove.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.housemove.data.Action
import app.housemove.data.AppData
import app.housemove.data.Outcome
import app.housemove.data.Person
import app.housemove.data.Proposal
import app.housemove.data.ProposalKind
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
 *   WaitingTag                 the amber "Waiting for approval" callout for a suggestion's target
 *   allowed(), myRole(), myPerson()  what the Store's rules say, re-read whenever people or roles change
 *   AppData.shared()           more than one person on the move, so roles are worth showing
 *   Outcome.orSay(context)     shows why the Store refused something
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
fun tightScreen(): Boolean = LocalConfiguration.current.let { it.screenWidthDp / it.fontScale < 380f }

/** Shows why the Store refused, and hands back what it saved when it did not. Never fails silently. */
fun <T> Outcome<T>.orSay(context: Context): T? {
    if (this is Outcome.Refused) toast(context, reason)
    return valueOrNull()
}

/**
 * The addresses another phone can reach this one at, for the code it shows.
 * Tests provide "127.0.0.1" so two stores can meet on one machine.
 */
val LocalSyncAddresses = staticCompositionLocalOf<() -> List<String>> { { WifiSync.localAddresses() } }

/** What an invite's QR picture says, for tests that stand in for the camera. Screen readers do not read it. */
val InviteText = SemanticsPropertyKey<String>("InviteText")

// ---- a person as a ball ------------------------------------------------------------------

private val PERSON_TINTS = listOf(
    Color(0xFFB7A6FF), Color(0xFF8CC6FF), Color(0xFFFFB98A), Color(0xFFFF9FC6), Color(0xFFC3E68C), Color(0xFF7FDCE0), Color(0xFFFFDB8A),
)

/** The colour that stands for a person, from their letter, so it is the same on every phone. */
fun personTint(p: Person?): Color {
    val key = p?.letter?.trim()?.firstOrNull()?.uppercaseChar()?.let { it - 'A' } ?: (p?.id?.hashCode() ?: 0)
    return PERSON_TINTS[Math.floorMod(key, PERSON_TINTS.size)]
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
    val ink = if (gone) Night.TextDim else Night.OnWhite
    Box(
        modifier.size(size).drawBehind { if (me && !gone) drawOrb(OrbKind.Selected, halo = false) else drawTintedOrb(tint, lit = !gone) },
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null) Text(initial.toString(), color = ink, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        else Icon(Icons.Default.Person, contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.5f))
    }
}

/** A role as a small pill with its picture. */
@Composable
fun RoleChip(role: Role, modifier: Modifier = Modifier, tone: GlassTone = GlassTone.Plain) {
    GlassChip(role.label, modifier, icon = role.icon(), tone = tone)
}

/**
 * The amber callout that marks the target of a suggestion nobody has decided on yet:
 * "Waiting for approval", and who suggested it. Tapping it opens Approvals.
 */
@Composable
fun WaitingTag(proposal: Proposal, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val who = if (proposal.by == deviceInfo().personId) "you" else appData().personName(proposal.by).ifBlank { "someone" }
    val question = proposal.kind == ProposalKind.QUESTION
    GlassTag(
        if (question) "Waiting for an answer" else "Waiting for approval", if (question) "Asked by $who" else "Suggested by $who",
        Icons.Default.HourglassTop, modifier, kind = OrbKind.Amber, onClick = { nav.open("approvals") },
    )
}
