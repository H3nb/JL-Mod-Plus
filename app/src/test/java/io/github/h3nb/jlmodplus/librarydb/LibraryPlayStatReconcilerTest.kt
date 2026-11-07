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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LibraryPlayStatReconcilerTest {
    @field:TempDir lateinit var temporaryFolder: File
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao
    private val reconciler = LibraryPlayStatReconciler()

    @BeforeEach fun setUp() = runBlocking {
        val file = TempFiles.newFile(temporaryFolder, LibraryDatabase.FILE_NAME)
        assertTrue(file.delete())
        database = Room.databaseBuilder<LibraryDatabase>(file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()
        dao = database.libraryDao()
        dao.setLibraryState(LibraryStateEntity(bootstrapState = LibraryBootstrapState.READY))
    }

    @AfterEach fun tearDown() = database.close()

    @Test fun sameStorageKeyFromDifferentWorkdirIsIgnored() = runBlocking {
        val root = TempFiles.newFolder(temporaryFolder, "root-a")
        val other = TempFiles.newFolder(temporaryFolder, "root-b")
        val id = dao.insertApp(app("game"))

        val result = reconciler.reconcile(
            dao,
            root,
            listOf(record("session-a", other, "game", activeMillis = 900L)),
        )

        assertTrue(result.reconciledSessionIds.isEmpty())
        assertTrue(result.pendingSessionIds.isEmpty())
        val unchanged = requireNotNull(dao.getApp(id))
        assertEquals(0L, unchanged.playCount)
        assertEquals(0L, unchanged.totalPlayTimeMs)
    }

    @Test fun installedTargetMissingFromRoomIsIndexedThenAppliedExactlyOnce() = runBlocking {
        val root = TempFiles.newFolder(temporaryFolder, "root")
        createConvertedApp(root, "game")
        val session = record("session-a", root, "game", activeMillis = 750L)

        val first = reconciler.reconcile(dao, root, listOf(session))
        assertEquals(listOf("session-a"), first.reconciledSessionIds)
        assertTrue(first.pendingSessionIds.isEmpty())
        val indexed = dao.getAppByStorageKey("game")
        assertNotNull(indexed)
        assertEquals(1L, indexed!!.playCount)
        assertEquals(750L, indexed.totalPlayTimeMs)
        assertEquals(100L, indexed.lastPlayedAt)

        val second = reconciler.reconcile(dao, root, listOf(session))
        assertEquals(listOf("session-a"), second.reconciledSessionIds)
        val unchanged = requireNotNull(dao.getAppByStorageKey("game"))
        assertEquals(1L, unchanged.playCount)
        assertEquals(750L, unchanged.totalPlayTimeMs)
    }

    @Test fun malformedInstalledTargetRemainsPendingWithoutReceipt() = runBlocking {
        val root = TempFiles.newFolder(temporaryFolder, "root")
        val converted = File(root, "converted").apply { mkdir() }
        val appDir = File(converted, "broken").apply { mkdir() }
        File(appDir, "converted.dex").writeText("payload")
        File(appDir, "converted.dex.conf").writeText("MIDlet-Name: Broken\n")

        val result = reconciler.reconcile(
            dao,
            root,
            listOf(record("session-b", root, "broken", activeMillis = 10L)),
        )

        assertTrue(result.reconciledSessionIds.isEmpty())
        assertEquals(listOf("session-b"), result.pendingSessionIds)
        assertTrue(dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-b")) >= 0L)
    }

    @Test fun confidentlyAbsentTargetCanBeReceiptedWithoutCreatingApp() = runBlocking {
        val root = TempFiles.newFolder(temporaryFolder, "root")

        val result = reconciler.reconcile(
            dao,
            root,
            listOf(record("session-c", root, "gone", activeMillis = 200L)),
        )

        assertEquals(listOf("session-c"), result.reconciledSessionIds)
        assertTrue(result.pendingSessionIds.isEmpty())
        assertEquals(null, dao.getAppByStorageKey("gone"))
        assertEquals(-1L, dao.insertPlayStatReceipt(PlayStatReceiptEntity("session-c")))
    }

    private fun record(
        sessionId: String,
        root: File,
        storageKey: String,
        activeMillis: Long,
    ) = LibraryPlayStatRecord(
        sessionId = sessionId,
        workdirLocator = root.canonicalPath,
        storageKey = storageKey,
        reachedRunning = true,
        firstRunningWallTimeMillis = 100L,
        accumulatedActiveMillis = activeMillis,
    )

    private fun app(storageKey: String) = LibraryAppEntity(
        storageKey = storageKey,
        sourceTitle = "Game",
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
    )

    private fun createConvertedApp(root: File, storageKey: String) {
        val converted = File(root, "converted").apply { mkdir() }
        val appDir = File(converted, storageKey).apply { mkdir() }
        File(appDir, "converted.dex").writeText("payload")
        File(appDir, "converted.dex.conf").writeText(
            "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n",
        )
    }
}
