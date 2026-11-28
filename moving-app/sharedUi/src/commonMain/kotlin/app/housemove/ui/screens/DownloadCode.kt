package app.housemove.ui.screens

import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.filled.QrCode2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.housemove.ui.GlassChip
import app.housemove.ui.GlassDialog
import app.housemove.ui.IPHONE_GETS_IT
import app.housemove.ui.Night
import app.housemove.ui.PillButton

/*
 * "Show a download code", beside "Share the app": a QR code of the address the app can be downloaded
 * from, for a phone with internet. Share the app sends this phone's own copy without internet.
 */

/** Where a phone with internet downloads HouseMove. The one place the address is written: change it here. */
const val APP_DOWNLOAD_URL = "https://github.com/sharooncs/coderabbit/raw/claude/intelligent-brown-mk8754/releases/HouseMove-latest.apk"

/** Said with the download code. */
const val APP_DOWNLOAD_NOTE = "Needs internet on their phone. Without internet, use Share the app."

const val SHOW_DOWNLOAD_CODE = "Show a download code"

/** "Show a download code" as a chip ([chip]) or as a clear pill, to sit beside "Share the app"; it opens the code. */
@Composable
internal fun DownloadCodeButton(modifier: Modifier = Modifier, chip: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (chip) GlassChip(SHOW_DOWNLOAD_CODE, modifier, icon = Icons.Default.QrCode2, onClick = { open = true })
    else PillButton(SHOW_DOWNLOAD_CODE, onClick = { open = true }, modifier = modifier, icon = Icons.Default.QrCode2, primary = false)
    if (open) DownloadCodeDialog(onClose = { open = false })
}

/** The download code, large on white, with the one thing to know about it. */
@Composable
internal fun DownloadCodeDialog(onClose: () -> Unit) {
    GlassDialog(
        "Download HouseMove", onClose, icon = Icons.Default.QrCode2,
        buttons = { PillButton("Done", onClick = onClose) },
    ) {
        CodeImage(APP_DOWNLOAD_URL, "Download code for HouseMove")
        Text("On their phone: point the camera here and open the link.", style = MaterialTheme.typography.bodyMedium)
        Text(APP_DOWNLOAD_NOTE, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
        // The code is Android's app: an iPhone cannot install it.
        Text(IPHONE_GETS_IT, style = MaterialTheme.typography.bodyMedium, color = Night.TextSoft)
    }
}
