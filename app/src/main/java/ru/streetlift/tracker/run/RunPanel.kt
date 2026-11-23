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

/** «Бег» во вкладке «Таймер»: карта с маршрутом, время, старт / пауза / стоп. */
@SuppressLint("MissingPermission")
@Composable
fun RunPanel() {
    val ctx = LocalContext.current
    val st by RunTracker.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(st.phase) {
        while (st.running) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(250)
        }
        now = android.os.SystemClock.elapsedRealtime()
    }
    var denied by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    fun hasPermission() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            denied = false
            saved = false
            RunService.start(ctx)
        } else denied = true
    }
    // Где мы сейчас — пока маршрута нет, карта показывает последнюю известную точку
    val last = remember(st.phase) {
        (if (!hasPermission()) null else runCatching {
            val lm = ctx.getSystemService(LocationManager::class.java)
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))
                ?.let { RunTracker.Pt(it.latitude, it.longitude) }
        }.getOrNull()) ?: RunTracker.Pt(55.7558, 37.6173) // пока неизвестно — Москва
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp))) {
            RouteMap(st.segments, Modifier.fillMaxWidth().height(320.dp), interactive = false, follow = st.active, fallback = last)
            if (st.active && !st.hasFix) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                ) { Text("Поиск GPS…", Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
            }
        }
        val sec = (st.elapsedMs(now) / 1000).toInt()
        Text(
            if (sec >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
            else ru.streetlift.tracker.domain.Fmt.clock(sec),
            fontSize = 56.sp,
            fontWeight = FontWeight.Bold,
            color = if (st.phase == RunTracker.Phase.PAUSED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        when (st.phase) {
            RunTracker.Phase.IDLE -> Button(
                onClick = {
                    if (hasPermission()) {
                        saved = false
                        RunService.start(ctx)
                    } else {
                        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Старт") }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { RunService.toggle(ctx) }, modifier = Modifier.weight(1f)) {
                    Text(if (st.running) "Пауза" else "Продолжить")
                }
                OutlinedButton(onClick = { RunService.stopRun(ctx); saved = true }, modifier = Modifier.weight(1f)) { Text("Стоп") }
            }
        }
        if (denied) {
            Text(
                "Нужно разрешение на геолокацию, чтобы записывать маршрут.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (saved && !st.active) {
            Text(
                "Пробежка сохранена — Статистика → Бег",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
