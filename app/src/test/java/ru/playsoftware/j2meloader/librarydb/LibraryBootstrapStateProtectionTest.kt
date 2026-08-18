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

/** Regression coverage for established Library state whose singleton bootstrap row is missing. */
class LibraryBootstrapStateProtectionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun receiptOnlyDatabaseWithoutBootstrapStateIsNotTreatedAsFresh() = runBlocking {
        val workDir = temporaryFolder.newFolder("receipt-only")
        val database = Room.databaseBuilder<LibraryDatabase>(
            File(workDir, LibraryDatabase.FILE_NAME).absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            .build()
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
}
