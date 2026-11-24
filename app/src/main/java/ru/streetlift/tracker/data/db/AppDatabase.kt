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
        ManualRecordEntity::class,
        RunEntity::class,
    ],
    version = 20,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun programDao(): ProgramDao
    abstract fun sessionDao(): SessionDao
    abstract fun statsDao(): StatsDao

    companion object {
        /** v20: прыжки — шаг 15 см, блок пробивки весом, откат и промежуточный вес. */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN jumpBlock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exercises ADD COLUMN jumpBlockCm INTEGER")
                db.execSQL("ALTER TABLE exercises ADD COLUMN jumpStep INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exercises ADD COLUMN jumpRollback INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE exercises ADD COLUMN jumpNextKg REAL")
                db.execSQL("ALTER TABLE exercises ADD COLUMN jumpNextReps INTEGER")
                db.execSQL("ALTER TABLE exercises ADD COLUMN jumpRetryKg REAL")
                // Старая высота с шагом 5 см — вниз до кратной 15, старое плато сбрасываем
                db.execSQL("UPDATE exercises SET heightCm = MAX(15, (heightCm / 15) * 15), plateauCm = NULL, weightKg = 0, targetReps = 3, declineStreak = 0 WHERE heightCm IS NOT NULL AND name = 'Прыжки на тумбу'")
            }
        }

        /** v19: у тренировки — цикл в мезоцикле и что выпало в Дне 1 (пистолетики/прыжки). */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN cycleInMeso INTEGER")
                db.execSQL("ALTER TABLE sessions ADD COLUMN altSlot INTEGER")
            }
        }

        /** v18: «Обратные разведения на заднюю дельту» → «Разведения на дельту» (программа, история, рекорды). */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val old = "Обратные разведения на заднюю дельту"
                val new = "Разведения на дельту"
                db.execSQL("UPDATE exercises SET name = ? WHERE name = ?", arrayOf(new, old))
                db.execSQL("UPDATE set_logs SET exerciseName = ? WHERE exerciseName = ?", arrayOf(new, old))
                db.execSQL("UPDATE stage_archive SET exerciseName = ? WHERE exerciseName = ?", arrayOf(new, old))
                db.execSQL("UPDATE manual_records SET exerciseName = ? WHERE exerciseName = ?", arrayOf(new, old))
            }
        }

        /** v17: прыжки на тумбу — высота. */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN heightCm INTEGER")
                db.execSQL("ALTER TABLE exercises ADD COLUMN plateauCm INTEGER")
                db.execSQL("ALTER TABLE set_logs ADD COLUMN heightCm INTEGER")
            }
        }

        /** v16: сторона подхода у односторонних упражнений. */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE set_logs ADD COLUMN side TEXT")
            }
        }

        /** v15: шаг веса 1,25 кг у всех упражнений. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE exercises SET weightStep = 1.25")
            }
        }

        /** v14: напоминание вечером, если тренировка не сделана. */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN remindLate INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN remindLateMin INTEGER NOT NULL DEFAULT 1260")
            }
        }

        /** v13: рекорды вручную и пробежки. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `manual_records` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`exerciseName` TEXT NOT NULL, `leverageStage` TEXT, `weightKg` REAL NOT NULL, `value` INTEGER NOT NULL, " +
                        "`isSeconds` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `durationMs` INTEGER NOT NULL, `route` TEXT NOT NULL)",
                )
            }
        }

        /** v12: напоминания о тренировке и пометка «после паузы» у тренировки. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE settings ADD COLUMN remindEve INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN remindEveMin INTEGER NOT NULL DEFAULT 1260")
                db.execSQL("ALTER TABLE settings ADD COLUMN remindDay INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE settings ADD COLUMN remindDayMin INTEGER NOT NULL DEFAULT 1020")
                db.execSQL("ALTER TABLE sessions ADD COLUMN afterPause INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN manualDay INTEGER NOT NULL DEFAULT 0")
            }
        }

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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
                // Самая первая сборка (v1) не сохраняется — пересоздаётся
                .fallbackToDestructiveMigration()
                .build()
    }
}
