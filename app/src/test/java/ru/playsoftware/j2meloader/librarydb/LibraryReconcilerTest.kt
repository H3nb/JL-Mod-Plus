/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryReconcilerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao
    private lateinit var workDir: File

    @Before fun setUp() = runBlocking {
        workDir = temporaryFolder.newFolder("workdir")
        database = Room.databaseBuilder<LibraryDatabase>(File(workDir, LibraryDatabase.FILE_NAME).absolutePath)
            .setDriver(BundledSQLiteDriver()).build()
        dao = database.libraryDao()
        dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY))
    }

    @After fun tearDown() = database.close()

    @Test fun unchangedAppDoesNotReparseDescriptor() = runBlocking {
        dao.insertApp(app("existing", "Indexed title"))
        val existing = File(File(workDir, "converted").apply { mkdir() }, "existing").apply { mkdir() }
        File(existing, "converted.dex").writeText("payload")
        File(existing, "converted.dex.conf").writeText("MIDlet-Name: incomplete\n")

        val result = LibraryReconciler().reconcile(database, workDir)

        assertEquals(0, result.addedCount)
        assertEquals(0, result.removedCount)
        assertTrue(result.failures.isEmpty())
        assertEquals("Indexed title", dao.getAppByStorageKey("existing")?.sourceTitle)
    }

    @Test fun newAndMissingKeysAreReconciled() = runBlocking {
        dao.insertApp(app("missing", "Missing"))
        createConvertedApp("added", "Added Game")

        val result = LibraryReconciler().reconcile(database, workDir)

        assertEquals(1, result.addedCount)
        assertEquals(1, result.removedCount)
        assertEquals("Added Game", dao.getAppByStorageKey("added")?.sourceTitle)
        assertNull(dao.getAppByStorageKey("missing"))
    }

    @Test fun publishedReinstallWithPendingBackupRefreshesSameKeyMetadataAndPreservesUserState() = runBlocking {
        val appId = dao.insertApp(
            LibraryAppEntity(
                storageKey = "game",
                sourceTitle = "Old Source",
                sourceVendor = "Vendor",
                sourceVersion = "1.0",
                customTitle = "My Rename",
                favorite = true,
                addedAt = 123L,
                lastPlayedAt = 456L,
                playCount = 7,
                totalPlayTimeMs = 8_000L,
            ),
        )
        createConvertedApp("game", "New Source", version = "2.0")
        createBackupDirectory("game", "Old Source", version = "1.0")

        val result = LibraryReconciler().reconcile(database, workDir)

        assertEquals(0, result.addedCount)
        assertEquals(0, result.removedCount)
        assertTrue(result.failures.isEmpty())
        val recovered = requireNotNull(dao.getApp(appId))
        assertEquals("New Source", recovered.sourceTitle)
        assertEquals("2.0", recovered.sourceVersion)
        assertEquals("My Rename", recovered.customTitle)
        assertTrue(recovered.favorite)
        assertEquals(123L, recovered.addedAt)
        assertEquals(456L, recovered.lastPlayedAt)
        assertEquals(7L, recovered.playCount)
        assertEquals(8_000L, recovered.totalPlayTimeMs)
        assertFalse(backupRoot().exists())
    }

    @Test fun prePublishReinstallCrashRestoresOldDirectoryInsteadOfDeletingCatalogState() = runBlocking {
        val appId = dao.insertApp(
            LibraryAppEntity(
                storageKey = "game",
                sourceTitle = "Old Source",
                sourceVendor = "Vendor",
                sourceVersion = "1.0",
                customTitle = "Keep me",
                favorite = true,
            ),
        )
        createBackupDirectory("game", "Old Source", version = "1.0")
        val staging = File(File(workDir, "converted"), LibraryInstallRecovery.STAGING_DIR_NAME)
            .apply { mkdirs() }
        File(staging, "partial").writeText("replacement")

        val result = LibraryReconciler().reconcile(database, workDir)

        assertEquals(0, result.addedCount)
        assertEquals(0, result.removedCount)
        assertTrue(result.failures.isEmpty())
        val restored = requireNotNull(dao.getApp(appId))
        assertEquals("Old Source", restored.sourceTitle)
        assertEquals("Keep me", restored.customTitle)
        assertTrue(restored.favorite)
        assertTrue(File(File(workDir, "converted"), "game").isDirectory)
        assertFalse(staging.exists())
        assertFalse(backupRoot().exists())
    }

    @Test fun failedRecoveryEvidenceProtectsCatalogRowFromAutomaticRemoval() = runBlocking {
        dao.insertApp(app("game", "Preserved"))
        val invalidBackup = File(backupRoot().apply { mkdirs() }, "game")
        invalidBackup.writeText("not a directory")

        val result = LibraryReconciler().reconcile(database, workDir)

        assertEquals(0, result.removedCount)
        assertEquals(listOf("game"), result.failures.map { it.storageKey })
        assertEquals("Preserved", dao.getAppByStorageKey("game")?.sourceTitle)
        assertTrue(invalidBackup.isFile)
    }

    @Test fun malformedNewAppIsRetainedForRetry() = runBlocking {
        val broken = File(File(workDir, "converted").apply { mkdir() }, "broken").apply { mkdir() }
        File(broken, "converted.dex").writeText("payload")
        File(broken, "converted.dex.conf").writeText("MIDlet-Name: incomplete\n")

        val result = LibraryReconciler().reconcile(database, workDir)

        assertEquals(listOf("broken"), result.failures.map { it.storageKey })
        assertNull(dao.getAppByStorageKey("broken"))
        assertTrue(broken.isDirectory)
    }

    @Test fun invalidConvertedRootDoesNotChangeCatalog() = runBlocking {
        dao.insertApp(app("preserved", "Preserved"))
        File(workDir, "converted").writeText("not a directory")
        try {
            LibraryReconciler().reconcile(database, workDir)
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            assertEquals("Preserved", dao.getAppByStorageKey("preserved")?.sourceTitle)
        }
    }

    @Test fun removalsAreChunkedBelowLegacySqliteBindLimit() = runBlocking {
        val count = 1_500
        dao.insertApps((0 until count).map { index -> app("missing-$index", "Game $index") })

        val result = LibraryReconciler().reconcile(database, workDir)

        assertEquals(count, result.removedCount)
        assertTrue(result.failures.isEmpty())
        assertTrue(dao.getStorageKeys().isEmpty())
    }

    @Test fun differenceHandlesFiveThousandRows() {
        val db = (0 until 5_000).mapTo(LinkedHashSet()) { "app-$it" }
        val fs = (2_500 until 7_500).mapTo(LinkedHashSet()) { "app-$it" }
        val diff = LibraryReconciler.difference(db, fs)
        assertEquals(2_500, diff.added.size)
        assertEquals(2_500, diff.removed.size)
    }

    private fun app(key: String, title: String) = LibraryAppEntity(
        storageKey = key,
        sourceTitle = title,
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
    )

    private fun createConvertedApp(key: String, title: String, version: String = "1.0") {
        val dir = File(File(workDir, "converted").apply { mkdir() }, key).apply { mkdir() }
        File(dir, "converted.dex").writeText("payload")
        File(dir, "converted.dex.conf").writeText(
            "MIDlet-Name: $title\nMIDlet-Vendor: Vendor\nMIDlet-Version: $version\n",
        )
    }

    private fun createBackupDirectory(key: String, title: String, version: String) {
        val dir = File(backupRoot().apply { mkdirs() }, key).apply { mkdir() }
        File(dir, "converted.dex").writeText("old payload")
        File(dir, "converted.dex.conf").writeText(
            "MIDlet-Name: $title\nMIDlet-Vendor: Vendor\nMIDlet-Version: $version\n",
        )
    }

    private fun backupRoot(): File = File(
        File(workDir, "converted"),
        LibraryInstallRecovery.BACKUP_ROOT_NAME,
    )
}
