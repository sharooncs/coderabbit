package app.housemove.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.housemove.data.Home
import app.housemove.data.Store
import app.housemove.ui.screens.*

@Composable
fun AppRoot(store: Store, startRoute: String? = null, onNav: (NavHostController) -> Unit = {}) {
    HouseMoveTheme {
        val nav = rememberNavController()
        LaunchedEffect(nav) { onNav(nav) }
        val start = remember { startRoute ?: if (store.data.value.move.setupDone) "home" else "setup" }
        CompositionLocalProvider(LocalStore provides store, LocalNav provides nav) {
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
                    composable(
                        "person/{id}?invite={invite}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType }, navArgument("invite") { type = NavType.BoolType; defaultValue = false }),
                    ) { PersonScreen(it.arguments?.getString("id") ?: "", it.arguments?.getBoolean("invite") == true) }
                    composable("approvals") { ApprovalsScreen() }
                    composable("activity") { ActivityScreen() }
                    composable("settings") { SettingsScreen() }
                    composable("search") { SearchScreen() }
                    composable("scan") { ScanScreen() }

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
        }
    }
}
