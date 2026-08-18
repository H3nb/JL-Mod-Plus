/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMigrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun migrationRegistryCoversEverySupportedAdjacentVersion() {
        assertEquals(
            emptyList<Pair<Int, Int>>(),
            LibraryMigrations.missingAdjacentTransitions(LibraryDatabase.SCHEMA_VERSION),
        )
        val transitions = LibraryMigrations.ALL.map { it.startVersion to it.endVersion }
        assertEquals(transitions.size, transitions.toSet().size)
        assertTrue(transitions.all { (start, end) -> end == start + 1 })
    }

    /**
     * Rebuild every committed historical Room snapshot as a real SQLite file, then let the current
     * Room database open it using the exact production migration registry. Adding schema v3 makes
     * this exercise v1 -> v3 and v2 -> v3 automatically.
     */
    @Test fun everyHistoricalSchemaOpensAndMigratesToLatest() = runBlocking {
        for (version in LibraryMigrations.FIRST_SUPPORTED_VERSION until LibraryDatabase.SCHEMA_VERSION) {
            val file = File(temporaryFolder.root, "schema-$version.db")
            createFromCommittedSchema(version, file)
            openLatest(file).use { database ->
                // Force Room to open, migrate, and validate instead of only creating a lazy builder.
                database.libraryDao().getStorageKeys()
            }
        }
    }

    @Test fun schema1MigratesToLatestWithoutLosingLibraryOwnedState() = runBlocking {
        val file = File(temporaryFolder.root, "preserve-state.db")
        createFromCommittedSchema(1, file)
        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            connection.execSQL(
                """
                INSERT INTO apps (
                    id, storage_key, source_title, source_vendor, source_version,
                    source_description, custom_title, custom_vendor, custom_version,
                    custom_description, favorite, added_at, last_played_at, play_count,
                    total_play_time_ms, icon_revision
                ) VALUES (
                    7, 'game', 'Source Game', 'Vendor', '1.0', 'Source description',
                    'Custom Game', 'Custom Vendor', 'Special', 'Custom description',
                    1, 100, 200, 3, 4000, 9
                )
                """.trimIndent(),
            )
            connection.execSQL(
                "INSERT INTO collections (id, name, normalized_name, created_at, sort_order) " +
                    "VALUES (5, 'RPG', 'rpg', 300, 0)",
            )
            connection.execSQL(
                "INSERT INTO collection_apps (collection_id, app_id, added_at) VALUES (5, 7, 400)",
            )
            connection.execSQL("INSERT INTO play_stat_receipts (session_id) VALUES ('session-1')")
            connection.execSQL("INSERT INTO library_state (id, bootstrap_state) VALUES (1, 'READY')")
        }

        openLatest(file).use { database ->
            val dao = database.libraryDao()
            val app = requireNotNull(dao.getApp(7))
            assertEquals("game", app.storageKey)
            assertEquals("Custom Game", app.customTitle)
            assertEquals("Custom Vendor", app.customVendor)
            assertEquals("Special", app.customVersion)
            assertEquals("Custom description", app.customDescription)
            assertTrue(app.favorite)
            assertEquals(100L, app.addedAt)
            assertEquals(200L, app.lastPlayedAt)
            assertEquals(3L, app.playCount)
            assertEquals(4_000L, app.totalPlayTimeMs)
            assertEquals(9L, app.iconRevision)
            assertEquals(listOf(7L), dao.getCollectionAppIds(5))
            assertEquals("READY", dao.getLibraryState()?.bootstrapState)
            assertEquals(-1L, dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-1")))
            assertNotNull(dao.getAppByStorageKey("game"))
        }
    }

    private fun openLatest(file: File): LibraryDatabase =
        Room.databaseBuilder<LibraryDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*LibraryMigrations.ALL)
            .build()

    private fun createFromCommittedSchema(version: Int, file: File) {
        require(version < LibraryDatabase.SCHEMA_VERSION) { "Only historical schemas are reconstructed" }
        val schema = schemaFile(version)
        val databaseJson = schema.reader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject.getAsJsonObject("database")
        }
        assertEquals(version, databaseJson.get("version").asInt)

        BundledSQLiteDriver().open(file.absolutePath).use { connection ->
            databaseJson.getAsJsonArray("entities").forEach { element ->
                val entity = element.asJsonObject
                connection.execSnapshotSql(entity, "createSql")
                entity.getAsJsonArray("indices")?.forEach { index ->
                    connection.execSnapshotSql(index.asJsonObject, "createSql")
                }
            }
            databaseJson.getAsJsonArray("setupQueries").forEach { query ->
                connection.execSQL(query.asString)
            }
            connection.execSQL("PRAGMA user_version = $version")
        }
    }

    private fun SQLiteConnection.execSnapshotSql(node: JsonObject, field: String) {
        val tableName = node.get("tableName")?.asString
        val sql = node.get(field)?.asString ?: return
        execSQL(if (tableName == null) sql else sql.replace("${'$'}{TABLE_NAME}", tableName))
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/${LibraryDatabase::class.qualifiedName}/$version.json"
        val candidates = listOf(File(relative), File("app", relative))
        return candidates.firstOrNull(File::isFile)
            ?: error("Room schema $version not found from ${File(".").absolutePath}")
    }
}
