package app.housemove.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.housemove.data.Home
import app.housemove.data.Moves
import app.housemove.data.Store
import app.housemove.ui.screens.*

/**
 * The app on a phone: the move used last, or, on a phone with no move yet, the first-open
 * screens ("Start a new move", "Join a move"). Opening another move replaces every screen
 * with that move's, starting at its Home.
 */
@Composable
fun AppRoot(moves: Moves, onNav: (NavHostController) -> Unit = {}) {
    HouseMoveTheme {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        val current by moves.current.collectAsState()
        CompositionLocalProvider(LocalMoves provides moves, LocalAppState provides state) {
            val store = current
            if (store == null) FirstOpen(onNav)
            else key(store) { AppRoot(store, onNav = onNav) }
        }
    }
}

/** The screens of a phone with no move: the two ways in, and a lost phone's way back. */
@Composable
private fun FirstOpen(onNav: (NavHostController) -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(nav) { onNav(nav) }
    val state = appState()
    CompositionLocalProvider(LocalNav provides nav) {
        AppBackground {
            NavHost(
                nav, startDestination = "welcome",
                enterTransition = { fadeIn(tween(220, delayMillis = 90)) },
                exitTransition = { fadeOut(tween(90)) },
            ) {
                firstOpenRoutes(inMove = false)
            }
        }
    }
    // "Join theirs instead" on a move given up before it was set up leads straight on to the scanner.
    LaunchedEffect(nav) { state.takeRoute()?.takeIf { it in FIRST_OPEN_ROUTES }?.let { nav.navigate(it) } }
}

private val FIRST_OPEN_ROUTES = setOf("welcome", "join", "lost")

/** The first-open screens. They are in every move's screens too, so a phone with moves can join or restore another. */
private fun NavGraphBuilder.firstOpenRoutes(inMove: Boolean) {
    composable("welcome") { WelcomeScreen() }
    composable("join") { JoinScreen(inMove) }
    composable("lost") { LostPhoneScreen(inMove) }
}

/**
 * One move's screens. [startRoute] is where they open; by default Home, or the first-run
 * wizard for a move that is not set up yet. Shown on its own (in tests), it works on that
 * one move; inside [AppRoot] for the phone's moves, the screens for moves use those.
 */
@Composable
fun AppRoot(store: Store, startRoute: String? = null, onNav: (NavHostController) -> Unit = {}) {
    HouseMoveTheme {
        val nav = rememberNavController()
        LaunchedEffect(nav) { onNav(nav) }
        val scope = rememberCoroutineScope()
        val outer = LocalAppState.current
        val state = outer ?: remember { AppState(scope) }
        val start = remember { startRoute ?: if (store.data.value.move.setupDone) "home" else "setup" }
        CompositionLocalProvider(LocalStore provides store, LocalNav provides nav, LocalAppState provides state) {
            // The night ground is drawn once here; every screen is transparent on top of it.
            AppBackground {
                NavHost(
                    nav, startDestination = start,
                    // Screens are transparent, so a slow cross-fade would show one through the other.
                    enterTransition = { fadeIn(tween(220, delayMillis = 90)) },
                    exitTransition = { fadeOut(tween(90)) },
                ) {
                    composable("setup") { SetupScreen() }
                    composable("home") { HomeScreen() }
                    composable("overview") { OverviewScreen() }
                    composable("more") { MoreScreen() }
                    composable("rooms") { RoomsScreen() }
                    // A sync code or invite scanned elsewhere in the app arrives as "sync?code=…" and is used at once.
                    composable("sync?code={code}", arguments = listOf(navArgument("code") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                        SyncScreen(it.arguments?.getString("code"))
                    }
                    composable("people") { PeopleScreen() }
                    composable("people/add") { AddPersonScreen() }
                    composable("helping") { HelpingScreen() }
                    composable(
                        "person/{id}?invite={invite}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("invite") { type = NavType.BoolType; defaultValue = false }),
                    ) { PersonScreen(it.arguments?.getString("id") ?: "", it.arguments?.getBoolean("invite") == true) }
                    composable("approvals") { ApprovalsScreen() }
                    composable("activity") { ActivityScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("search") { SearchScreen() }
                    composable("scan") { ScanScreen() }
                    composable("moves") { MovesScreen() }
                    firstOpenRoutes(inMove = true)

                    composable("items") { ItemsScreen() }
                    composable("item/{id}") { ItemScreen(it.arguments?.getString("id") ?: "") }
                    composable("walk") { WalkScreen() }
                    composable("triage") { TriageScreen() }
                    composable("plan") { PlanScreen() }
                    composable("disposal") { DisposalScreen() }
                    composable("electronics") { ElectronicsScreen() }

                    composable("boxes") { BoxesScreen() }
                    composable("box/{id}") { BoxScreen(it.arguments?.getString("id") ?: "") }
                    composable("pack") { PackScreen() }
                    composable("labels") { LabelsScreen() }
                    composable("lists") { ListsScreen() }
                    composable("packorder") { PackOrderScreen() }
                    composable("tasks") { TasksScreen() }

                    composable("moveday") { MoveDayScreen() }
                    composable("load") { LoadScreen() }
                    composable("unload") { UnloadScreen() }
                    composable("reconcile") { ReconcileScreen() }
                    composable("sweep") { SweepScreen() }
                    composable("records/{home}") {
                        RecordsScreen(if (it.arguments?.getString("home") == "DEST") Home.DEST else Home.ORIGIN)
                    }
                    composable("unpack") { UnpackScreen() }
                    composable("budget") { BudgetScreen() }
                    composable("reports") { ReportsScreen() }
                }
            }
            // Opened from another move (a label scanned there, "Sync" on its card): where to go on from here.
            LaunchedEffect(nav) { state.takeRoute()?.let { r -> runCatching { nav.navigate(r) } } }
        }
    }
}
