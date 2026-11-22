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
        val store = (application as App).store
        setContent { AppRoot(store) }
    }

    override fun onStop() {
        super.onStop()
        // The list of moves follows what happened in the one in use (a join changes which move it is).
        (application as App).moves.refresh()
        (application as App).moves.flush()
    }
}
