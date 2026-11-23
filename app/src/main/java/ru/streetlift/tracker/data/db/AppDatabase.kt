package ru.streetlift.tracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun programDao(): ProgramDao
    abstract fun sessionDao(): SessionDao

    companion object {
        /** v9: пропуск плановой разгрузки и счётчик циклов со смены стадии. Данные сохраняются. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN skipPlannedDeload INTEGER")
                db.execSQL("ALTER TABLE exercises ADD COLUMN cyclesSinceStage INTEGER NOT NULL DEFAULT 99")
            }
        }

        /** v4: снимок для отката тренировки. Данные сохраняются. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN rollback TEXT")
            }
        }

        /** v3: поля отмены пропуска дня. Данные сохраняются. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN undoDayIndex INTEGER")
                db.execSQL("ALTER TABLE settings ADD COLUMN undoCycleInMeso INTEGER")
                db.execSQL("ALTER TABLE settings ADD COLUMN undoCompletedRounds INTEGER")
                db.execSQL("ALTER TABLE settings ADD COLUMN undoOverride TEXT")
                db.execSQL("ALTER TABLE settings ADD COLUMN undoNextEpochDay INTEGER")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "streetlift.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_8_9)
                // Самая первая сборка (v1) не сохраняется — пересоздаётся
                .fallbackToDestructiveMigration()
                .build()
    }
}
