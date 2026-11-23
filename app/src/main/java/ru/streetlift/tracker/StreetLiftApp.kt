package ru.streetlift.tracker

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }
}
