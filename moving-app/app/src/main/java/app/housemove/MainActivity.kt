package app.housemove

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.housemove.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val moves = (application as App).moves
        // The move used last opens; a phone with none opens on "Start a new move" and "Join a move".
        setContent { AppRoot(moves) }
    }

    override fun onStop() {
        super.onStop()
        // The list of moves follows what happened in the one in use (a join changes which move it is).
        (application as App).moves.refresh()
        (application as App).moves.flush()
    }
}
