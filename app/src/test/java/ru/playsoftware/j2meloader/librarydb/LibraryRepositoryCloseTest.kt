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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Recreation guard: an old Activity/ViewModel repository must lose authority synchronously. */
class LibraryRepositoryCloseTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun closeSynchronouslyInvalidatesReadyTokenAndMutations() = runBlocking {
        val root = temporaryFolder.newFolder("closed-generation")
        val appDir = File(File(root, "converted").apply { mkdir() }, "game").apply { mkdir() }
        File(appDir, "converted.dex").writeText("payload")
        File(appDir, "converted.dex.conf").writeText(
            "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n",
        )

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = LibraryRepository(
            scope = scope,
            databaseFactory = { workdir ->
                Room.databaseBuilder<LibraryDatabase>(
                    File(workdir, LibraryDatabase.FILE_NAME).absolutePath,
                )
                    .setDriver(BundledSQLiteDriver())
                    .build()
            },
        )
        try {
            repository.setEmulatorDirectory(root)
            val ready = withTimeout(10_000) {
                repository.state.filterIsInstance<LibraryRepository.State.Ready>().first()
            }
            val token = LibraryGenerationToken(ready.generation, ready.emulatorDir)
            val appId = ready.apps.single().id

            repository.close()

            assertNull(repository.currentReadyToken())
            assertFalse(repository.isReadyGeneration(token))
            try {
                repository.setCustomTitle(token, appId, "Must not publish")
                throw AssertionError("Expected closed repository mutation rejection")
            } catch (_: IllegalStateException) {
                // Expected: stale Rx work from the destroyed owner has lost authority immediately.
            }
        } finally {
            repository.close()
            scope.cancel()
        }
    }
}
