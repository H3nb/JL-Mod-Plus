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
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryBootstrapperTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = temporaryFolder.newFolder("workdir")
        val databaseFile = File(workDir, LibraryDatabase.FILE_NAME)
        database = Room.databaseBuilder<LibraryDatabase>(databaseFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        dao = database.libraryDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun freshEmptyLibraryBecomesReady() = runBlocking {
        val result = LibraryBootstrapper().ensureReady(database, workDir)

        assertFalse(result.alreadyReady)
        assertEquals(0, result.indexedCount)
        assertTrue(result.failures.isEmpty())
        assertEquals(LibraryBootstrapState.READY, dao.getLibraryState()?.bootstrapState)
        assertTrue(dao.getStorageKeys().isEmpty())
    }

    @Test
    fun existingConvertedAppsArePublishedTogetherWhenScanFinishes() = runBlocking {
        createConvertedApp("One", "Game One", "Vendor One", "1.0")
        createConvertedApp("Two", "Game Two", "Vendor Two", "2.0")
        val progress = mutableListOf<LibraryBootstrapper.Progress>()

        val result = LibraryBootstrapper().ensureReady(database, workDir, progress::add)

        assertEquals(2, result.indexedCount)
        assertTrue(result.failures.isEmpty())
        assertEquals(setOf("One", "Two"), dao.getStorageKeys().toSet())
        assertEquals(LibraryBootstrapState.READY, dao.getLibraryState()?.bootstrapState)
        assertEquals(2, progress.size)
        assertEquals(2, progress.last().completed)
        assertEquals(2, progress.last().total)
    }

    @Test
    fun interruptedBootstrapReplacesPartialCatalogBeforePublishingReady() = runBlocking {
        dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.INDEXING))
        dao.insertApp(
            LibraryAppEntity(
                storageKey = "stale",
                sourceTitle = "Stale",
                sourceVendor = "Vendor",
                sourceVersion = "1.0",
                customTitle = "Partial user state must not escape incomplete bootstrap",
                favorite = true,
            ),
        )
        createConvertedApp("real", "Real Game", "Vendor", "1.0")

        val result = LibraryBootstrapper().ensureReady(database, workDir)

        assertFalse(result.alreadyReady)
        assertEquals(listOf("real"), dao.getStorageKeys())
        assertEquals(LibraryBootstrapState.READY, dao.getLibraryState()?.bootstrapState)
    }

    @Test
    fun readyLibraryIsNeverRebuiltFromFilesystem() = runBlocking {
        val preservedId = dao.insertApp(
            LibraryAppEntity(
                storageKey = "preserved",
                sourceTitle = "Game",
                sourceVendor = "Vendor",
                sourceVersion = "1.0",
                customTitle = "User title",
                favorite = true,
            ),
        )
        dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY))
        // If the scanner ran this would fail: converted exists but is not a directory.
        File(workDir, "converted").writeText("storage unavailable")

        val result = LibraryBootstrapper().ensureReady(database, workDir)

        assertTrue(result.alreadyReady)
        val preserved = dao.getApp(preservedId)
        assertNotNull(preserved)
        requireNotNull(preserved)
        assertEquals("User title", preserved.customTitle)
        assertTrue(preserved.favorite)
    }

    @Test
    fun fatalFilesystemReadLeavesBootstrapIncompleteForRetry() = runBlocking {
        File(workDir, "converted").writeText("not a directory")

        try {
            LibraryBootstrapper().ensureReady(database, workDir)
            throw AssertionError("Expected bootstrap failure")
        } catch (_: IOException) {
            // Expected: storage/listing failure must not be reinterpreted as an empty Library.
        }

        assertEquals(LibraryBootstrapState.INDEXING, dao.getLibraryState()?.bootstrapState)
    }

    @Test
    fun malformedAppDoesNotBlockHealthyCatalogOrDeleteEvidence() = runBlocking {
        createConvertedApp("good", "Good Game", "Vendor", "1.0")
        val converted = File(workDir, "converted")
        val broken = File(converted, "broken").apply { mkdir() }
        File(broken, "converted.dex").writeText("payload")
        val descriptor = File(broken, "converted.dex.conf").apply {
            writeText("MIDlet-Name: Missing vendor and version\n")
        }

        val result = LibraryBootstrapper().ensureReady(database, workDir)

        assertEquals(listOf("good"), dao.getStorageKeys())
        assertEquals(listOf("broken"), result.failures.map { it.storageKey })
        assertEquals(LibraryBootstrapState.READY, dao.getLibraryState()?.bootstrapState)
        assertTrue(broken.isDirectory)
        assertTrue(descriptor.isFile)
    }

    private fun createConvertedApp(
        storageKey: String,
        title: String,
        vendor: String,
        version: String,
    ) {
        val converted = File(workDir, "converted").apply { mkdir() }
        val app = File(converted, storageKey).apply { mkdir() }
        File(app, "converted.dex").writeText("payload")
        File(app, "converted.dex.conf").writeText(
            "MIDlet-Name: $title\n" +
                "MIDlet-Vendor: $vendor\n" +
                "MIDlet-Version: $version\n",
        )
    }
}
