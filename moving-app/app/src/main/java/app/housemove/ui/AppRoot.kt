package app.housemove.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.NavHostController
import app.housemove.data.Moves
import app.housemove.data.Store
import app.housemove.sync.AutoSync
import app.housemove.sync.MailboxSync
import app.housemove.ui.screens.LocalCardPrinter
import app.housemove.ui.screens.LocalTagPrinter

/**
 * The app on an Android phone: the shared screens ([MovesRoot], sharedUi Nav.kt) with Android under them
 * ([AndroidPlatform]) and Android's system bars. MainActivity shows this; the tests show it too.
 */
@Composable
fun AppRoot(moves: Moves, onNav: (NavHostController) -> Unit = {}, autoSync: AutoSync? = null, mailbox: MailboxSync? = null) {
    SystemBars()
    OnAndroid { MovesRoot(moves, onNav, autoSync, mailbox) }
}

/** One move's screens on Android ([MoveRoot]): in tests, one move shown on its own. */
@Composable
fun AppRoot(store: Store, startRoute: String? = null, onNav: (NavHostController) -> Unit = {}) {
    SystemBars()
    OnAndroid { MoveRoot(store, startRoute, onNav) }
}

@Composable
private fun OnAndroid(content: @Composable () -> Unit) {
    val platform = rememberAndroidPlatform(LocalTagPrinter.current, LocalCardPrinter.current)
    CompositionLocalProvider(LocalPlatform provides platform, content = content)
}
