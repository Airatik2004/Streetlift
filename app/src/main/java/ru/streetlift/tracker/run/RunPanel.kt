package ru.streetlift.tracker.run

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** «Бег» во вкладке «Таймер»: карта с маршрутом, время, обратный отсчёт, старт / пауза / стоп. */
@SuppressLint("MissingPermission")
@Composable
fun RunPanel(prefs: ru.streetlift.tracker.timer.TimerPrefs.Prefs, update: (ru.streetlift.tracker.timer.TimerPrefs.Prefs) -> Unit) {
    val ctx = LocalContext.current
    val st by RunTracker.state.collectAsStateWithLifecycle()
    // Пробежка по плану подготовки к забегу
    val repo = (ctx.applicationContext as ru.streetlift.tracker.StreetLiftApp).repository
    val settings by repo.settings.collectAsStateWithLifecycle(initialValue = null)
    val runData by repo.runData.collectAsStateWithLifecycle(initialValue = emptyList())
    val wk by RunWorkout.state.collectAsStateWithLifecycle()
    val today = java.time.LocalDate.now().toEpochDay()
    val planSettings = settings?.takeIf { it.endurance }
    val todayItem = planSettings?.runItems()?.firstOrNull { it.epochDay == today && !ru.streetlift.tracker.domain.RunPlan.isDone(it, runData) }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(st.phase) {
        while (st.running || st.autoPaused) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
        now = android.os.SystemClock.elapsedRealtime()
    }
    var denied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    // Состояние разрешений и геолокации перепроверяется при возврате в приложение
    var fine by remember { mutableStateOf(false) }
    var coarse by remember { mutableStateOf(false) }
    var locOn by remember { mutableStateOf(true) }
    fun refresh() {
        fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locOn = androidx.core.location.LocationManagerCompat.isLocationEnabled(ctx.getSystemService(LocationManager::class.java))
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        refresh()
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            denied = false
            saved = false
            RunService.start(ctx)
        } else denied = true
    }
    // Где мы сейчас — пока нет точки, карта показывает последнюю известную, иначе Москву
    val last = remember(st.phase, fine) {
        (if (!fine && !coarse) null else runCatching {
            val lm = ctx.getSystemService(LocationManager::class.java)
            lm.allProviders.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { RunTracker.Pt(it.latitude, it.longitude) }
        }.getOrNull()) ?: RunTracker.Pt(55.7558, 37.6173)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp))) {
            RouteMap(
                st.segments, Modifier.fillMaxWidth().height(320.dp),
                interactive = false, follow = st.active, fallback = last,
                current = st.current, accuracy = st.accuracy,
            )
            if (st.active && !st.hasFix) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                ) {
                    Text(
                        st.accuracy?.let { "Сигнал слабый · точность ${it.toInt()} м" } ?: "Поиск GPS…",
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (st.phase == RunTracker.Phase.COUNTDOWN) {
            Text("${st.countdown}", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        } else {
            val sec = (st.elapsedMs(now) / 1000).toInt()
            Text(
                if (sec >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
                else ru.streetlift.tracker.domain.Fmt.clock(sec),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = if (st.phase == RunTracker.Phase.PAUSED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (st.autoPaused) {
                Text("Автопауза — побегите, и время пойдёт", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            // Дистанция, темп сейчас, средний темп
            Row(Modifier.fillMaxWidth()) {
                Metric("Дистанция", ru.streetlift.tracker.domain.RunStats.distance(st.distanceM), Modifier.weight(1f))
                Metric("Темп сейчас", ru.streetlift.tracker.domain.RunStats.pace(st.currentPace() ?: 0.0), Modifier.weight(1f))
                Metric("Средний", ru.streetlift.tracker.domain.RunStats.pace(st.avgPace(now) ?: 0.0), Modifier.weight(1f))
            }
            if (st.active) {
                Text(
                    "Км ${(st.lastKm?.km ?: 0) + 1} · ${ru.streetlift.tracker.domain.RunStats.pace(st.kmPace(now) ?: 0.0)}/км" +
                        (st.lastKm?.let { " · прошлый ${ru.streetlift.tracker.domain.RunStats.pace(it.kmMs / 1000.0)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Сегодня по плану — запуск с отрезками и целевыми темпами
        if (st.phase == RunTracker.Phase.IDLE && todayItem != null && planSettings != null) {
            val dist = planSettings.runDist
            val v = ru.streetlift.tracker.domain.RunPlan.fitness(runData, today)
            val paces = if (dist != null && v != null) todayItem.segs.map {
                ru.streetlift.tracker.domain.RunPlan.zonePace(it.zone, v, dist, planSettings.runGoalSec?.toDouble())
            } else emptyList()
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Сегодня по плану: ${todayItem.title()}", style = MaterialTheme.typography.titleSmall)
                    todayItem.segs.forEachIndexed { i, seg ->
                        Text(
                            "${seg.kind.title} ${ru.streetlift.tracker.domain.RunStats.distance(seg.m.toDouble())}" +
                                (paces.getOrNull(i)?.let { " · ${ru.streetlift.tracker.domain.RunStats.pace(it)}/км" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(
                        onClick = {
                            refresh()
                            RunWorkout.load(todayItem, paces)
                            if (fine) {
                                saved = false
                                RunService.start(ctx)
                            } else {
                                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Старт по плану") }
                }
            }
        }
        // Идёт пробежка по плану: текущий отрезок
        val seg = wk.seg
        if (st.active && wk.active != null) {
            androidx.compose.material3.Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (seg == null) {
                        Text("План выполнен — можно заканчивать", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        val num = wk.workNumber()?.takeIf { seg.kind == ru.streetlift.tracker.domain.RunPlan.SegKind.WORK }
                        Text(seg.kind.title + (num?.let { " ${it.first} из ${it.second}" } ?: ""), style = MaterialTheme.typography.titleSmall)
                        val leftM = (seg.m - (st.distanceM - wk.segStartM)).coerceAtLeast(0.0)
                        Text(
                            "Осталось ${ru.streetlift.tracker.domain.RunStats.distance(leftM)}" +
                                (wk.pace?.let { " · цель ${ru.streetlift.tracker.domain.RunStats.pace(it)}/км" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        when (st.phase) {
            RunTracker.Phase.IDLE -> Button(
                onClick = {
                    refresh()
                    RunWorkout.clear()
                    if (fine) {
                        saved = false
                        RunService.start(ctx)
                    } else {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Старт") }
            RunTracker.Phase.COUNTDOWN -> OutlinedButton(onClick = { RunService.cancel(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text("Отмена")
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { RunService.toggle(ctx) }, modifier = Modifier.weight(1f)) {
                    Text(if (st.running) "Пауза" else "Продолжить")
                }
                OutlinedButton(onClick = { RunService.stopRun(ctx); saved = true }, modifier = Modifier.weight(1f)) { Text("Стоп") }
            }
        }
        // Понятная причина, если GPS не будет
        when {
            !locOn -> Problem("Геолокация выключена — включите её в шторке или в настройках.", "Открыть настройки") {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }
            coarse && !fine -> Problem("Разрешено только приблизительное местоположение — для маршрута нужно точное.", "Настройки приложения") {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}")),
                    )
                }
            }
            denied -> Problem("Нужно разрешение на геолокацию, чтобы записывать маршрут.", "Настройки приложения") {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:${ctx.packageName}")),
                    )
                }
            }
        }
        if (saved && !st.active) {
            Text(
                "Пробежка сохранена — Статистика → Бег",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Автопауза и отсечка километров
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Автопауза, когда стоите", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(checked = prefs.runAutoPause, onCheckedChange = {
                update(prefs.copy(runAutoPause = it))
            })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Каждый км", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            listOf(0 to "Выкл", 1 to "Сигнал", 2 to "Голос").forEach { (v, t) ->
                androidx.compose.material3.FilterChip(
                    selected = prefs.runKmCue == v,
                    onClick = {
                        update(prefs.copy(runKmCue = v))
                    },
                    label = { Text(t) },
                )
            }
        }
        // Обратный отсчёт перед стартом
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Отсчёт", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            listOf(0 to "Выкл", 3 to "3с", 5 to "5с", 10 to "10с").forEach { (v, t) ->
                androidx.compose.material3.FilterChip(
                    selected = prefs.runCountdown == v,
                    onClick = {
                        update(prefs.copy(runCountdown = v))
                    },
                    label = { Text(t) },
                )
            }
        }
    }
}

@Composable
private fun Problem(text: String, action: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        androidx.compose.material3.TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
