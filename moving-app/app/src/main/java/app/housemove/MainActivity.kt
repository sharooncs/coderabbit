package app.housemove

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import app.housemove.ui.AppRoot
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val moves = (application as App).moves
        if (savedInstanceState == null) takeUpdate(intent)
        // The move used last opens; a phone with none opens on "Start a new move" and "Join a move".
        setContent { AppRoot(moves, autoSync = (application as App).autoSync, mailbox = (application as App).mailbox) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeUpdate(intent)
    }

    /** An update file opened from a messaging app or a file manager: the screens open it into its move. */
    private fun takeUpdate(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        if (uri != null) (application as App).incomingUpdate.value = uri
    }

    override fun onStart() {
        super.onStart()
        // On open: the daily small backup, and letting go of what this phone no longer needs. Away from the screen.
        val moves = (application as App).moves
        thread(name = "check-in", isDaemon = true) { moves.checkIn() }
        // On screen: the phones of the move on the same Wi-Fi find each other and sync by themselves.
        (application as App).autoSync.resume()
        // And those away from it, through the internet mailbox, when the move has one.
        (application as App).mailbox.resume()
    }

    override fun onStop() {
        super.onStop()
        // Off screen: no advertising, listening or syncing by itself until the app is opened again.
        (application as App).autoSync.pause()
        (application as App).mailbox.pause()
        // The list of moves follows what happened in the one in use (a join changes which move it is).
        val moves = (application as App).moves
        moves.refresh()
        moves.flush()
        // Going to the background: the daily small backup, if today's is not written yet.
        thread(name = "check-in", isDaemon = true) { moves.checkIn() }
    }
}
