package ru.streetlift.tracker.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Защита от вылета при обновлении: SQL в миграциях обращается только к существующим таблицам.
 * (Миграция 22→23 обращалась к несуществующей «stages» — приложение падало на старте.)
 */
class MigrationSqlTest {
    private fun src(path: String): String {
        val f = listOf(File("src/main/java/ru/streetlift/tracker/$path"), File("app/src/main/java/ru/streetlift/tracker/$path"))
            .first { it.exists() }
        return f.readText()
    }

    @Test
    fun migrations_useKnownTables() {
        val tables = Regex("""tableName = "([a-z_]+)"""").findAll(src("data/db/Entities.kt")).map { it.groupValues[1] }.toSet()
        val used = Regex("""(?:UPDATE|ALTER TABLE|INSERT INTO|DELETE FROM)\s+`?([a-z_]+)""")
            .findAll(src("data/db/AppDatabase.kt")).map { it.groupValues[1] }.toSet()
        val unknown = used - tables
        assertTrue("Неизвестные таблицы в миграциях: $unknown", unknown.isEmpty())
    }
}
