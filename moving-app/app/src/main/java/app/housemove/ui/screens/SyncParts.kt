package app.housemove.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.data.Person
import app.housemove.data.Store
import app.housemove.sync.WifiSync
import app.housemove.ui.GlassCard
import app.housemove.ui.GlassTone
import app.housemove.ui.LocalStore
import app.housemove.ui.PersonOrb
import app.housemove.ui.RoleChip
import app.housemove.ui.accent
import app.housemove.ui.line
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What came of two phones meeting, in words for the screen. */
class SyncReport(
    val ok: Boolean,
    val title: String,
    val text: String,
    /** Set when this phone has just become someone: the invite it scanned was made for them. */
    val joinedAs: Person? = null,
)

/**
 * Did the two phones take in each other's lists? Also true when only some photos were still
 * on their way as the connection dropped ([WifiSync.PHOTOS_CUT]): that is a sync, with the
 * rest of the photos to come next time.
 */
fun WifiSync.Result.listsSynced(): Boolean = ok || error == WifiSync.PHOTOS_CUT

/**
 * Turn what [WifiSync] reported into something to show. Every message comes from WifiSync
 * itself, already in plain words; this only gives each a short heading.
 */
fun syncReport(r: WifiSync.Result, store: Store): SyncReport {
    if (r.error == WifiSync.PHOTOS_CUT) {
        return SyncReport(true, "Synced with ${r.peerName.ifBlank { "the other phone" }}, all but some photos", r.error)
    }
    if (!r.ok) {
        val title = when (r.error) {
            WifiSync.INVITE_EXPIRED -> "This invite no longer works"
            WifiSync.REMOVED_SELF -> "Removed from this move"
            WifiSync.NOT_ADDED -> "Not added to this move yet"
            WifiSync.UNKNOWN_PHONE -> "That phone is not on this move"
            WifiSync.VERSION_MISMATCH -> "Different versions"
            WifiSync.NO_NETWORK -> "No Wi-Fi"
            WifiSync.UNREACHABLE -> "No answer from the other phone"
            WifiSync.CONNECTION_LOST -> "The connection dropped"
            WifiSync.CODE_NOT_ACCEPTED -> "Code not accepted"
            WifiSync.WRONG_CODE -> "Someone used the wrong code"
            WifiSync.NOT_HOUSEMOVE -> "Not a HouseMove phone"
            WifiSync.UNREADABLE -> "Could not read the other phone"
            else -> "Could not sync"
        }
        return SyncReport(false, title, r.error ?: "")
    }
    val photos = if (r.photosSent + r.photosReceived == 0) "Everything is up to date."
    else "Photos sent ${r.photosSent}, received ${r.photosReceived}."
    val who = r.joinedAs
    if (who != null) {
        return SyncReport(true, "You are ${who.name.ifBlank { "on the move" }}, ${who.role.label}", who.role.line(), who)
    }
    val myLetter = store.device.value.letter
    val clash = if (r.peerLetter.isNotBlank() && r.peerLetter.equals(myLetter, ignoreCase = true)) {
        " Both phones use the letter $myLetter for handwritten codes: change it on one of them in Settings."
    } else ""
    return SyncReport(true, "Synced with ${r.peerName.ifBlank { "the other phone" }}", photos + clash)
}

/** The result of a sync or a join as a panel: mint when it worked, red with the reason when it did not. */
@Composable
fun SyncReportCard(report: SyncReport, modifier: Modifier = Modifier) {
    val tone = if (report.ok) GlassTone.Mint else GlassTone.Red
    GlassCard(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, tone = tone, shape = RoundedCornerShape(26.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val who = report.joinedAs
            if (who != null) PersonOrb(who, size = 52.dp, me = true)
            else Icon(
                if (report.ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline, contentDescription = null,
                tint = tone.accent(), modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(report.title, style = MaterialTheme.typography.titleMedium)
                if (report.text.isNotBlank()) Text(report.text, style = MaterialTheme.typography.bodyMedium)
                if (who != null) RoleChip(who.role, tone = GlassTone.Mint)
            }
        }
    }
}

/**
 * Scans lead here: connect to the phone whose code was scanned, then sync with it or,
 * when the code is an invite made for one person, become that person.
 * A phone that is not on a move yet is let in by a plain code only while that move has
 * a single person; after that the way in is an invite, so everyone has a role.
 */
class Joiner(private val store: Store, private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var report by mutableStateOf<SyncReport?>(null)

    /** False when [text] is not a sync code or invite at all. */
    fun join(text: String): Boolean {
        val invite = WifiSync.Invite.parse(text) ?: return false
        if (busy) return true
        busy = true
        report = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { WifiSync.join(store, invite, letNewPhonesIn = store.isAlone()) }
            if (r.listsSynced()) store.setDevice { it.copy(lastSyncAt = System.currentTimeMillis()) }
            report = syncReport(r, store)
            busy = false
        }
        return true
    }
}

@Composable
fun rememberJoiner(): Joiner {
    val store = LocalStore.current
    val scope = rememberCoroutineScope()
    return remember(store) { Joiner(store, scope) }
}
