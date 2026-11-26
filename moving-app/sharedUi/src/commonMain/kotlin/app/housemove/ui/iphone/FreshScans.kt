package app.housemove.ui.iphone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.housemove.sync.MailboxSync
import app.housemove.sync.WifiSync
import app.housemove.ui.LocalPlatform
import app.housemove.ui.ScanDialog
import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.QrCodeScanner

/*
 * Get fresh data's own scanner (step 3a of design/ios-plan.md): where the iPhone has a camera HouseMove may use, the
 * Android phone's sync code and the internet mailbox's code are scanned in the app, as Android's Sync and backup scans
 * them (SyncScreen, MailboxUi). The iPhone's Camera app (Open in HouseMove) and pasting still work as before.
 */

/** What is said when the scanner reads a code that is not one for getting fresh data. */
internal const val NOT_A_SYNC_CODE = "That is not a sync code. On the Android phone tap More, Sync and backup, Show sync code."

/**
 * "Scan the Android phone's code": its sync code (Show sync code) gets fresh data at once, as when it is pasted. The
 * mailbox's code is taken too. Anything else is said and the scanner stays open, as on Android.
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
                if (WifiSync.Invite.parse(Words.cleanCode(text)) != null || MailboxSync.isCode(text)) {
                    scanning = false
                    model.use(text)
                } else platform.toast(NOT_A_SYNC_CODE)
            },
            onClose = { scanning = false },
            raw = true,
            typeLabel = "Or paste the code",
            // A sync code is far too long to type: the field waits behind a chip, as on Android.
            typingFolded = true,
        )
    }
}

/** An Organiser's "Scan the mailbox's code" (or "Scan a new code" once there is one), as on Android's Sync and backup. */
@Composable
internal fun ScanMailboxCode(model: AppModel, hasOne: Boolean) {
    val platform = LocalPlatform.current
    if (!platform.camera) return
    var scanning by remember { mutableStateOf(false) }
    SmallButton(if (hasOne) "Scan a new code" else "Scan the mailbox's code", Icons.Filled.QrCodeScanner, onClick = { scanning = true })
    if (scanning) {
        ScanDialog(
            title = "Scan the code the mailbox laptop shows",
            onCode = { text ->
                if (MailboxSync.isCode(text)) {
                    scanning = false
                    model.use(text)
                } else platform.toast(MailboxSync.NOT_A_CODE)
            },
            onClose = { scanning = false },
            raw = true,
            typeLabel = "Or paste the code",
            typingFolded = true,
        )
    }
}
