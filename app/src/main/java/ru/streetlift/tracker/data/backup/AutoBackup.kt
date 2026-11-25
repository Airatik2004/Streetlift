package ru.streetlift.tracker.data.backup

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import ru.streetlift.tracker.data.repo.WorkoutRepository

/**
 * Автокопия базы: JSON в «Загрузки/StreetLift» после каждой тренировки и пробежки и не реже раза в день.
 * Хранятся последние [KEEP] файлов. Восстановление — обычный «Импорт» в Истории.
 */
object AutoBackup {
    const val KEEP = 10
    const val FOLDER = "StreetLift"
    private const val PREFIX = "streetlift-auto-"
    private const val PREFS = "autobackup"
    private const val KEY_LAST = "last"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Время последней автокопии (мс), 0 — не было. */
    fun last(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST, 0L)

    /** Копия, если с прошлой прошли сутки. */
    suspend fun daily(ctx: Context, repo: WorkoutRepository) {
        if (System.currentTimeMillis() - last(ctx) >= DAY_MS) run(ctx, repo)
    }

    /** Сделать копию сейчас. Ошибки не роняют приложение — копия просто пропускается. */
    suspend fun run(ctx: Context, repo: WorkoutRepository): Boolean = runCatching {
        // Android 10+: запись в общие «Загрузки» без разрешений
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val json = repo.exportJson()
        val name = PREFIX + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss")) + ".json"
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$FOLDER/")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: return false
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
        prune(ctx)
        true
    }.getOrDefault(false)

    /** Оставить последние [KEEP] автокопий (видны только свои файлы приложения). */
    private fun prune(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = ctx.contentResolver
        val ids = mutableListOf<Long>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("$PREFIX%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        ids.drop(KEEP).forEach { id ->
            runCatching { resolver.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }
}
