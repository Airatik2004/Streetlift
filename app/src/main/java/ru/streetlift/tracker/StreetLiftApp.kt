package ru.streetlift.tracker

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ru.streetlift.tracker.data.db.AppDatabase
import ru.streetlift.tracker.data.repo.WorkoutRepository
import ru.streetlift.tracker.timer.TimerService

class StreetLiftApp : Application() {

    lateinit var repository: WorkoutRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repository = WorkoutRepository(AppDatabase.build(this))
        appScope.launch { repository.ensureSeeded() }
        TimerService.createChannels(this)
        ru.streetlift.tracker.reminder.Reminders.createChannel(this)
        ru.streetlift.tracker.run.RunService.createChannel(this)
        // Напоминания переставляются при каждом изменении настроек (тренировка, перенос, время)
        appScope.launch {
            repository.settings.collect { ru.streetlift.tracker.reminder.Reminders.reschedule(this@StreetLiftApp, it) }
        }
        ru.streetlift.tracker.timer.Beeper.init(this)
        // Автокопия базы: раз в сутки и после каждой тренировки/пробежки
        appScope.launch {
            ru.streetlift.tracker.data.backup.AutoBackup.daily(this@StreetLiftApp, repository)
            combine(repository.finishedSessions, repository.runs) { s, r -> s.size to r.size }
                .distinctUntilChanged()
                .drop(1)
                .collect { ru.streetlift.tracker.data.backup.AutoBackup.run(this@StreetLiftApp, repository) }
        }
    }
}
