/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryDatabaseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao

    @Before fun setUp() {
        val file = temporaryFolder.newFile(LibraryDatabase.FILE_NAME)
        assertTrue(file.delete())
        database = Room.databaseBuilder<LibraryDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        dao = database.libraryDao()
    }

    @After fun tearDown() = database.close()

    @Test fun duplicateSourceIdentityIsAllowedButStorageKeyIsDistinct() = runBlocking {
        dao.insertApp(app("game-a"))
        dao.insertApp(app("game-b"))
        val matches = dao.findAppsBySourceIdentity("Game", "Vendor")
        assertEquals(2, matches.size)
        assertEquals(setOf("game-a", "game-b"), matches.map { it.storageKey }.toSet())
    }

    @Test fun normalizedCollectionNameIsUnique() = runBlocking {
        val first = LibraryCollectionEntity(name = "  My   Games  ", createdAt = 1L)
        val duplicate = LibraryCollectionEntity(name = "my games", createdAt = 2L)
        assertEquals("my games", first.normalizedName)
        assertEquals(first.normalizedName, duplicate.normalizedName)
        dao.insertCollection(first)
        try {
            dao.insertCollection(duplicate)
            throw AssertionError("Expected normalized collection-name uniqueness failure")
        } catch (_: Exception) {
            // Expected: display casing/spacing differs, normalized identity does not.
        }
    }

    @Test fun reinstallMutationPreservesAllLibraryOwnedState() = runBlocking {
        val id = dao.insertApp(
            app(
                storageKey = "game-a",
                customTitle = "My Game",
                favorite = true,
                addedAt = 123L,
                lastPlayedAt = 456L,
                playCount = 7,
                totalPlayTimeMs = 8_000L,
            ),
        )
        val collectionId = dao.insertCollection(
            LibraryCollectionEntity(name = "Collection", createdAt = 1L),
        )
        dao.insertCollectionMembership(
            LibraryCollectionAppEntity(collectionId = collectionId, appId = id, addedAt = 2L),
        )

        val returnedId = dao.recordInstalledApp(
            existingId = id,
            metadata = InstalledAppMetadata(
                storageKey = "game-a",
                sourceTitle = "Game Updated",
                sourceVendor = "Vendor",
                sourceVersion = "2.0",
                sourceDescription = "Updated description",
                iconRevision = 9L,
                addedAt = 999L,
            ),
        )

        assertEquals(id, returnedId)
        val updated = requireNotNull(dao.getApp(id))
        assertEquals("Game Updated", updated.sourceTitle)
        assertEquals("2.0", updated.sourceVersion)
        assertEquals("My Game", updated.customTitle)
        assertTrue(updated.favorite)
        assertEquals(123L, updated.addedAt)
        assertEquals(456L, updated.lastPlayedAt)
        assertEquals(7L, updated.playCount)
        assertEquals(8_000L, updated.totalPlayTimeMs)
        assertEquals(-1L, dao.insertCollectionMembership(
            LibraryCollectionAppEntity(collectionId = collectionId, appId = id, addedAt = 3L),
        ))
    }

    @Test fun newInstallRecordsActualAddedAt() = runBlocking {
        val id = dao.recordInstalledApp(
            existingId = null,
            metadata = InstalledAppMetadata(
                storageKey = "new-game",
                sourceTitle = "New Game",
                sourceVendor = "Vendor",
                sourceVersion = "1.0",
                sourceDescription = null,
                iconRevision = 1L,
                addedAt = 42L,
            ),
        )
        assertEquals(42L, dao.getApp(id)?.addedAt)
    }

    @Test fun implicitStorageKeyMigrationIsRejected() = runBlocking {
        val id = dao.insertApp(app("original"))
        try {
            dao.recordInstalledApp(
                existingId = id,
                metadata = InstalledAppMetadata(
                    storageKey = "renamed",
                    sourceTitle = "Game",
                    sourceVendor = "Vendor",
                    sourceVersion = "2.0",
                    sourceDescription = null,
                    iconRevision = 1L,
                    addedAt = 5L,
                ),
            )
            throw AssertionError("Expected storage-key migration rejection")
        } catch (_: IllegalStateException) {
            assertNotNull(dao.getAppByStorageKey("original"))
            assertNull(dao.getAppByStorageKey("renamed"))
        }
    }

    @Test fun customTitleCanBeResetWithoutChangingSourceIdentity() = runBlocking {
        val id = dao.insertApp(app("game", customTitle = "Renamed"))
        assertEquals(1, dao.updateCustomTitle(id, null))
        val row = dao.observeApps().first().single()
        assertEquals("Game", row.title)
        assertEquals("Game", row.sourceTitle)
        assertEquals("Vendor", row.sourceVendor)
    }

    @Test fun deletingAppCascadesMembershipButDoesNotDeletePlayReceipt() = runBlocking {
        val appId = dao.insertApp(app("game-a"))
        val collectionId = dao.insertCollection(
            LibraryCollectionEntity(name = "Favorites from school", createdAt = 1L),
        )
        assertTrue(dao.insertCollectionMembership(
            LibraryCollectionAppEntity(collectionId = collectionId, appId = appId, addedAt = 2L),
        ) >= 0)
        assertTrue(dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-1")) >= 0)

        assertEquals(1, dao.deleteAppByStorageKey("game-a"))
        assertNull(dao.getApp(appId))
        assertEquals(-1L, dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-1")))
    }

    @Test fun listProjectionUsesEffectiveMetadataWithoutFilesystemState() = runBlocking {
        dao.insertApp(app("game-a", customTitle = "Custom title", favorite = true, addedAt = 100L))
        val row = dao.observeApps().first().single()
        assertEquals("Custom title", row.title)
        assertEquals("Game", row.sourceTitle)
        assertEquals("Vendor", row.sourceVendor)
        assertTrue(row.favorite)
        assertEquals(100L, row.addedAt)
    }

    private fun app(
        storageKey: String,
        customTitle: String? = null,
        favorite: Boolean = false,
        addedAt: Long? = null,
        lastPlayedAt: Long? = null,
        playCount: Long = 0,
        totalPlayTimeMs: Long = 0,
    ) = LibraryAppEntity(
        storageKey = storageKey,
        sourceTitle = "Game",
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
        sourceDescription = "Source description",
        customTitle = customTitle,
        favorite = favorite,
        addedAt = addedAt,
        lastPlayedAt = lastPlayedAt,
        playCount = playCount,
        totalPlayTimeMs = totalPlayTimeMs,
    )
}
