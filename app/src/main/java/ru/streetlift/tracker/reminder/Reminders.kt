package ru.streetlift.tracker.reminder

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.streetlift.tracker.MainActivity
import ru.streetlift.tracker.NavRequests
import ru.streetlift.tracker.R
import ru.streetlift.tracker.StreetLiftApp
import ru.streetlift.tracker.data.db.SettingsEntity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Напоминания о тренировке: накануне вечером («Завтра тренировка») и в день тренировки
 * («Сегодня тренировка» с кнопками «Начать» / «Завтра»). Точное время — если разрешено.
 */
object Reminders {
    const val CHANNEL = "workout_reminders"
    const val KIND_EVE = "eve"
    const val KIND_DAY = "day"
    private const val EXTRA_KIND = "kind"
    private const val EXTRA_DAY = "day"
    const val ACTION_FIRE = "ru.streetlift.tracker.REMIND"
    const val ACTION_POSTPONE = "ru.streetlift.tracker.REMIND_POSTPONE"
    private const val NOTIF_ID = 2001

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Напоминания о тренировке", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Накануне и в день тренировки"
            },
        )
    }

    /** Можно ли ставить на точное время (Android 12+ требует разрешения). */
    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Экран системных настроек «Будильники и напоминания» для этого приложения. */
    fun exactSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 31) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
        } else null

    /** День, на который ставятся напоминания: запланированный, а если он прошёл без тренировки — завтра. */
    fun targetDay(s: SettingsEntity, now: LocalDateTime): Long {
        val today = now.toLocalDate().toEpochDay()
        val d = maxOf(today, s.nextWorkoutEpochDay ?: today)
        return if (d == today && now.toLocalTime().toSecondOfDay() / 60 >= s.remindDayMin) today + 1 else d
    }

    /** Переставить оба напоминания по текущим настройкам. */
    fun reschedule(context: Context, s: SettingsEntity) {
        val am = context.getSystemService(AlarmManager::class.java)
        val now = LocalDateTime.now()
        val day = targetDay(s, now)
        val zone = ZoneId.systemDefault()
        fun at(epochDay: Long, minutes: Int): Long =
            LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant().toEpochMilli()
        val nowMs = System.currentTimeMillis()

        val eveAt = at(day - 1, s.remindEveMin)
        val dayAt = at(day, s.remindDayMin)
        set(context, am, KIND_EVE, day, if (s.remindEve && eveAt > nowMs) eveAt else null)
        set(context, am, KIND_DAY, day, if (s.remindDay && dayAt > nowMs) dayAt else null)
    }

    @SuppressLint("MissingPermission")
    private fun set(context: Context, am: AlarmManager, kind: String, day: Long, at: Long?) {
        val code = if (kind == KIND_EVE) 100 else 101
        val pi = PendingIntent.getBroadcast(
            context, code,
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_FIRE)
                .putExtra(EXTRA_KIND, kind).putExtra(EXTRA_DAY, day),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (at == null) return
        try {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Переставить по настройкам из базы (после загрузки, срабатывания, разрешения). */
    fun rescheduleFromDb(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext as StreetLiftApp
        scope.launch {
            try { reschedule(context.applicationContext, app.repository.getSettings()) } finally { done() }
        }
    }

    internal fun handle(context: Context, intent: Intent, done: () -> Unit) {
        val app = context.applicationContext as StreetLiftApp
        val repo = app.repository
        when (intent.action) {
            ACTION_POSTPONE -> {
                NotificationManagerCompat.from(context).cancel(NOTIF_ID)
                scope.launch { try { repo.postpone() } finally { done() } }
            }
            ACTION_FIRE -> {
                val kind = intent.getStringExtra(EXTRA_KIND) ?: return done()
                val day = intent.getLongExtra(EXTRA_DAY, -1L)
                scope.launch {
                  try {
                    val s = repo.getSettings()
                    val today = LocalDate.now().toEpochDay()
                    // Тренировку уже сделали, перенесли дальше или идёт — не напоминаем
                    val pending = maxOf(today, s.nextWorkoutEpochDay ?: today) <= day
                    val enabled = if (kind == KIND_EVE) s.remindEve else s.remindDay
                    if (enabled && pending && !repo.hasActiveSession()) {
                        val dayName = repo.plannedDay()?.name.orEmpty()
                        notify(context, kind, dayName)
                    }
                    reschedule(context, s)
                  } finally { done() }
                }
            }
            else -> done()
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, kind: String, dayName: String) {
        val open = PendingIntent.getActivity(
            context, 20,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(if (kind == KIND_EVE) "Завтра тренировка" else "Сегодня тренировка")
            .setContentText(dayName)
            .setColor(0xFF2196F3.toInt())
            .setAutoCancel(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (kind == KIND_DAY) {
            val start = PendingIntent.getActivity(
                context, 21,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(NavRequests.EXTRA_START_WORKOUT, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val later = PendingIntent.getBroadcast(
                context, 22,
                Intent(context, ReminderReceiver::class.java).setAction(ACTION_POSTPONE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(0, "Начать", start).addAction(0, "Завтра", later)
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIF_ID, b.build()) }
    }
}

/** Срабатывание напоминания и кнопка «Завтра». */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        Reminders.handle(context, intent) { pr.finish() }
    }
}

/** После перезагрузки и смены разрешения на точное время — переставить напоминания. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        Reminders.rescheduleFromDb(context) { pr.finish() }
    }
}
