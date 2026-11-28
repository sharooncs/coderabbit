package app.housemove.ui.iphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.housemove.ui.LocalPlatform
import app.housemove.ui.ScanDialog
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.QrCodeScanner

/*
 * Get fresh data's own scanner (step 3a of design/ios-plan.md): where the iPhone has a camera HouseMove may use, the
 * Android phone's sync code and the internet mailbox's code are scanned in the app, as Android's Sync and backup scans
 * them (SyncScreen, MailboxUi). The iPhone's Camera app (Open in HouseMove) and pasting still work as before.
 */

/**
 * "Scan the Android phone's code": its sync code (Show sync code) gets fresh data at once, as when it is pasted; the
 * mailbox's code is taken too. The scanner closes on the first code it reads, whatever it is, and Get fresh data says
 * what came of it ("That is not a sync code", "That is an invite"). Android keeps scanning and says it in a toast; on
 * the iPhone a toast shows under the scanner, which fills the screen, so it is said on the screen instead.
 */
@Composable
internal fun ScanSyncCode(model: AppModel) {
    val platform = LocalPlatform.current
    if (!platform.camera) return
    var scanning by remember { mutableStateOf(false) }
    BigButton("Scan the Android phone's code", Icons.Filled.QrCodeScanner, onClick = { scanning = true }, primary = false)
    if (scanning) {
        ScanDialog(
            title = "Scan the sync code on the Android phone",
            onCode = { text ->
                scanning = false
                model.use(text)
            },
            onClose = { scanning = false },
            raw = true,
            typeLabel = "Or paste the code",
            // A sync code is far too long to type: the field waits behind a chip, as on Android.
            typingFolded = true,
        )
    }
}

/**
 * An Organiser's "Scan the mailbox's code" (or "Scan a new code" once there is one), as on Android's Sync and backup.
 * As above, the scanner closes on the first code it reads and Get fresh data says what came of it ("Internet mailbox
 * set up…", or that it is not the mailbox's code).
 */
@Composable
internal fun ScanMailboxCode(model: AppModel, hasOne: Boolean) {
    if (!LocalPlatform.current.camera) return
    var scanning by remember { mutableStateOf(false) }
    SmallButton(if (hasOne) "Scan a new code" else "Scan the mailbox's code", Icons.Filled.QrCodeScanner, onClick = { scanning = true })
    if (scanning) {
        ScanDialog(
            title = "Scan the code the mailbox laptop shows",
            onCode = { text ->
                scanning = false
                model.useMailboxCode(text)
            },
            onClose = { scanning = false },
            raw = true,
            typeLabel = "Or paste the code",
            typingFolded = true,
        )
    }
}
