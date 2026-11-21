package app.housemove.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.housemove.data.Home
import app.housemove.data.Store
import app.housemove.ui.screens.*

@Composable
fun AppRoot(store: Store, startRoute: String? = null) {
    HouseMoveTheme {
        val nav = rememberNavController()
        val start = remember { startRoute ?: if (store.data.value.move.setupDone) "home" else "setup" }
        CompositionLocalProvider(LocalStore provides store, LocalNav provides nav) {
            NavHost(nav, startDestination = start) {
                composable("setup") { SetupScreen() }
                composable("home") { HomeScreen() }
                composable("more") { MoreScreen() }
                composable("rooms") { RoomsScreen() }
                composable("sync") { SyncScreen() }
                composable("settings") { SettingsScreen() }
                composable("search") { SearchScreen() }
                composable("scan") { ScanScreen() }

                composable("items") { ItemsScreen() }
                composable("item/{id}") { ItemScreen(it.arguments?.getString("id") ?: "") }
                composable("walk") { WalkScreen() }
                composable("triage") { TriageScreen() }
                composable("plan") { PlanScreen() }
                composable("disposal") { DisposalScreen() }

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
