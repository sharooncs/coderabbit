package app.housemove.ui.iphone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import app.housemove.data.Store
import app.housemove.ui.AppBackground
import app.housemove.ui.AppState
import app.housemove.ui.HomeBanner
import app.housemove.ui.HouseMoveTheme
import app.housemove.ui.LocalAppState
import app.housemove.ui.LocalAutoSync
import app.housemove.ui.LocalMailbox
import app.housemove.ui.LocalPlatform
import app.housemove.ui.LocalSyncScreen
import app.housemove.ui.MoveRoot
import app.housemove.ui.open

/**
 * The iPhone app: joining a move that lives on an Android phone (the join screens here), then the move itself in the
 * shared app (step 2 of design/ios-plan.md): Home, rooms, items, boxes, containers, search, people and the rest, with
 * the same rules as Android for this iPhone's person. Sync and backup is the iPhone's Get fresh data ([FreshScreen]):
 * its sync code, syncing by itself, the internet mailbox, and leaving the move. What waits for step 3 is left out
 * ([IphonePlatform]). [safeArea] keeps everything clear of the Dynamic Island, the home indicator and the keyboard; the
 * ground runs under them. [startRoute] is where the shared screens open (Home by default) and [onNav] gives their
 * navigation, for the tests.
 */
@Composable
fun HouseMoveApp(
    model: AppModel,
    safeArea: WindowInsets = WindowInsets.safeDrawing,
    startRoute: String? = null,
    onNav: (NavHostController) -> Unit = {},
) {
    HouseMoveTheme {
        AppBackground {
            Box(Modifier.fillMaxSize().windowInsetsPadding(safeArea)) {
                val store = model.store
                if (store == null) JoinFlow(model) else key(store) { TheMove(model, store, startRoute, onNav) }
            }
            // What the shared screens say for a few seconds, above the home indicator and above any dialog (a scanner's
            // "That is not a sync code" too), and never in the way of a tap (ToastHost).
            ToastHost(model.toasts, Modifier.windowInsetsPadding(safeArea))
        }
    }
}

/** The joined move in the shared app, with the iPhone under it. */
@Composable
private fun TheMove(model: AppModel, store: Store, startRoute: String?, onNav: (NavHostController) -> Unit) {
    val platform = remember(model) { IphonePlatform(model.hooks, model.toasts) }
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    // Said once on Home after joining: who this iPhone is now, and what its role does (Android's banner after joining).
    model.welcome?.let { w ->
        LaunchedEffect(w) {
            state.banner = HomeBanner(store.moveId, w.title, w.text, store.me?.id.orEmpty())
            model.welcomeRead()
        }
    }
    var nav by remember { mutableStateOf<NavHostController?>(null) }
    // A sync code or the mailbox's code from outside (the Camera's Open in HouseMove, a paste) shows Get fresh data.
    val asked = model.freshAsked
    LaunchedEffect(asked, nav) {
        val n = nav ?: return@LaunchedEffect
        if (asked > 0 && n.currentBackStackEntry?.destination?.route?.startsWith("sync") != true) n.open("sync")
    }
    CompositionLocalProvider(
        LocalPlatform provides platform,
        LocalAppState provides state,
        LocalAutoSync provides model.autoSync,
        LocalMailbox provides model.mailbox,
        LocalSyncScreen provides { code -> FreshScreen(model, store, code) },
    ) {
        MoveRoot(store, startRoute, onNav = {
            nav = it
            onNav(it)
        })
        // An update file or a backup opened with HouseMove from another app (step 3b).
        IncomingFiles(model, store)
    }
}
