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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryCollectionRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var repository: LibraryRepository

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repository = LibraryRepository(
            scope = scope,
            databaseFactory = { root ->
                Room.databaseBuilder<LibraryDatabase>(
                    File(root, LibraryDatabase.FILE_NAME).absolutePath,
                ).setDriver(BundledSQLiteDriver()).build()
            },
        )
    }

    @After
    fun tearDown() {
        repository.close()
        scope.cancel()
    }

    @Test
    fun collectionCrudAndMembershipPublishThroughReadyState() = runBlocking {
        val root = temporaryFolder.newFolder("collections")
        createConvertedApp(root, "game", "Game")
        repository.setEmulatorDirectory(root)
        val ready = awaitReady(root)
        val token = ready.token()
        val appId = ready.apps.single().id

        val collectionId = repository.createCollection(token, "RPG", createdAt = 100L)
        val created = awaitReady(root) { state ->
            state.collections.singleOrNull()?.id == collectionId
        }
        assertEquals("RPG", created.collections.single().name)
        assertEquals(0, created.collections.single().appCount)

        repository.setCollectionMembership(
            expected = token,
            collectionId = collectionId,
            appId = appId,
            included = true,
            addedAt = 200L,
        )
        val withMembership = awaitReady(root) { state ->
            state.collections.singleOrNull()?.appCount == 1
        }
        assertEquals(1, withMembership.collections.single().appCount)
        assertEquals(setOf(appId), repository.collectionAppIds(token, collectionId))

        repository.renameCollection(token, collectionId, "Role Playing")
        val renamed = awaitReady(root) { state ->
            state.collections.singleOrNull()?.name == "Role Playing"
        }
        assertEquals("Role Playing", renamed.collections.single().name)

        repository.setCollectionMembership(
            expected = token,
            collectionId = collectionId,
            appId = appId,
            included = false,
            addedAt = 300L,
        )
        awaitReady(root) { state -> state.collections.singleOrNull()?.appCount == 0 }
        assertTrue(repository.collectionAppIds(token, collectionId).isEmpty())

        repository.deleteCollection(token, collectionId)
        val deleted = awaitReady(root) { state -> state.collections.isEmpty() }
        assertTrue(deleted.collections.isEmpty())
    }

    @Test
    fun staleCollectionMutationIsRejectedAfterWorkdirRoundTrip() = runBlocking {
        val first = temporaryFolder.newFolder("first")
        val second = temporaryFolder.newFolder("second")
        createConvertedApp(first, "one", "One")
        createConvertedApp(second, "two", "Two")

        repository.setEmulatorDirectory(first)
        val stale = awaitReady(first)
        val staleToken = stale.token()
        val collectionId = repository.createCollection(staleToken, "Old root", createdAt = 1L)
        awaitReady(first) { it.collections.singleOrNull()?.id == collectionId }

        repository.setEmulatorDirectory(second)
        awaitReady(second)
        repository.setEmulatorDirectory(first)
        val reopened = awaitReady(first) { it.generation != staleToken.generation }
        assertNotEquals(staleToken.generation, reopened.generation)

        try {
            repository.renameCollection(staleToken, collectionId, "Must reject")
            throw AssertionError("Expected stale collection mutation rejection")
        } catch (_: IllegalStateException) {
            assertEquals("Old root", reopened.collections.single().name)
        }
    }

    private suspend fun awaitReady(
        root: File,
        predicate: (LibraryRepository.State.Ready) -> Boolean = { true },
    ): LibraryRepository.State.Ready = withTimeout(10_000) {
        val canonical = root.canonicalFile
        repository.state.filterIsInstance<LibraryRepository.State.Ready>()
            .first { it.emulatorDir == canonical && predicate(it) }
    }

    private fun LibraryRepository.State.Ready.token() =
        LibraryGenerationToken(generation, emulatorDir)

    private fun createConvertedApp(root: File, key: String, title: String) {
        val app = File(File(root, "converted").apply { mkdir() }, key).apply { mkdir() }
        File(app, "converted.dex").writeText("payload")
        File(app, "converted.dex.conf").writeText(
            "MIDlet-Name: $title\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n",
        )
    }
}
