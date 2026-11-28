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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import app.housemove.data.Home
import app.housemove.data.Moves
import app.housemove.data.Store
import app.housemove.sync.AutoSync
import app.housemove.sync.MailboxSync
import app.housemove.ui.screens.*

/**
 * The app on a phone: the move used last, or, on a phone with no move yet, the first-open
 * screens ("Start a new move", "Join a move"). Opening another move replaces every screen
 * with that move's, starting at its Home. The phone's own root gives [LocalPlatform] (Android's
 * AppRoot, in the app).
 */
@Composable
fun MovesRoot(moves: Moves, onNav: (NavHostController) -> Unit = {}, autoSync: AutoSync? = null, mailbox: MailboxSync? = null) {
    HouseMoveTheme {
        val scope = rememberCoroutineScope()
        val state = remember { AppState(scope) }
        val current by moves.current.collectAsState()
        CompositionLocalProvider(LocalMoves provides moves, LocalAppState provides state, LocalAutoSync provides autoSync, LocalMailbox provides mailbox) {
            val store = current
            if (store == null) FirstOpen(onNav)
            else key(store) { MoveRoot(store, onNav = onNav) }
            // An update file opened from another app, and what the phone says after letting go of a move by itself.
            IncomingUpdates(moves)
            Farewells(moves)
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

/**
 * A phone's own screen for syncing and backup in place of [SyncScreen], where it has one: the iPhone's "Get fresh data"
 * (iphone/FreshScreen.kt), with its sync code, syncing by itself, the internet mailbox and leaving the move. It is given
 * the code that came with the route, if any.
 */
val LocalSyncScreen = staticCompositionLocalOf<(@Composable (code: String?) -> Unit)?> { null }

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
 * one move; inside [MovesRoot] for the phone's moves, the screens for moves use those. The phone's
 * own root gives [LocalPlatform] (Android's AppRoot; the iPhone's HouseMoveApp, which also gives
 * [LocalSyncScreen]).
 */
@Composable
fun MoveRoot(store: Store, startRoute: String? = null, onNav: (NavHostController) -> Unit = {}) {
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
                        val code = it.arguments?.read { getStringOrNull("code") }
                        val own = LocalSyncScreen.current
                        if (own != null) own(code) else SyncScreen(code)
                    }
                    composable("people") { PeopleScreen() }
                    composable("people/add") { AddPersonScreen() }
                    composable("helping") { HelpingScreen() }
                    composable(
                        "person/{id}?invite={invite}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("invite") { type = NavType.BoolType; defaultValue = false }),
                    ) { PersonScreen(it.arguments?.read { getStringOrNull("id") } ?: "", it.arguments?.read { getBooleanOrNull("invite") } == true) }
                    composable("approvals") { ApprovalsScreen() }
                    composable("activity") { ActivityScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("search") { SearchScreen() }
                    composable("scan") { ScanScreen() }
                    composable("moves") { MovesScreen() }
                    composable("askdecisions") { AskDecisionsScreen() }
                    firstOpenRoutes(inMove = true)

                    composable("items") { ItemsScreen() }
                    composable("item/{id}") { ItemScreen(it.arguments?.read { getStringOrNull("id") } ?: "") }
                    composable("walk") { WalkScreen() }
                    composable("triage") { TriageScreen() }
                    composable("plan") { PlanScreen() }
                    composable("disposal") { DisposalScreen() }
                    composable("electronics") { ElectronicsScreen() }

                    composable("boxes") { BoxesScreen() }
                    composable("box/{id}") { BoxScreen(it.arguments?.read { getStringOrNull("id") } ?: "") }
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
                        RecordsScreen(if (it.arguments?.read { getStringOrNull("home") } == "DEST") Home.DEST else Home.ORIGIN)
                    }
                    composable("unpack") { UnpackScreen() }
                    composable("budget") { BudgetScreen() }
                    composable("reports") { ReportsScreen() }
                }
            }
            // Opened from another move (a label scanned there, "Sync" on its card): where to go on from here.
            LaunchedEffect(nav) { state.takeRoute()?.let { r -> runCatching { nav.navigate(r) } } }
            SaveProblems(store)
            NotOnThisPhone(nav)
        }
    }
}

/**
 * A screen this phone cannot offer yet ([unavailableRoutes]: the iPhone's camera, scanner and PDF screens until step 3)
 * is left out of the lists of screens; should a link still lead to one, the phone goes back and says so. Android offers
 * every screen, so there this does nothing.
 */
@Composable
private fun NotOnThisPhone(nav: NavHostController) {
    val hidden = unavailableRoutes()
    if (hidden.isEmpty()) return
    val context = LocalPlatform.current
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')?.substringBefore('/')
    LaunchedEffect(route) {
        if (route != null && route in hidden) {
            toast(context, "That is not on this phone yet.")
            nav.popBackStack()
        }
    }
}

/**
 * A save that failed is said at once, on whatever screen is open, so no change is lost without the person knowing.
 * Home says it too until a save goes through ([Store.saveProblem]).
 */
@Composable
private fun SaveProblems(store: Store) {
    val context = LocalPlatform.current
    val problem by store.saveProblem.collectAsState()
    LaunchedEffect(problem) { problem?.let { toast(context, it) } }
}
