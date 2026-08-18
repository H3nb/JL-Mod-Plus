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
import org.junit.Assert.assertFalse
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

    @Test fun customMetadataCanBeEditedAndResetWithoutTouchingOtherLibraryState() = runBlocking {
        val id = dao.insertApp(
            LibraryAppEntity(
                storageKey = "game",
                sourceTitle = "Game",
                sourceVendor = "Vendor",
                sourceVersion = "1.0",
                sourceDescription = "Source description",
                favorite = true,
                addedAt = 10L,
                lastPlayedAt = 20L,
                playCount = 3L,
                totalPlayTimeMs = 4_000L,
            ),
        )

        assertEquals(
            1,
            dao.updateCustomMetadata(
                appId = id,
                customTitle = "My Game",
                customVendor = "My Vendor",
                customVersion = "Special",
                customDescription = "My description",
            ),
        )
        val edited = requireNotNull(dao.getApp(id))
        assertEquals("My Game", edited.customTitle)
        assertEquals("My Vendor", edited.customVendor)
        assertEquals("Special", edited.customVersion)
        assertEquals("My description", edited.customDescription)
        assertTrue(edited.favorite)
        assertEquals(10L, edited.addedAt)
        assertEquals(20L, edited.lastPlayedAt)
        assertEquals(3L, edited.playCount)
        assertEquals(4_000L, edited.totalPlayTimeMs)

        val editedRow = dao.observeApps().first().single()
        assertEquals("My Game", editedRow.title)
        assertEquals("My Vendor", editedRow.vendor)
        assertEquals("Special", editedRow.version)
        assertEquals("My description", editedRow.description)

        assertEquals(1, dao.resetCustomMetadata(id))
        val reset = requireNotNull(dao.getApp(id))
        assertNull(reset.customTitle)
        assertNull(reset.customVendor)
        assertNull(reset.customVersion)
        assertNull(reset.customDescription)
        assertTrue(reset.favorite)
        assertEquals(3L, reset.playCount)

        val resetRow = dao.observeApps().first().single()
        assertEquals("Game", resetRow.title)
        assertEquals("Vendor", resetRow.vendor)
        assertEquals("1.0", resetRow.version)
        assertEquals("Source description", resetRow.description)
    }

    @Test fun favoriteMutationChangesOnlyFavoriteState() = runBlocking {
        val id = dao.insertApp(
            app(
                storageKey = "game",
                customTitle = "Custom",
                addedAt = 11L,
                lastPlayedAt = 22L,
                playCount = 5L,
                totalPlayTimeMs = 6_000L,
            ),
        )

        assertEquals(1, dao.updateFavorite(id, true))
        val favorite = requireNotNull(dao.getApp(id))
        assertTrue(favorite.favorite)
        assertEquals("Custom", favorite.customTitle)
        assertEquals(11L, favorite.addedAt)
        assertEquals(22L, favorite.lastPlayedAt)
        assertEquals(5L, favorite.playCount)
        assertEquals(6_000L, favorite.totalPlayTimeMs)

        assertEquals(1, dao.updateFavorite(id, false))
        assertFalse(requireNotNull(dao.getApp(id)).favorite)
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