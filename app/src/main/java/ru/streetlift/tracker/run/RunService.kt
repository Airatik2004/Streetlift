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
        // Позиция видна всегда; в маршрут идут только точки лучше 30 м (решает RunTracker).
        // Высота — по барометру, если он есть, иначе по GPS.
        val alt = baroAlt ?: if (loc.hasAltitude()) loc.altitude else null
        RunTracker.onLocation(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, alt)
            ?.let { if (RunWorkout.state.value.active == null) announceKm(it) }
        // Пробежка по плану: смена отрезка — объявление
        if (RunTracker.state.value.running) {
            RunWorkout.onDistance(RunTracker.state.value.distanceM)?.let { announce(it) }
        }
    }

    // ---- Барометр: высота точнее GPS ----
    @Volatile private var baroAlt: Double? = null
    private var baroOn = false
    private val baroListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val a = android.hardware.SensorManager.getAltitude(android.hardware.SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]).toDouble()
            baroAlt = baroAlt?.let { it + (a - it) * 0.2 } ?: a
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun startBaro() {
        if (baroOn) return
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE) ?: return
        baroOn = sm.registerListener(baroListener, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
    }

    // ---- Голосовые отсечки ----
    private var tts: android.speech.tts.TextToSpeech? = null
    @Volatile private var ttsReady = false

    private fun initTts() {
        if (tts != null) return
        tts = android.speech.tts.TextToSpeech(applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val r = t.setLanguage(java.util.Locale("ru", "RU"))
                ttsReady = r != android.speech.tts.TextToSpeech.LANG_MISSING_DATA && r != android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                t.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { releaseFocus() }
                    @Deprecated("old api")
                    override fun onError(id: String?) { releaseFocus() }
                })
            }
        }
    }

    private var focus: android.media.AudioFocusRequest? = null

    private fun releaseFocus() {
        focus?.let { getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Отсечка километра: голосом (музыка приглушается) или сигналом. */
    private fun announceKm(cue: RunTracker.KmCue) = say(kmPhrase(cue), "km${cue.km}", ru.streetlift.tracker.timer.TimerPrefs.load(this).runKmCue)

    /** Смена отрезка пробежки по плану: голосом, если доступен, иначе двойной сигнал с вибрацией. */
    private fun announce(text: String) = say(text, "seg${System.currentTimeMillis()}", if (ttsReady) 2 else 1)

    private fun say(text: String, id: String, mode: Int) {
        if (mode == 0) return
        val t = tts
        if (mode == 2 && t != null && ttsReady) {
            val am = getSystemService(android.media.AudioManager::class.java)
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).build()
            am.requestAudioFocus(req)
            focus = req
            t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, id)
        } else {
            ru.streetlift.tracker.timer.Beeper.pip()
            scope.launch {
                delay(300)
                ru.streetlift.tracker.timer.Beeper.pip()
            }
            ru.streetlift.tracker.timer.SignalPlayer.vibrate(this, 400)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initTts()
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
        startBaro()
        if (ticker == null) {
            ticker = scope.launch {
                val st0 = RunTracker.state.value
                if (st0.phase == RunTracker.Phase.COUNTDOWN && st0.countdown <= 3) {
                    // Разбудить звук до первого пика отсчёта
                    ru.streetlift.tracker.timer.SignalPlayer.warmUp(this@RunService)
                    delay(400)
                }
                while (isActive && RunTracker.state.value.active) {
                    val s = RunTracker.state.value
                    val counting = s.phase == RunTracker.Phase.COUNTDOWN
                    // Пик только на 3, 2, 1; раньше — молча, цифры на экране и в шторке
                    if (counting && s.countdown <= 3) ru.streetlift.tracker.timer.Beeper.pip()
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build())
                    if (counting && s.countdown == 4) {
                        // За полсекунды до первого пика — разбудить звук (после тишины он «засыпает»)
                        delay(500)
                        ru.streetlift.tracker.timer.SignalPlayer.warmUp(this@RunService)
                        delay(500)
                    } else {
                        delay(1000)
                    }
                    // Отсчёт закончился — свисток, бег пошёл
                    if (RunTracker.tickCountdown()) ru.streetlift.tracker.timer.Beeper.whistle()
                    if (ru.streetlift.tracker.timer.TimerPrefs.load(this@RunService).runAutoPause) RunTracker.checkAutoPause()
                }
            }
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (listening) return
        val lm = getSystemService(LocationManager::class.java)
        // Android 12+: системное местоположение (GPS + Wi-Fi + сети) — находит быстрее, в т.ч. в помещении.
        // Иначе — GPS и сети вместе.
        val providers = if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.allProviders.contains(it) }
        }
        providers.forEach { p ->
            runCatching {
                lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper())
                listening = true
            }
            // Последняя известная точка — сразу показать на карте
            runCatching { lm.getLastKnownLocation(p) }.getOrNull()?.let { RunTracker.showLast(it.latitude, it.longitude) }
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
            .setContentTitle(
                when {
                    s.phase == RunTracker.Phase.COUNTDOWN -> "Бег · старт через ${s.countdown}"
                    s.running -> "Бег · $time"
                    s.autoPaused -> "Бег · $time · автопауза"
                    else -> "Бег · $time · пауза"
                },
            )
            .setContentText(
                if (!s.hasFix) "Поиск GPS…"
                else "${ru.streetlift.tracker.domain.RunStats.distance(s.distanceM)} · темп ${ru.streetlift.tracker.domain.RunStats.pace(s.avgPace() ?: 0.0)}/км",
            )
            .setColor(0xFF2196F3.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { if (s.phase != RunTracker.Phase.COUNTDOWN) addAction(0, if (s.running) "Пауза" else "Продолжить", action(ACTION_TOGGLE, 31)) }
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
        if (baroOn) runCatching { getSystemService(android.hardware.SensorManager::class.java).unregisterListener(baroListener) }
        releaseFocus()
        runCatching { tts?.shutdown() }
        tts = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** «13-й километр. Темп последнего километра — 4 минуты 30 секунд. Средний темп — 5 минут 12 секунд». */
        fun kmPhrase(cue: RunTracker.KmCue): String {
            val avg = cue.totalMs / 1000.0 / cue.km
            return "${cue.km}-й километр. Темп последнего километра — ${spoken(cue.kmMs / 1000.0)}. Средний темп — ${spoken(avg)}."
        }

        private fun spoken(secPerKm: Double): String {
            val s = kotlin.math.round(secPerKm).toInt()
            val m = s / 60
            val sec = s % 60
            fun pl(n: Int, one: String, few: String, many: String) = when {
                n % 100 in 11..14 -> many
                n % 10 == 1 -> one
                n % 10 in 2..4 -> few
                else -> many
            }
            val mins = "$m ${pl(m, "минута", "минуты", "минут")}"
            return if (sec == 0) mins else "$mins $sec ${pl(sec, "секунда", "секунды", "секунд")}"
        }

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
            RunTracker.start(ru.streetlift.tracker.timer.TimerPrefs.load(context).runCountdown)
            ContextCompat.startForegroundService(context, Intent(context, RunService::class.java))
        }

        fun toggle(context: Context) {
            RunTracker.toggle()
            context.startService(Intent(context, RunService::class.java))
        }

        /** Отменить во время обратного отсчёта. */
        fun cancel(context: Context) {
            RunTracker.cancel()
            RunWorkout.clear()
            context.stopService(Intent(context, RunService::class.java))
        }

        /** Завершить и сохранить пробежку (если было хоть сколько-то времени). */
        fun stopRun(context: Context) {
            val done = RunTracker.finish()
            RunWorkout.clear()
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
