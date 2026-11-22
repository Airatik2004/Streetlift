package ru.streetlift.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SettingsEntity::class,
        DayEntity::class,
        ExerciseEntity::class,
        SessionEntity::class,
        SetLogEntity::class,
        StageEntity::class,
        StageArchiveEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun programDao(): ProgramDao
    abstract fun sessionDao(): SessionDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "streetlift.db")
                // Ранних пользователей нет — старые схемы просто пересоздаются
                .fallbackToDestructiveMigration()
                .build()
    }
}
