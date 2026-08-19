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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMetadataOverrideRegressionTest {
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
    fun savingSourceValueResetsOnlyThatMetadataOverride() {
        runBlocking {
            val root = temporaryFolder.newFolder("metadata-field-reset")
            createConvertedApp(root)
            repository.setEmulatorDirectory(root)
            val ready = awaitReady(root)
            val token = LibraryGenerationToken(ready.generation, ready.emulatorDir)
            val appId = ready.apps.single().id

            repository.setMetadataOverrides(
                expected = token,
                appId = appId,
                title = "Custom title",
                vendor = "Custom vendor",
                version = "Special",
                description = "Custom description",
            )
            val edited = awaitReady(root) { state ->
                state.apps.singleOrNull()?.vendor == "Custom vendor"
            }.apps.single()
            assertEquals("Source description", edited.sourceDescription)

            repository.setMetadataOverrides(
                expected = token,
                appId = appId,
                title = edited.title,
                vendor = edited.sourceVendor,
                version = edited.version,
                description = edited.description,
            )
            val selectivelyReset = awaitReady(root) { state ->
                state.apps.singleOrNull()?.let { row ->
                    row.vendor == row.sourceVendor && row.title == "Custom title"
                } == true
            }.apps.single()

            assertEquals("Vendor", selectivelyReset.vendor)
            assertEquals("Custom title", selectivelyReset.title)
            assertEquals("Special", selectivelyReset.version)
            assertEquals("Custom description", selectivelyReset.description)
            assertFalse(selectivelyReset.favorite)
            assertTrue(repository.isReadyGeneration(token))
        }
    }

    private suspend fun awaitReady(
        root: File,
        predicate: (LibraryRepository.State.Ready) -> Boolean = { true },
    ): LibraryRepository.State.Ready = withTimeout(10_000) {
        val canonical = root.canonicalFile
        repository.state
            .filterIsInstance<LibraryRepository.State.Ready>()
            .first { state -> state.emulatorDir == canonical && predicate(state) }
    }

    private fun createConvertedApp(root: File) {
        val app = File(File(root, "converted").apply { mkdir() }, "game").apply { mkdir() }
        File(app, "converted.dex").writeText("payload")
        File(app, "converted.dex.conf").writeText(
            "MIDlet-Name: Game\n" +
                "MIDlet-Vendor: Vendor\n" +
                "MIDlet-Version: 1.0\n" +
                "MIDlet-Description: Source description\n",
        )
    }
}
