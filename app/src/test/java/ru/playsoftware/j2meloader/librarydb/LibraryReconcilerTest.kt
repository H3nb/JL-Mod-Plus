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

    private fun createConvertedApp(key: String, title: String) {
        val dir = File(File(workDir, "converted").apply { mkdir() }, key).apply { mkdir() }
        File(dir, "converted.dex").writeText("payload")
        File(dir, "converted.dex.conf").writeText(
            "MIDlet-Name: $title\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n",
        )
    }
}
