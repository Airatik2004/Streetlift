package ru.streetlift.tracker.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ru.streetlift.tracker.data.db.DayEntity
import ru.streetlift.tracker.data.db.ExerciseEntity
import ru.streetlift.tracker.data.db.SessionEntity
import ru.streetlift.tracker.data.db.SetLogEntity
import ru.streetlift.tracker.data.db.SettingsEntity
import ru.streetlift.tracker.data.db.StageArchiveEntity
import ru.streetlift.tracker.data.db.StageEntity

/** Полный снимок базы для экспорта/импорта. */
@Serializable
data class BackupData(
    val app: String = "StreetLift",
    val formatVersion: Int = 2,
    val exportedAt: Long,
    val settings: SettingsEntity,
    val days: List<DayEntity>,
    val exercises: List<ExerciseEntity>,
    val sessions: List<SessionEntity>,
    val sets: List<SetLogEntity>,
    val stages: List<StageEntity> = emptyList(),
    val stageArchive: List<StageArchiveEntity> = emptyList(),
)

/** Состояние до завершения тренировки. */
@Serializable
data class RollbackData(val settings: SettingsEntity, val exercises: List<ExerciseEntity>)

object BackupJson {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(data: BackupData): String = json.encodeToString(BackupData.serializer(), data)

    fun decode(text: String): BackupData = json.decodeFromString(BackupData.serializer(), text)

    fun encodeRollback(data: RollbackData): String = json.encodeToString(RollbackData.serializer(), data)

    fun decodeRollback(text: String): RollbackData = json.decodeFromString(RollbackData.serializer(), text)
}
