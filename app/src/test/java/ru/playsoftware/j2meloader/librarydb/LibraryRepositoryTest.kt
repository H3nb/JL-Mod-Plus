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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var repositoryScope: CoroutineScope
    private lateinit var repository: LibraryRepository
    private lateinit var openCount: AtomicInteger

    @Before fun setUp() {
        repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        openCount = AtomicInteger()
        repository = LibraryRepository(
            scope = repositoryScope,
            databaseFactory = { root ->
                openCount.incrementAndGet()
                openDatabase(root)
            },
        )
    }

    @After fun tearDown() {
        repository.close()
        repositoryScope.cancel()
    }

    @Test fun switchingWorkdirPublishesRowsFromNewRootOnly() = runBlocking {
        val first = temporaryFolder.newFolder("first")
        val second = temporaryFolder.newFolder("second")
        createConvertedApp(first, "one", "First Game")
        createConvertedApp(second, "two", "Second Game")

        repository.setEmulatorDirectory(first)
        val firstReady = awaitReady(first)
        assertEquals(listOf("one"), firstReady.apps.map { it.storageKey })

        repository.setEmulatorDirectory(second)
        val secondReady = awaitReady(second)
        assertEquals(listOf("two"), secondReady.apps.map { it.storageKey })
        assertEquals(second.canonicalFile, secondReady.emulatorDir)
        assertTrue(File(first, LibraryDatabase.FILE_NAME).isFile)
        assertTrue(File(second, LibraryDatabase.FILE_NAME).isFile)
    }

    @Test fun duplicateSetForSameWorkdirDoesNotCreateAnotherGeneration() = runBlocking {
        val root = temporaryFolder.newFolder("dedupe")
        createConvertedApp(root, "game", "Game")
        repository.setEmulatorDirectory(root)
        awaitReady(root)
        assertEquals(1, openCount.get())

        repository.setEmulatorDirectory(root.absoluteFile)
        repository.setEmulatorDirectory(root.canonicalFile)
        delay(200)

        val ready = repository.state.value as LibraryRepository.State.Ready
        assertEquals(1, openCount.get())
        assertEquals(root.canonicalFile, ready.emulatorDir)
        assertEquals(listOf("game"), ready.apps.map { it.storageKey })
    }

    @Test fun sameWorkdirSetAfterErrorStartsRecoveryGeneration() = runBlocking {
        val root = temporaryFolder.newFolder("error-set-retry")
        val invalidConverted = File(root, "converted").apply { writeText("not a directory") }

        repository.setEmulatorDirectory(root)
        withTimeout(10_000) {
            repository.state.filterIsInstance<LibraryRepository.State.Error>().first()
        }
        assertEquals(1, openCount.get())

        assertTrue(invalidConverted.delete())
        createConvertedApp(root, "fixed", "Fixed Game")
        repository.setEmulatorDirectory(root)

        val ready = awaitReady(root)
        assertEquals(listOf("fixed"), ready.apps.map { it.storageKey })
        assertEquals(2, openCount.get())
    }

    @Test fun retryReopensSameWorkdirAfterRecoverableStorageFailure() = runBlocking {
        val root = temporaryFolder.newFolder("retry")
        val invalidConverted = File(root, "converted").apply { writeText("not a directory") }

        repository.setEmulatorDirectory(root)
        val error = withTimeout(10_000) {
            repository.state.filterIsInstance<LibraryRepository.State.Error>().first()
        }
        assertEquals(root.canonicalFile, error.emulatorDir)
        assertEquals(1, openCount.get())

        assertTrue(invalidConverted.delete())
        createConvertedApp(root, "fixed", "Fixed Game")
        repository.retry()

        val ready = awaitReady(root)
        assertEquals(listOf("fixed"), ready.apps.map { it.storageKey })
        assertEquals(2, openCount.get())
    }

    @Test fun mutationPublishesThroughActiveRoomFlow() = runBlocking {
        val root = temporaryFolder.newFolder("mutation")
        createConvertedApp(root, "game", "Original")
        repository.setEmulatorDirectory(root)
        val ready = awaitReady(root)
        val id = ready.apps.single().id

        repository.setCustomTitle(root, id, "Renamed")

        val renamed = withTimeout(10_000) {
            repository.state.filterIsInstance<LibraryRepository.State.Ready>()
                .first { state -> state.apps.singleOrNull()?.title == "Renamed" }
        }
        assertEquals("Original", renamed.apps.single().sourceTitle)
        assertEquals("Renamed", renamed.apps.single().title)
    }

    @Test fun staleWorkdirMutationIsRejectedAfterSwitch() = runBlocking {
        val first = temporaryFolder.newFolder("stale-first")
        val second = temporaryFolder.newFolder("stale-second")
        createConvertedApp(first, "one", "First")
        createConvertedApp(second, "two", "Second")
        repository.setEmulatorDirectory(first)
        val firstId = awaitReady(first).apps.single().id
        repository.setEmulatorDirectory(second)
        awaitReady(second)

        try {
            repository.setCustomTitle(first, firstId, "Must not publish")
            throw AssertionError("Expected stale-workdir mutation rejection")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("two"), repository.state.value.let { state ->
                (state as LibraryRepository.State.Ready).apps.map { it.storageKey }
            })
        }
    }

    private suspend fun awaitReady(root: File): LibraryRepository.State.Ready = withTimeout(10_000) {
        val canonical = root.canonicalFile
        repository.state
            .filterIsInstance<LibraryRepository.State.Ready>()
            .first { it.emulatorDir == canonical }
    }

    private fun openDatabase(root: File): LibraryDatabase =
        Room.databaseBuilder<LibraryDatabase>(File(root, LibraryDatabase.FILE_NAME).absolutePath)
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun createConvertedApp(root: File, key: String, title: String) {
        val app = File(File(root, "converted").apply { mkdir() }, key).apply { mkdir() }
        File(app, "converted.dex").writeText("payload")
        File(app, "converted.dex.conf").writeText(
            "MIDlet-Name: $title\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n",
        )
    }
}
