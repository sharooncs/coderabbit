package app.housemove.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.housemove.data.*
import app.housemove.pdf.Share
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The moves on this phone ([Moves]): listed, opened, started, joined, restored and removed by the
 * screens for them. Null where the app is shown with a single move on its own (most tests): those
 * screens then work on that one move.
 */
val LocalMoves = staticCompositionLocalOf<Moves?> { null }

/** Said on Home once, after this phone joined or restored a move: who it is now and what that role does. */
data class HomeBanner(val moveId: String, val title: String, val text: String, val personId: String = "")

/**
 * What the app keeps while it runs, across a change of move (which replaces every screen):
 * the banner for Home, where the next move's screens open, and an invite scanned somewhere
 * else for the join screen to ask about. [scope] outlives the screens, for work such as a
 * join that ends by opening another move.
 */
class AppState(val scope: CoroutineScope) {
    var banner by mutableStateOf<HomeBanner?>(null)

    /** Where the screens of the move opened next start, after Home; used once. */
    var pendingRoute: String? = null

    /** An invite scanned on another screen, for the join screen to ask about first. */
    var pendingInvite: String? = null

    /** The move to go back to if a new one is given up before it is set up. */
    var returnTo: String? = null

    /** Take [pendingRoute], once. */
    fun takeRoute(): String? = pendingRoute.also { pendingRoute = null }
}

val LocalAppState = staticCompositionLocalOf<AppState?> { null }

@Composable
fun appState(): AppState = LocalAppState.current ?: error("AppState not provided")

// ---- a move, as the list of moves shows it --------------------------------------------------

/**
 * How far a move has got, from 0 to 1: the share of what it uses that is done (things decided,
 * boxes sealed, loaded, delivered, unpacked). A move with nothing in it yet is at 0.
 */
fun AppData.doneFraction(): Float {
    val p = progress()
    val parts = listOfNotNull(
        p.triage.takeIf { uses(Part.TRIAGE) },
        p.sealed.takeIf { uses(Part.BOXES) },
        p.loaded.takeIf { uses(Part.MOVE_DAY) },
        p.delivered.takeIf { uses(Part.MOVE_DAY) },
        p.unpacked.takeIf { uses(Part.UNPACK) },
    ).filter { it.total > 0 }
    if (parts.isEmpty()) return 0f
    return parts.map { it.fraction }.average().toFloat()
}

/** "9 days to go", "Tomorrow", "Today", "3 days ago"; null for a move with no date (or one that has none: a list of things). */
fun AppData.daysToGoText(today: Long = app.housemove.data.today()): String? {
    if (move.kind == MoveKind.INVENTORY) return null
    val d = (move.moveDay ?: return null) - today
    return when {
        d > 1 -> "$d days to go"
        d == 1L -> "Tomorrow"
        d == 0L -> "Today"
        d == -1L -> "Yesterday"
        else -> "${-d} days ago"
    }
}

/**
 * Does this move need its person now: suggestions waiting for someone who approves them, or
 * something urgent for someone who decides. Used for the dot by the move's name.
 */
fun Store.needsYou(): Boolean {
    val d = data.value
    val dev = device.value
    if (!d.move.setupDone) return false
    if (d.can(dev, Action.APPROVE) && d.waitingCount() > 0) return true
    return d.can(dev, Action.DECIDE) && d.issues().any { it.severe }
}

/** Finished: put away under "Earlier moves", or everything in place a fortnight after move day. */
fun AppData.finished(today: Long = app.housemove.data.today()): Boolean {
    val day = move.moveDay ?: return false
    if (today - day < 14) return false
    val mv = movables()
    return mv.isNotEmpty() && mv.all { it.stage == Stage.DELIVERED || it.stage == Stage.DONE }
}

/**
 * The other moves on this phone that need their person ([needsYou]), for the dot by the move's
 * name. Worked out again when the list of moves changes; the other moves do not change while
 * this one is open.
 */
@Composable
fun otherMovesNeedingYou(): List<MoveCard> {
    val moves = LocalMoves.current ?: return emptyList()
    val cards by moves.cards.collectAsState()
    val here = LocalStore.current.moveId
    return remember(cards, here) {
        cards.filter { c -> !c.archived && !c.matches(here) && (moves.store(c.moveId)?.needsYou() == true) }
    }
}

// ---- sharing the app ------------------------------------------------------------------------

private const val APK = "application/vnd.android.package-archive"

/** Where the installed app is read from. Tests put a small file in its place. */
@Volatile
internal var installedApk: (Context) -> File = { File(it.applicationInfo.sourceDir) }

/** What hands a file to the share sheet. Tests see what would be sent. */
@Volatile
internal var shareSheet: (Context, File, String) -> Unit = { context, file, type -> Share.send(context, file, type) }

/**
 * Send this phone's copy of the app through the share sheet, which reaches Quick Share (no
 * internet needed), for a phone that has an older HouseMove or none. The installed file is
 * copied to the shared cache folder first, which the app's FileProvider serves.
 */
fun shareTheApp(context: Context, scope: CoroutineScope) {
    // The share sheet and any message are on the main thread, whatever thread the scope resumes on.
    scope.launch(Dispatchers.Main) {
        val file = withContext(Dispatchers.IO) {
            try {
                Share.outFile(context, "HouseMove.apk").also { out -> installedApk(context).copyTo(out, overwrite = true) }
            } catch (_: Exception) {
                null
            }
        }
        val sent = file != null && try {
            shareSheet(context, file, APK)
            true
        } catch (_: Exception) {
            false
        }
        if (!sent) toast(context, "This phone could not get the app ready to share. Try again in a moment.")
    }
}
