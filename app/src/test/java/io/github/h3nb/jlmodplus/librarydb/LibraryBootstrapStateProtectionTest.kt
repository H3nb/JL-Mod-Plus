/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import org.junit.jupiter.api.io.TempDir
import io.github.h3nb.jlmodplus.testutil.TempFiles
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Regression coverage for durable Library data whose singleton bootstrap row is missing. */
class LibraryBootstrapStateProtectionTest {
    @field:TempDir lateinit var temporaryFolder: File

    @Test
    fun receiptOnlyDatabaseWithoutBootstrapStateIsNotTreatedAsFresh() = runBlocking {
        val workDir = TempFiles.newFolder(temporaryFolder, "receipt-only")
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
