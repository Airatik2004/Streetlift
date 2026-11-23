package ru.streetlift.tracker.run

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.streetlift.tracker.MainActivity
import ru.streetlift.tracker.NavRequests
import ru.streetlift.tracker.R
import ru.streetlift.tracker.StreetLiftApp
import ru.streetlift.tracker.data.db.RunEntity
import ru.streetlift.tracker.domain.Fmt

/** Фоновая запись пробежки: GPS и время, уведомление с «Пауза» / «Стоп». */
class RunService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    private var listening = false

    private val listener = LocationListener { loc: Location ->
        // Грубые точки (хуже 30 м) не пишем — маршрут не скачет
        if (!loc.hasAccuracy() || loc.accuracy <= 30f) RunTracker.addPoint(loc.latitude, loc.longitude)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> RunTracker.toggle()
            ACTION_STOP -> {
                stopRun(this)
                return START_NOT_STICKY
            }
        }
        if (!RunTracker.state.value.active) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, build(), type)
        } catch (e: Exception) {
            // Нет разрешения на геолокацию — запись невозможна
            RunTracker.finish()
            stopSelf()
            return START_NOT_STICKY
        }
        startLocation()
        if (ticker == null) {
            ticker = scope.launch {
                while (isActive && RunTracker.state.value.active) {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
                    delay(1000)
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (listening) return
        val lm = getSystemService(LocationManager::class.java)
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, listener, Looper.getMainLooper())
            listening = true
        }
    }

    private fun build(): Notification {
        val s = RunTracker.state.value
        val time = Fmt.clock((s.elapsedMs() / 1000).toInt())
        val open = PendingIntent.getActivity(
            this, 30,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(NavRequests.EXTRA_OPEN_TAB, NavRequests.TAB_TIMER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(if (s.running) "Бег · $time" else "Бег · $time · пауза")
            .setContentText(if (s.hasFix) "Маршрут записывается" else "Поиск GPS…")
            .setColor(0xFF2196F3.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, if (s.running) "Пауза" else "Продолжить", action(ACTION_TOGGLE, 31))
            .addAction(0, "Стоп", action(ACTION_STOP, 32))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun action(a: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, RunService::class.java).setAction(a),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    override fun onDestroy() {
        if (listening) runCatching { getSystemService(LocationManager::class.java).removeUpdates(listener) }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "run"
        private const val NOTIF_ID = 3001
        const val ACTION_TOGGLE = "ru.streetlift.tracker.RUN_TOGGLE"
        const val ACTION_STOP = "ru.streetlift.tracker.RUN_STOP"

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Бег", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Запись пробежки"
                    setShowBadge(false)
                },
            )
        }

        fun start(context: Context) {
            RunTracker.start()
            ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
        }

        fun toggle(context: Context) {
            RunTracker.toggle()
            context.startService(Intent(context, RunService::class.java))
        }

        /** Завершить и сохранить пробежку (если было хоть сколько-то времени). */
        fun stopRun(context: Context) {
            val done = RunTracker.finish()
            val app = context.applicationContext as StreetLiftApp
            if (done.accumulatedMs >= 5_000) {
                CoroutineScope(Dispatchers.IO).launch {
                    app.repository.saveRun(
                        RunEntity(startedAt = done.startedAt, durationMs = done.accumulatedMs, route = RunTracker.encode(done.segments)),
                    )
                }
            }
            context.stopService(Intent(context, RunService::class.java))
        }
    }
}
