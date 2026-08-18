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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression coverage for established Library data whose bootstrap marker is inconsistent. */
class LibraryBootstrapStateProtectionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun receiptOnlyDatabaseWithoutBootstrapStateIsNotTreatedAsFresh() = runBlocking {
        val workDir = temporaryFolder.newFolder("receipt-only")
        val database = openDatabase(workDir)
        try {
            val dao = database.libraryDao()
            assertTrue(dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-preserved")) >= 0)
            assertNull(dao.getLibraryState())

            try {
                LibraryBootstrapper().ensureReady(database, workDir)
                throw AssertionError("Expected established receipt state to reject fresh bootstrap")
            } catch (_: IllegalStateException) {
                // A missing singleton row must not make durable exactly-once receipts disposable.
            }

            assertNull(dao.getLibraryState())
            assertEquals(-1L, dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-preserved")))
        } finally {
            database.close()
        }
    }

    @Test
    fun indexingMarkerWithPersistentAppIsNotClearedAsRetryableBootstrap() = runBlocking {
        val workDir = temporaryFolder.newFolder("indexing-with-data")
        val database = openDatabase(workDir)
        try {
            val dao = database.libraryDao()
            dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.INDEXING))
            val appId = dao.insertApp(
                LibraryAppEntity(
                    storageKey = "preserved",
                    sourceTitle = "Preserved",
                    sourceVendor = "Vendor",
                    sourceVersion = "1.0",
                    customTitle = "User Rename",
                    favorite = true,
                ),
            )

            try {
                LibraryBootstrapper().ensureReady(database, workDir)
                throw AssertionError("Expected inconsistent INDEXING state to fail closed")
            } catch (_: IllegalStateException) {
                // The initial publish is atomic, so INDEXING plus durable rows is not a fresh retry.
            }

            val preserved = requireNotNull(dao.getApp(appId))
            assertEquals("User Rename", preserved.customTitle)
            assertTrue(preserved.favorite)
            assertEquals(LibraryBootstrapState.INDEXING, dao.getLibraryState()?.bootstrapState)
        } finally {
            database.close()
        }
    }

    private fun openDatabase(workDir: File): LibraryDatabase =
        Room.databaseBuilder<LibraryDatabase>(
            File(workDir, LibraryDatabase.FILE_NAME).absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .build()
}
