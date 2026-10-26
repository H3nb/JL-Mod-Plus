/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryMetadataCommitTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun cancellationAfterPublicationDoesNotSplitFilesFromCommittedMetadata() = runBlocking {
        val root = temporaryFolder.newFolder("cancelled-save")
        val cache = File(root, "cache").apply { mkdirs() }
        val staged = File(cache, "draft.png").apply { writeBytes(byteArrayOf(8, 7, 6)) }
        val started = CompletableDeferred<Unit>()
        val continueCommit = CompletableDeferred<Unit>()
        var committed = false

        val save = launch(Dispatchers.Default) {
            commitLibraryMetadataEdit(
                publish = {
                    LibraryIconOverride.publishDrafts(
                        root, "game", cache, LibraryIconDraft(false, staged.path), null,
                    ).also { started.complete(Unit) }
                },
                persist = {
                    continueCommit.await()
                    committed = true
                },
            )
        }
        withTimeout(5_000) { started.await() }
        save.cancel()
        continueCommit.complete(Unit)
        withTimeout(5_000) { save.join() }

        assertTrue("Once publication starts, the commit must finish despite cancellation", committed)
        assertArrayEquals(
            byteArrayOf(8, 7, 6),
            LibraryIconOverride.persistedOverrideFile(root, "game").readBytes(),
        )
        assertArrayEquals(
            byteArrayOf(8, 7, 6),
            LibraryIconOverride.effectiveIconFile(root, "game").readBytes(),
        )
    }

    @Test
    fun failedMetadataPersistenceRestoresPublishedFiles() = runBlocking {
        val root = temporaryFolder.newFolder("failed-save")
        val cache = File(root, "cache").apply { mkdirs() }
        val original = byteArrayOf(1, 2, 3)
        val durable = LibraryIconOverride.persistedOverrideFile(root, "game")
        val effective = LibraryIconOverride.effectiveIconFile(root, "game")
        durable.parentFile!!.mkdirs()
        effective.parentFile!!.mkdirs()
        durable.writeBytes(original)
        effective.writeBytes(original)
        val staged = File(cache, "draft.png").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        var failed = false

        try {
            commitLibraryMetadataEdit(
                publish = {
                    LibraryIconOverride.publishDrafts(
                        root, "game", cache, LibraryIconDraft(false, staged.path), null,
                    )
                },
                persist = { error("injected Room failure") },
            )
        } catch (_: IllegalStateException) {
            failed = true
        }

        assertTrue("The persistence failure must propagate to the editor", failed)
        assertArrayEquals(original, durable.readBytes())
        assertArrayEquals(original, effective.readBytes())
    }
}
