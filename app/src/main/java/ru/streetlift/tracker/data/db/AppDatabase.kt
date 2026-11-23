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
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun programDao(): ProgramDao
    abstract fun sessionDao(): SessionDao

    companion object {
        /** v11: набор резин (помощь в кг). Отрицательные веса приводятся к сочетаниям при запуске. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN bands TEXT NOT NULL DEFAULT '$DEFAULT_BANDS'")
            }
        }

        /**
         * v10: свой счётчик разгрузки у каждого упражнения и длина подводки к пику.
         * Счётчик берётся из общего номера цикла, чтобы ближайшая разгрузка не сдвинулась.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN deloadSessions INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN peakSpanDays INTEGER")
                // Сделано тренировок в мезоцикле = (циклов позади) × тренировок в цикле + уже сделанные в текущем
                db.execSQL(
                    """
                    UPDATE exercises SET deloadSessions = (
                        SELECT
                            (MIN(s.cycleInMeso, s.deloadEvery) - 1) * (CASE WHEN exercises.shareKey IS NULL THEN 1 ELSE 2 END)
                            + (SELECT COUNT(*) FROM exercises e2 JOIN days d ON d.id = e2.dayId
                               WHERE (e2.id = exercises.id OR (exercises.shareKey IS NOT NULL AND e2.shareKey = exercises.shareKey))
                                 AND d.position < s.currentDayIndex)
                        FROM settings s WHERE s.id = 1
                    )
                    """.trimIndent(),
                )
                // Разгрузочный цикл уже пройден целиком — новый мезоцикл
                db.execSQL(
                    """
                    UPDATE exercises SET deloadSessions = 0
                    WHERE deloadSessions >= (SELECT deloadEvery FROM settings WHERE id = 1) * (CASE WHEN shareKey IS NULL THEN 1 ELSE 2 END)
                    """.trimIndent(),
                )
                db.execSQL("UPDATE exercises SET deloadSessions = 0 WHERE deloadSessions IS NULL OR deloadSessions < 0")
            }
        }

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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                // Самая первая сборка (v1) не сохраняется — пересоздаётся
                .fallbackToDestructiveMigration()
                .build()
    }
}
