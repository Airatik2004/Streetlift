package ru.streetlift.tracker.timer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.media.Ringtone
import android.media.SoundPool
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.streetlift.tracker.MainActivity
import ru.streetlift.tracker.R
import ru.streetlift.tracker.domain.Fmt
import kotlin.math.abs

/**
 * Foreground Service: держит процесс живым во время отдыха, показывает уведомление
 * с обратным отсчётом и кнопками, по окончании — звук + вибрация.
 */
class TimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var ringtone: Ringtone? = null
    private var stopJob: Job? = null
    private var inForeground = false
    private var lastKey = ""
    private var lastEndWall = 0L
    private var lastAcquire = 0L
    private var prefs = TimerPrefs.Prefs()
    private var vibrator: Vibrator? = null
    private var tone: ToneGenerator? = null
    private var lastBeepSec = -1
    private var soundPool: SoundPool? = null
    private var gongId = 0
    private var pipId = 0
    private val loaded = mutableSetOf<Int>()
    private var gongStream = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        initSoundPool()
        scope.launch {
            var wasFinished = RestTimer.state.value.finished
            RestTimer.state.collect { st ->
                handleState(st, wasFinished)
                wasFinished = st.finished
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Всегда сначала уходим в foreground — иначе система убьёт сервис.
        goForeground(RestTimer.state.value)
        prefs = TimerPrefs.load(this)
        when (intent?.action) {
            ACTION_TOGGLE -> RestTimer.toggle()
            ACTION_PLUS -> RestTimer.add(15_000)
            ACTION_MINUS -> RestTimer.add(-15_000)
            ACTION_STOP -> {
                stopAlarm()
                RestTimer.reset()
                shutdown()
            }
        }
        if (RestTimer.state.value.idle) shutdown()
        return START_NOT_STICKY
    }

    private fun handleState(st: RestTimer.State, wasFinished: Boolean) {
        if (st.running) {
            stopJob?.cancel()
            stopJob = null
            stopAlarm()
            holdWakeLock(st.remainingMs + 60_000)
            val sec = st.remainingSeconds
            if (prefs.countdownBeeps && sec in 1..3 && sec != lastBeepSec) {
                lastBeepSec = sec
                beep()
            } else if (sec > 3) {
                lastBeepSec = -1
            }
        } else if (!st.finished) {
            releaseWakeLock()
        }
        if (st.finished && !wasFinished) {
            onTimerFinished(st)
            return
        }
        if (st.idle) {
            shutdown()
            return
        }
        updateNotification(st)
    }

    private fun onTimerFinished(st: RestTimer.State) {
        val p = TimerPrefs.load(this).also { prefs = it }
        // Ровно один свисток; системной мелодии даём прозвучать до 3 с
        val signalMs = if (p.soundEnabled && p.soundUri != null) 3_000L else 2_000L
        holdWakeLock(signalMs + 5_000)
        if (p.soundEnabled) playAlarm(p)
        vibrate(p)
        notifySafe(DONE_ID, buildDoneNotification(st))
        updateNotification(st)
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(signalMs)
            stopAlarm()
            shutdown()
        }
    }

    // ---------------- Уведомления ----------------

    private fun goForeground(st: RestTimer.State) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, ONGOING_ID, buildOngoing(st), type)
            inForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun updateNotification(st: RestTimer.State) {
        if (!inForeground) return
        val endWall = System.currentTimeMillis() + st.remainingMs
        val key = "${st.running}|${st.finished}|${st.label}|${st.totalMs}|" +
            if (st.running) "" else st.remainingSeconds.toString()
        // Во время отсчёта уведомление само тикает (chronometer), обновляем только при изменениях
        if (key == lastKey && (!st.running || abs(endWall - lastEndWall) < 1_500)) return
        lastKey = key
        lastEndWall = endWall
        notifySafe(ONGOING_ID, buildOngoing(st))
    }

    private fun buildOngoing(st: RestTimer.State): Notification {
        val title = when {
            st.finished -> "Отдых окончен — к снаряду!"
            st.running -> "Отдых"
            else -> "Пауза · ${Fmt.clock(st.remainingSeconds)}"
        }
        val b = NotificationCompat.Builder(this, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(st.label.ifBlank { "Таймер отдыха" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (st.running) {
            b.setWhen(System.currentTimeMillis() + st.remainingMs)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        b.addAction(0, if (st.running) "Пауза" else "Старт", actionIntent(ACTION_TOGGLE, 1))
        b.addAction(0, "+15с", actionIntent(ACTION_PLUS, 2))
        b.addAction(0, "Стоп", actionIntent(ACTION_STOP, 3))
        return b.build()
    }

    private fun buildDoneNotification(st: RestTimer.State): Notification =
        NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle("Отдых окончен")
            .setContentText(st.label.ifBlank { "Пора делать следующий подход" })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setContentIntent(openAppIntent())
            .build()

    @SuppressLint("MissingPermission")
    private fun notifySafe(id: Int, n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "notify denied", e)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, code: Int): PendingIntent {
        val i = Intent(this, TimerService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            this, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ---------------- Звук, вибрация, wake lock ----------------

    private fun initSoundPool() {
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build().also { sp ->
                sp.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded += id }
                gongId = sp.load(this, R.raw.timer_whistle, 1)
                pipId = sp.load(this, R.raw.timer_pip, 1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "soundpool failed", e)
        }
    }

    private fun playAlarm(p: TimerPrefs.Prefs) {
        val sp = soundPool
        if (p.soundUri == null && sp != null && gongId in loaded) {
            stopAlarm()
            val v = p.volume.coerceIn(0.1f, 1f)
            gongStream = sp.play(gongId, v, v, 1, 0, 1f) // один раз
            return
        }
        try {
            stopAlarm()
            val uri = p.soundUri?.let { Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= 28) {
                    isLooping = false
                    volume = p.volume.coerceIn(0.1f, 1f)
                }
                play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ringtone failed", e)
        }
    }

    private fun stopAlarm() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
        if (gongStream != 0) {
            soundPool?.stop(gongStream)
            gongStream = 0
        }
        try {
            vibrator?.cancel()
        } catch (_: Exception) {
        }
        vibrator = null
    }

    private fun beep() {
        val sp = soundPool
        if (sp != null && pipId in loaded) {
            val v = prefs.volume.coerceIn(0.1f, 1f)
            sp.play(pipId, v, v, 2, 0, 1f)
            return
        }
        try {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, (prefs.volume * 100).toInt().coerceIn(10, 100))
                .also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (e: Exception) {
            Log.w(TAG, "beep failed", e)
        }
    }

    private fun vibrate(p: TimerPrefs.Prefs) {
        if (!p.vibration) return
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            // Одна вибрация длиной со свисток
            vib.vibrate(VibrationEffect.createOneShot(TimerPrefs.VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE))
            vibrator = vib
        } catch (e: Exception) {
            Log.w(TAG, "vibrate failed", e)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun holdWakeLock(timeoutMs: Long) {
        val wl = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreetLift:RestTimer")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        // Повторный acquire у non-reference-counted лока продлевает таймаут — не чаще раза в 20 с
        val now = android.os.SystemClock.elapsedRealtime()
        if (!wl.isHeld || now - lastAcquire > 20_000 || timeoutMs <= 10_000) {
            wl.acquire(timeoutMs.coerceAtLeast(5_000))
            lastAcquire = now
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun shutdown() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        inForeground = false
        lastKey = ""
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopAlarm()
        tone?.release()
        tone = null
        soundPool?.release()
        soundPool = null
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TimerService"
        const val CH_RUNNING = "rest_timer"
        const val CH_DONE = "rest_done"
        private const val ONGOING_ID = 1001
        private const val DONE_ID = 1002

        const val ACTION_TOGGLE = "ru.streetlift.tracker.timer.TOGGLE"
        const val ACTION_PLUS = "ru.streetlift.tracker.timer.PLUS"
        const val ACTION_MINUS = "ru.streetlift.tracker.timer.MINUS"
        const val ACTION_STOP = "ru.streetlift.tracker.timer.STOP"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_RUNNING, "Таймер отдыха", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Обратный отсчёт между подходами"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_DONE, "Окончание отдыха", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Сигнал, что пора делать подход"
                    setSound(null, null) // звук играет сам сервис (поток будильника)
                    enableVibration(false) // вибрация — тоже сервис
                },
            )
        }

        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "cannot start service", e)
            }
        }
    }
}

/** Действия таймера из UI: меняют состояние и поднимают сервис. */
object TimerActions {
    /** Выставить время без запуска (пресеты, рекомендация, ручной ввод). */
    fun set(ms: Long, label: String = RestTimer.state.value.label) = RestTimer.setDuration(ms, label)

    fun start(context: Context, ms: Long, label: String = RestTimer.state.value.label) {
        RestTimer.start(ms, label)
        TimerService.ensureRunning(context)
    }

    fun toggle(context: Context) {
        RestTimer.toggle()
        if (RestTimer.state.value.running) TimerService.ensureRunning(context)
    }

    fun add(deltaMs: Long) = RestTimer.add(deltaMs)

    fun reset() = RestTimer.reset()
}
