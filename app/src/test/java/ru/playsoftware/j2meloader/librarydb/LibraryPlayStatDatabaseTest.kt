/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryPlayStatDatabaseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao

    @Before fun setUp() {
        val file = temporaryFolder.newFile(LibraryDatabase.FILE_NAME)
        check(file.delete())
        database = Room.databaseBuilder<LibraryDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        dao = database.libraryDao()
    }

    @After fun tearDown() = database.close()

    @Test fun receiptAndStatsCommitExactlyOnce() = runBlocking {
        val appId = dao.insertApp(app(lastPlayedAt = 100L, playCount = 2L, totalPlayTimeMs = 500L))

        assertEquals(
            LibraryPlayStatReconcileResult.Applied,
            dao.reconcilePlayStat(
                sessionId = "session-1",
                storageKey = "game",
                reachedRunning = true,
                firstRunningWallTimeMillis = 200L,
                accumulatedActiveMillis = 1_000L,
                allowMissingTarget = false,
            ),
        )
        assertEquals(
            LibraryPlayStatReconcileResult.AlreadyReceipted,
            dao.reconcilePlayStat(
                sessionId = "session-1",
                storageKey = "game",
                reachedRunning = true,
                firstRunningWallTimeMillis = 300L,
                accumulatedActiveMillis = 9_000L,
                allowMissingTarget = false,
            ),
        )

        val updated = requireNotNull(dao.getApp(appId))
        assertEquals(200L, updated.lastPlayedAt)
        assertEquals(3L, updated.playCount)
        assertEquals(1_500L, updated.totalPlayTimeMs)
    }

    @Test fun olderFirstRunningTimeDoesNotMoveLastPlayedBackwards() = runBlocking {
        val appId = dao.insertApp(app(lastPlayedAt = 500L))

        assertEquals(
            LibraryPlayStatReconcileResult.Applied,
            dao.reconcilePlayStat(
                sessionId = "session-old",
                storageKey = "game",
                reachedRunning = true,
                firstRunningWallTimeMillis = 100L,
                accumulatedActiveMillis = 250L,
                allowMissingTarget = false,
            ),
        )

        val updated = requireNotNull(dao.getApp(appId))
        assertEquals(500L, updated.lastPlayedAt)
        assertEquals(1L, updated.playCount)
        assertEquals(250L, updated.totalPlayTimeMs)
    }

    @Test fun existingDirectoryWithoutCatalogRowRemainsRetryable() = runBlocking {
        assertEquals(
            LibraryPlayStatReconcileResult.TargetMissing,
            dao.reconcilePlayStat(
                sessionId = "session-pending",
                storageKey = "game",
                reachedRunning = true,
                firstRunningWallTimeMillis = 100L,
                accumulatedActiveMillis = 250L,
                allowMissingTarget = false,
            ),
        )

        val appId = dao.insertApp(app())
        assertEquals(
            LibraryPlayStatReconcileResult.Applied,
            dao.reconcilePlayStat(
                sessionId = "session-pending",
                storageKey = "game",
                reachedRunning = true,
                firstRunningWallTimeMillis = 100L,
                accumulatedActiveMillis = 250L,
                allowMissingTarget = false,
            ),
        )
        assertEquals(1L, requireNotNull(dao.getApp(appId)).playCount)
    }

    @Test fun confidentlyAbsentAppGetsReceiptWithoutInventingCatalogRow() = runBlocking {
        assertEquals(
            LibraryPlayStatReconcileResult.Applied,
            dao.reconcilePlayStat(
                sessionId = "session-gone",
                storageKey = "gone",
                reachedRunning = true,
                firstRunningWallTimeMillis = 100L,
                accumulatedActiveMillis = 250L,
                allowMissingTarget = true,
            ),
        )
        assertNull(dao.getAppByStorageKey("gone"))

        dao.insertApp(app(storageKey = "gone"))
        assertEquals(
            LibraryPlayStatReconcileResult.AlreadyReceipted,
            dao.reconcilePlayStat(
                sessionId = "session-gone",
                storageKey = "gone",
                reachedRunning = true,
                firstRunningWallTimeMillis = 100L,
                accumulatedActiveMillis = 250L,
                allowMissingTarget = false,
            ),
        )
        val recreated = requireNotNull(dao.getAppByStorageKey("gone"))
        assertEquals(0L, recreated.playCount)
        assertEquals(0L, recreated.totalPlayTimeMs)
    }

    @Test fun neverRunningSessionReceiptsWithoutChangingPlayStats() = runBlocking {
        val appId = dao.insertApp(app(lastPlayedAt = 500L, playCount = 3L, totalPlayTimeMs = 700L))

        assertEquals(
            LibraryPlayStatReconcileResult.Applied,
            dao.reconcilePlayStat(
                sessionId = "session-never-ran",
                storageKey = "game",
                reachedRunning = false,
                firstRunningWallTimeMillis = null,
                accumulatedActiveMillis = 0L,
                allowMissingTarget = false,
            ),
        )

        val unchanged = requireNotNull(dao.getApp(appId))
        assertEquals(500L, unchanged.lastPlayedAt)
        assertEquals(3L, unchanged.playCount)
        assertEquals(700L, unchanged.totalPlayTimeMs)
    }

    private fun app(
        storageKey: String = "game",
        lastPlayedAt: Long? = null,
        playCount: Long = 0L,
        totalPlayTimeMs: Long = 0L,
    ) = LibraryAppEntity(
        storageKey = storageKey,
        sourceTitle = "Game",
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
        lastPlayedAt = lastPlayedAt,
        playCount = playCount,
        totalPlayTimeMs = totalPlayTimeMs,
    )
}
