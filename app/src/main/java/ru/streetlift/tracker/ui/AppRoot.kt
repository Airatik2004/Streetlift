package ru.streetlift.tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.streetlift.tracker.data.db.SettingsEntity
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.ui.setup.SetupScreen
import ru.streetlift.tracker.ui.editor.EditorScreen
import ru.streetlift.tracker.ui.history.HistoryScreen
import ru.streetlift.tracker.ui.home.HomeScreen
import ru.streetlift.tracker.ui.timer.TimerScreen
import ru.streetlift.tracker.ui.workout.WorkoutScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Главная", Icons.Filled.Home),
    Tab("editor", "Цикл", Icons.Filled.Tune),
    Tab("timer", "Таймер", Icons.Filled.Timer),
    Tab("history", "История", Icons.Filled.History),
)

@Composable
fun AppRoot(repo: WorkoutRepository) {
    val settings by repo.settings.collectAsStateWithLifecycle<SettingsEntity?>(initialValue = null)
    val current = settings ?: return
    if (!current.setupDone) {
        Scaffold { padding ->
            Box(Modifier.padding(padding)) { SetupScreen(repo) }
        }
        return
    }
    val nav = rememberNavController()
    val openRequest by ru.streetlift.tracker.NavRequests.openWorkout.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(openRequest) {
        val id = openRequest ?: return@LaunchedEffect
        ru.streetlift.tracker.NavRequests.openWorkout.value = null
        if (nav.currentBackStackEntry?.destination?.route?.startsWith("workout") != true) {
            nav.navigate("workout/$id") { launchSingleTop = true }
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route == null || !route.startsWith("workout")

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(repo, onOpenWorkout = { id -> nav.navigate("workout/$id") })
            }
            composable("editor") { EditorScreen(repo) }
            composable("timer") { TimerScreen() }
            composable("history") { HistoryScreen(repo) }
            composable(
                "workout/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("sessionId") ?: 0L
                WorkoutScreen(repo, id, onExit = { nav.popBackStack("home", inclusive = false) })
            }
        }
    }
}
