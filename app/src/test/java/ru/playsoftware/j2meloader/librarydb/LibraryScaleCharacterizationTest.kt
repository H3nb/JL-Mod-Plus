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
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Non-flaky scale characterization for the Library v2 target sizes.
 *
 * These tests intentionally do not assert wall-clock thresholds because shared CI runners are not
 * benchmark hardware. They keep the 100/1k/5k paths executable in normal CI and print elapsed time
 * so regressions can be compared between runs without turning transient runner load into failures.
 */
class LibraryScaleCharacterizationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun listProjectionAtTargetScales() {
        for (count in TARGET_SIZES) {
            val rows = (0 until count).map { index ->
                LibraryAppRow(
                    id = index.toLong() + 1,
                    storageKey = "app-$index",
                    sourceTitle = "Game ${count - index}",
                    sourceVendor = "Vendor ${index % 37}",
                    sourceVersion = "1.0",
                    title = "Game ${count - index}",
                    vendor = "Vendor ${index % 37}",
                    version = "1.0",
                    description = "Description $index",
                    favorite = false,
                    addedAt = null,
                    lastPlayedAt = null,
                    iconRevision = 0,
                )
            }
            val start = System.nanoTime()
            val projected = LibraryListProjection.project(rows, "game", 0, Locale.US)
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            println("Library list projection count=$count elapsedMs=${"%.3f".format(Locale.US, elapsedMs)}")
            assertEquals(count, projected.size)
        }
    }

    @Test fun roomBatchPublishAtOneAndFiveThousandRows() = runBlocking {
        for (count in intArrayOf(1_000, 5_000)) {
            val root = temporaryFolder.newFolder("db-$count")
            val database = Room.databaseBuilder<LibraryDatabase>(
                File(root, LibraryDatabase.FILE_NAME).absolutePath,
            )
                .setDriver(BundledSQLiteDriver())
                .build()
            try {
                val apps = (0 until count).map { index ->
                    LibraryAppEntity(
                        storageKey = "app-$index",
                        sourceTitle = "Game $index",
                        sourceVendor = "Vendor ${index % 37}",
                        sourceVersion = "1.0",
                    )
                }
                val start = System.nanoTime()
                database.libraryDao().replaceIncompleteCatalog(apps)
                val firstProjection = database.libraryDao().observeApps().first()
                val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
                println("Library Room publish count=$count elapsedMs=${"%.3f".format(Locale.US, elapsedMs)}")
                assertEquals(count, firstProjection.size)
                assertEquals(LibraryBootstrapState.READY, database.libraryDao().getLibraryState()?.bootstrapState)
            } finally {
                database.close()
            }
        }
    }

    private companion object {
        val TARGET_SIZES = intArrayOf(100, 1_000, 5_000)
    }
}
