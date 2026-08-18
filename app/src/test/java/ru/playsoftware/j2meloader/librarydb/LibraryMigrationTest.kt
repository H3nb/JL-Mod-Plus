/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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

    @Test fun schema1MigratesToLatestWithoutLosingLibraryOwnedState() = runBlocking {
        val databasePath = File(temporaryFolder.root, "migration.db").toPath()
        val helper = MigrationTestHelper(
            schemaDirectoryPath = schemaDirectory().toPath(),
            databasePath = databasePath,
            driver = BundledSQLiteDriver(),
            databaseClass = LibraryDatabase::class,
        )

        helper.createDatabase(1).use { connection ->
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
            connection.execSQL(
                "INSERT INTO play_stat_receipts (session_id) VALUES ('session-1')",
            )
            connection.execSQL(
                "INSERT INTO library_state (id, bootstrap_state) VALUES (1, 'READY')",
            )
        }

        helper.runMigrationsAndValidate(
            LibraryDatabase.SCHEMA_VERSION,
            LibraryMigrations.ALL.toList(),
        ).close()

        val database = Room.databaseBuilder<LibraryDatabase>(databasePath.toString())
            .setDriver(BundledSQLiteDriver())
            .addMigrations(*LibraryMigrations.ALL)
            .build()
        try {
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
        } finally {
            database.close()
        }
    }

    private fun schemaDirectory(): File {
        val relative = "schemas/${LibraryDatabase::class.qualifiedName}"
        val candidates = listOf(
            File(relative),
            File("app", relative),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("Room schema directory not found from ${File(".").absolutePath}")
    }
}
