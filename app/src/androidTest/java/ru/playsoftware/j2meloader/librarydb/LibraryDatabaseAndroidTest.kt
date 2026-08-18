/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device/emulator smoke coverage for the production AndroidSQLiteDriver + absolute-path DB path. */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseAndroidTest {
    @Test
    fun absolutePathAndroidSqliteDriverRoundTripsLibraryData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "library-driver-${System.nanoTime()}")
        assertTrue(root.mkdirs())
        var database: LibraryDatabase? = null
        try {
            database = LibraryDatabase.open(context, root)
            val dao = database.libraryDao()
            dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY))
            val id = dao.insertApp(
                LibraryAppEntity(
                    storageKey = "driver-smoke",
                    sourceTitle = "Driver Smoke",
                    sourceVendor = "JL-Mod Plus",
                    sourceVersion = "1.0",
                ),
            )

            assertEquals("Driver Smoke", dao.getApp(id)?.sourceTitle)
            assertEquals(LibraryBootstrapState.READY, dao.getLibraryState()?.bootstrapState)
            assertTrue(File(root, LibraryDatabase.FILE_NAME).isFile)
        } finally {
            database?.close()
            root.deleteRecursively()
        }
    }
}
