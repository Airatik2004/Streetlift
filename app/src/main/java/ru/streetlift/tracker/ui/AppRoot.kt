package ru.streetlift.tracker.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
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
    Tab("stats", "Статистика", Icons.Filled.BarChart),
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
    // Нижние вкладки листаются свайпом
    val pager = androidx.compose.foundation.pager.rememberPagerState { tabs.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val startRequest by ru.streetlift.tracker.NavRequests.startWorkout.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(startRequest) {
        if (!startRequest) return@LaunchedEffect
        ru.streetlift.tracker.NavRequests.startWorkout.value = false
        val id = repo.startPlanned() ?: return@LaunchedEffect
        if (nav.currentBackStackEntry?.destination?.route?.startsWith("workout") != true) {
            nav.navigate("workout/$id") { launchSingleTop = true }
        }
    }
    val tabRequest by ru.streetlift.tracker.NavRequests.openTab.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(tabRequest) {
        val tab = tabRequest ?: return@LaunchedEffect
        ru.streetlift.tracker.NavRequests.openTab.value = null
        if (nav.currentBackStackEntry?.destination?.route != "main") nav.popBackStack("main", inclusive = false)
        pager.scrollToPage(tab.coerceIn(0, tabs.size - 1))
    }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "main", modifier = Modifier.padding(padding)) {
            composable("main") {
              val active by repo.activeSession.collectAsStateWithLifecycle(initialValue = null)
              androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                // Незавершённая тренировка — закреплена сверху на всех вкладках, не уезжает при прокрутке
                val a = active
                if (a != null) ActiveWorkoutBar(a.dayName) { nav.navigate("workout/${a.id}") { launchSingleTop = true } }
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pager, beyondViewportPageCount = 1, modifier = Modifier.weight(1f),
                ) { page ->
                    when (page) {
                        0 -> HomeScreen(repo, onOpenWorkout = { id -> nav.navigate("workout/$id") })
                        1 -> EditorScreen(repo, away = pager.settledPage != 1)
                        2 -> TimerScreen()
                        else -> ru.streetlift.tracker.ui.stats.StatsScreen(repo)
                    }
                }
              }
            }
            composable(
                "workout/{sessionId}",
                arguments = listOf(navArgument("sessionId") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("sessionId") ?: 0L
                WorkoutScreen(repo, id, onExit = { nav.popBackStack("main", inclusive = false) })
            }
        }
    }
}

/** Плашка незавершённой тренировки: нажатие — открыть тренировку. */
@Composable
private fun ActiveWorkoutBar(dayName: String, onOpen: () -> Unit) {
    androidx.compose.material3.Surface(
        color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text("Незавершённая тренировка", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                Text(dayName, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
            }
            androidx.compose.material3.Button(onClick = onOpen) { Text("Продолжить") }
        }
    }
}
