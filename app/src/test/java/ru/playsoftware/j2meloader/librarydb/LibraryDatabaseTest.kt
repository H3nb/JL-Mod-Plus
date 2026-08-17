/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun setUp() {
        val file = temporaryFolder.newFile(LibraryDatabase.FILE_NAME)
        // Room must create the schema itself; the target path only needs to be free.
        assertTrue(file.delete())
        database = Room.databaseBuilder<LibraryDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        dao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun duplicateSourceIdentityIsAllowedButStorageKeyIsDistinct() = runBlocking {
        dao.insertApp(app(storageKey = "game-a"))
        dao.insertApp(app(storageKey = "game-b"))

        val matches = dao.findAppsBySourceIdentity("Game", "Vendor")

        assertEquals(2, matches.size)
        assertEquals(setOf("game-a", "game-b"), matches.map { it.storageKey }.toSet())
    }

    @Test
    fun targetedSourceUpdatePreservesLibraryOwnedState() = runBlocking {
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

        assertEquals(
            1,
            dao.updateSourceMetadata(
                appId = id,
                sourceTitle = "Game Updated",
                sourceVendor = "Vendor",
                sourceVersion = "2.0",
                sourceDescription = "Updated source description",
                iconRevision = 2,
            ),
        )

        val updated = dao.getApp(id)
        assertNotNull(updated)
        requireNotNull(updated)
        assertEquals("Game Updated", updated.sourceTitle)
        assertEquals("2.0", updated.sourceVersion)
        assertEquals("My Game", updated.customTitle)
        assertTrue(updated.favorite)
        assertEquals(123L, updated.addedAt)
        assertEquals(456L, updated.lastPlayedAt)
        assertEquals(7L, updated.playCount)
        assertEquals(8_000L, updated.totalPlayTimeMs)
    }

    @Test
    fun deletingAppCascadesMembershipButDoesNotDeletePlayReceipt() = runBlocking {
        val appId = dao.insertApp(app(storageKey = "game-a"))
        val collectionId = dao.insertCollection(
            LibraryCollectionEntity(
                name = "Favorites from school",
                createdAt = 1L,
            ),
        )
        assertTrue(
            dao.insertCollectionMembership(
                LibraryCollectionAppEntity(
                    collectionId = collectionId,
                    appId = appId,
                    addedAt = 2L,
                ),
            ) >= 0,
        )
        assertTrue(dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-1")) >= 0)

        assertEquals(1, dao.deleteAppByStorageKey("game-a"))
        assertNull(dao.getApp(appId))

        // A stale terminal journal with this session ID must stay deduplicated even after app delete.
        assertEquals(-1L, dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-1")))
    }

    @Test
    fun listProjectionUsesEffectiveMetadataWithoutFilesystemState() = runBlocking {
        dao.insertApp(
            app(
                storageKey = "game-a",
                customTitle = "Custom title",
                favorite = true,
                addedAt = 100L,
            ),
        )

        val rows = dao.observeApps().first()

        assertEquals(1, rows.size)
        assertEquals("Custom title", rows.single().title)
        assertEquals("Vendor", rows.single().vendor)
        assertTrue(rows.single().favorite)
        assertEquals(100L, rows.single().addedAt)
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
