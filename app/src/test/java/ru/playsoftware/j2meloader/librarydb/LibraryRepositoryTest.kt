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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertNotEquals(firstReady.generation, secondReady.generation)
        assertTrue(File(first, LibraryDatabase.FILE_NAME).isFile)
        assertTrue(File(second, LibraryDatabase.FILE_NAME).isFile)
    }

    @Test fun duplicateSetForSameWorkdirDoesNotCreateAnotherGeneration() = runBlocking {
        val root = temporaryFolder.newFolder("dedupe")
        createConvertedApp(root, "game", "Game")
        repository.setEmulatorDirectory(root)
        val initial = awaitReady(root)
        assertEquals(1, openCount.get())

        repository.setEmulatorDirectory(root.absoluteFile)
        repository.setEmulatorDirectory(root.canonicalFile)
        delay(200)

        val ready = repository.state.value as LibraryRepository.State.Ready
        assertEquals(1, openCount.get())
        assertEquals(initial.generation, ready.generation)
        assertEquals(root.canonicalFile, ready.emulatorDir)
        assertEquals(listOf("game"), ready.apps.map { it.storageKey })
    }

    @Test fun sameWorkdirSetAfterErrorStartsRecoveryGeneration() = runBlocking {
        val root = temporaryFolder.newFolder("error-set-retry")
        val invalidConverted = File(root, "converted").apply { writeText("not a directory") }

        repository.setEmulatorDirectory(root)
        val firstError = withTimeout(10_000) {
            repository.state.filterIsInstance<LibraryRepository.State.Error>().first()
        }
        assertEquals(1, openCount.get())

        assertTrue(invalidConverted.delete())
        createConvertedApp(root, "fixed", "Fixed Game")
        repository.setEmulatorDirectory(root)

        val ready = awaitReady(root)
        assertEquals(listOf("fixed"), ready.apps.map { it.storageKey })
        assertNotEquals(firstError.generation, ready.generation)
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
        assertNotEquals(error.generation, ready.generation)
        assertEquals(2, openCount.get())
    }

    @Test fun mutationPublishesThroughActiveRoomFlow() = runBlocking {
        val root = temporaryFolder.newFolder("mutation")
        createConvertedApp(root, "game", "Original")
        repository.setEmulatorDirectory(root)
        val ready = awaitReady(root)
        val token = ready.token()
        val id = ready.apps.single().id

        repository.setCustomTitle(token, id, "Renamed")

        val renamed = withTimeout(10_000) {
            repository.state.filterIsInstance<LibraryRepository.State.Ready>()
                .first { state -> state.apps.singleOrNull()?.title == "Renamed" }
        }
        assertEquals("Original", renamed.apps.single().sourceTitle)
        assertEquals("Renamed", renamed.apps.single().title)
    }

    @Test fun workdirRequestInvalidatesOldGenerationBeforeWorkerClosesDatabase() = runBlocking {
        val first = temporaryFolder.newFolder("sync-first")
        val second = temporaryFolder.newFolder("sync-second")
        createConvertedApp(first, "one", "First")
        createConvertedApp(second, "two", "Second")
        repository.setEmulatorDirectory(first)
        val firstReady = awaitReady(first)
        val staleToken = firstReady.token()
        val firstId = firstReady.apps.single().id

        repository.setEmulatorDirectory(second)

        // No await/yield here. The request itself must synchronously invalidate A even if the
        // collectLatest worker has not yet entered Opening(B) or closed DB A.
        assertNull(repository.currentReadyToken())
        try {
            repository.setCustomTitle(staleToken, firstId, "Must reject immediately")
            throw AssertionError("Expected immediate old-generation mutation rejection")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        awaitReady(second)
    }

    @Test fun staleWorkdirMutationIsRejectedAfterSwitch() = runBlocking {
        val first = temporaryFolder.newFolder("stale-first")
        val second = temporaryFolder.newFolder("stale-second")
        createConvertedApp(first, "one", "First")
        createConvertedApp(second, "two", "Second")
        repository.setEmulatorDirectory(first)
        val firstReady = awaitReady(first)
        val firstToken = firstReady.token()
        val firstId = firstReady.apps.single().id
        repository.setEmulatorDirectory(second)
        awaitReady(second)

        try {
            repository.setCustomTitle(firstToken, firstId, "Must not publish")
            throw AssertionError("Expected stale-workdir mutation rejection")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("two"), repository.state.value.let { state ->
                (state as LibraryRepository.State.Ready).apps.map { it.storageKey }
            })
        }
    }

    @Test fun staleGenerationForSamePathIsRejectedAfterAtoBtoA() = runBlocking {
        val first = temporaryFolder.newFolder("aba-first")
        val second = temporaryFolder.newFolder("aba-second")
        createConvertedApp(first, "one", "First")
        createConvertedApp(second, "two", "Second")

        repository.setEmulatorDirectory(first)
        val firstOpening = awaitReady(first)
        val staleToken = firstOpening.token()
        val staleId = firstOpening.apps.single().id

        repository.setEmulatorDirectory(second)
        awaitReady(second)
        repository.setEmulatorDirectory(first)
        val reopened = awaitReady(first) { it.generation != staleToken.generation }
        assertNotEquals(staleToken.generation, reopened.generation)

        try {
            repository.setCustomTitle(staleToken, staleId, "Must not cross generations")
            throw AssertionError("Expected stale same-path generation rejection")
        } catch (_: IllegalStateException) {
            val current = repository.state.value as LibraryRepository.State.Ready
            assertEquals("First", current.apps.single().title)
        }
    }

    @Test fun sourceIdentityLookupIsGenerationBound() = runBlocking {
        val first = temporaryFolder.newFolder("identity-first")
        val second = temporaryFolder.newFolder("identity-second")
        createConvertedApp(first, "one", "Same")
        createConvertedApp(second, "two", "Same")

        repository.setEmulatorDirectory(first)
        val stale = awaitReady(first).token()
        repository.setEmulatorDirectory(second)
        awaitReady(second)

        try {
            repository.findBySourceIdentity(stale, "Same", "Vendor")
            throw AssertionError("Expected stale source-identity lookup rejection")
        } catch (_: IllegalStateException) {
            // Expected: installer matching must not silently read a different generation snapshot.
        }
    }

    private suspend fun awaitReady(
        root: File,
        predicate: (LibraryRepository.State.Ready) -> Boolean = { true },
    ): LibraryRepository.State.Ready = withTimeout(10_000) {
        val canonical = root.canonicalFile
        repository.state
            .filterIsInstance<LibraryRepository.State.Ready>()
            .first { it.emulatorDir == canonical && predicate(it) }
    }

    private fun LibraryRepository.State.Ready.token() =
        LibraryGenerationToken(generation, emulatorDir)

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
