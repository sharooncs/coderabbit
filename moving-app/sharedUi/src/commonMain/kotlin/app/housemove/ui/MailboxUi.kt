package app.housemove.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.CloudDone
import app.housemove.ui.icons.filled.CloudOff
import app.housemove.ui.icons.filled.CloudQueue
import app.housemove.ui.icons.filled.CloudSync
import app.housemove.ui.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.housemove.data.Action
import app.housemove.sync.MailboxSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Syncing through the internet mailbox ([MailboxSync], design/encryption.md, "Mailbox as built"), while the app is on
 * screen. Null where the app is shown without it (most tests): the section then says what the move and the switch say.
 */
val LocalMailbox = staticCompositionLocalOf<MailboxSync?> { null }

/**
 * Sync and backup's "Internet mailbox": how it stands ("Up to date over the internet · 2 min ago", "No internet mailbox
 * yet", or what went wrong, in plain words), one hint, "Scan the mailbox's code" for an Organiser (to set it up, or after
 * the laptop made a new code), and the switch for this phone.
 */
@Composable
fun MailboxSection() {
    val store = LocalStore.current
    val engine = LocalMailbox.current
    val context = LocalPlatform.current
    val settings by store.phone.settings.collectAsState()
    val data = appData()
    val organiser = allowed(Action.MOVE_SETTINGS)
    val flow = remember(engine) { engine?.status ?: MutableStateFlow(MailboxSync.Status(MailboxSync.State.NOT_SET_UP)) }
    val live by flow.collectAsState()
    // What the move and the switch say come first: they hold even before the engine has looked.
    val status = when {
        !settings.mailbox -> MailboxSync.Status(MailboxSync.State.OFF)
        data.mailbox.url.isBlank() -> MailboxSync.Status(MailboxSync.State.NOT_SET_UP)
        engine == null -> MailboxSync.Status(MailboxSync.State.ALONE)
        live.state == MailboxSync.State.NOT_SET_UP || live.state == MailboxSync.State.OFF -> MailboxSync.Status(MailboxSync.State.WORKING)
        else -> live
    }
    var scanning by remember { mutableStateOf(false) }
    // "2 min ago" moves on with the clock.
    var now by remember { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = nowMillis()
            delay(15_000)
        }
    }
    Section("Internet mailbox") {
        Row(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val (icon, tint) = when (status.state) {
                MailboxSync.State.UP_TO_DATE -> Icons.Default.CloudDone to MaterialTheme.colorScheme.tertiary
                MailboxSync.State.WORKING -> Icons.Default.CloudSync to MaterialTheme.colorScheme.primary
                MailboxSync.State.OFF, MailboxSync.State.PROBLEM -> Icons.Default.CloudOff to MaterialTheme.colorScheme.onSurfaceVariant
                else -> Icons.Default.CloudQueue to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(status.line(now), style = MaterialTheme.typography.titleSmall)
                status.hint(now, organiser)?.let { Hint(it) }
            }
        }
        if (organiser && settings.mailbox) {
            GlassChip(
                if (data.mailbox.url.isBlank()) "Scan the mailbox's code" else "Scan a new code",
                icon = Icons.Default.QrCodeScanner, onClick = { scanning = true },
            )
        }
        SwitchRow(
            "Internet mailbox on this phone", settings.mailbox,
            { on ->
                store.phone.updateSettings { it.copy(mailbox = on) }
                engine?.poke()
            },
            sub = "Phones away from this Wi-Fi keep up to date through the family laptop. It only ever holds sealed messages.",
        )
    }
    if (scanning) {
        ScanDialog(
            title = "Scan the code the mailbox laptop shows",
            raw = true,
            typingFolded = true,
            typeLabel = "Or paste the code",
            onClose = { scanning = false },
            onCode = { text ->
                if (!MailboxSync.isCode(text)) {
                    toast(context, MailboxSync.NOT_A_CODE)
                } else {
                    val (ok, said) = MailboxSync.setUp(store, text)
                    scanning = false
                    toast(context, said)
                    if (ok) engine?.poke()
                }
            },
        )
    }
}
