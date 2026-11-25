package app.housemove.ui.iphone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import app.housemove.ui.AppBackground
import app.housemove.ui.HouseMoveTheme

/**
 * The first iPhone build's screens: joining a move that lives on an Android phone, then looking at it, getting fresh
 * data and leaving it. [safeArea] keeps everything clear of the Dynamic Island, the home indicator and the keyboard;
 * the ground runs under them.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HouseMoveApp(model: AppModel, safeArea: WindowInsets = WindowInsets.safeDrawing) {
    HouseMoveTheme {
        AppBackground {
            Box(Modifier.fillMaxSize().windowInsetsPadding(safeArea)) {
                val store = model.store
                if (store == null) {
                    JoinFlow(model)
                } else {
                    // The edge swipe goes back, as Back does. (Compose 1.12 points to NavigationEventHandler, whose
                    // library this build does not have yet; BackHandler still works on the iPhone.)
                    @Suppress("DEPRECATION")
                    BackHandler(enabled = model.pages.size > 1) { model.back() }
                    key(store) {
                        when (val page = model.pages.last()) {
                            Page.Rooms -> RoomsScreen(model, store)
                            is Page.Room -> RoomScreen(model, store, page.roomId)
                            Page.Search -> SearchScreen(model, store)
                            Page.Fresh -> FreshScreen(model, store)
                        }
                    }
                }
            }
        }
    }
}
