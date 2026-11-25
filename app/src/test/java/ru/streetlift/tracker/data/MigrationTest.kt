package ru.streetlift.tracker.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import ru.streetlift.tracker.data.db.AppDatabase

/**
 * Обновление базы поверх: для каждой пары схем (версия N и N+1 из app/schemas) создаётся база версии N,
 * прогоняются миграции и проверяется, что получилась ровно схема N+1. Плюс цепочка от самой старой схемы
 * до текущей. Ошибка в миграции (как с «stages» в 22→23) роняет сборку, а не приложение у пользователя.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MigrationTest {
    private val schemaDir = AppDatabase::class.java.canonicalName!!

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun versions(): List<Int> =
        InstrumentationRegistry.getInstrumentation().context.assets.list(schemaDir).orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }.sorted()

    @Test
    fun schemasPresent_forCurrentAndPrevious() {
        val v = versions()
        val current = v.maxOrNull() ?: 0
        // Схема прошлой версии обязана лежать в репозитории — иначе последнюю миграцию нечем проверить
        assertTrue("нет схем: $v", v.size >= 2 && (current - 1) in v)
    }

    @Test
    fun eachMigration_producesNextSchema() {
        val v = versions()
        v.filter { it + 1 in v }.forEach { from ->
            val name = "step-$from.db"
            helper.createDatabase(name, from).apply {
                // Немного данных, чтобы UPDATE-миграции работали не по пустым таблицам
                execSQL("INSERT INTO leverage_stages (name, description, position, builtIn, groupKey) VALUES ('Ноги врозь (узкие, ~30°)', 'старое', 4, 1, 'LEVER')")
                close()
            }
            helper.runMigrationsAndValidate(name, from + 1, true, *AppDatabase.ALL_MIGRATIONS).close()
        }
    }

    @Test
    fun fullChain_fromOldestSchema() {
        val v = versions()
        val name = "chain.db"
        helper.createDatabase(name, v.first()).close()
        helper.runMigrationsAndValidate(name, v.last(), true, *AppDatabase.ALL_MIGRATIONS).close()
    }
}
