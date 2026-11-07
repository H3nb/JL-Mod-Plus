/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import org.junit.jupiter.api.io.TempDir
import io.github.h3nb.jlmodplus.testutil.TempFiles
import java.io.File
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryUniversalBundleStagerTest {
    @field:TempDir lateinit var temporaryFolder: File

    @Test
    fun cleanupStaleRemovesOnlyOldUuidRoots() {
        val importRoot = TempFiles.newFolder(temporaryFolder, "library-bulk-import")
        val stale = File(importRoot, UUID.randomUUID().toString()).apply {
            mkdir()
            setLastModified(1_000L)
        }
        val recent = File(importRoot, UUID.randomUUID().toString()).apply {
            mkdir()
            setLastModified(9_500L)
        }
        val unrelated = File(importRoot, "keep-me").apply { mkdir() }

        val removed = LibraryUniversalBundleStager.cleanupStale(
            importRoot = importRoot,
            nowMillis = 10_000L,
            maxAgeMillis = 5_000L,
        )

        assertEquals(1, removed)
        assertFalse(stale.exists())
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }
}
