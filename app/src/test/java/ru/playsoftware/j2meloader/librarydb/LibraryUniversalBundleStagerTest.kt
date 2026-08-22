/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryUniversalBundleStagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun cleanupStaleRemovesOnlyOldUuidRoots() {
        val importRoot = temporaryFolder.newFolder("library-bulk-import")
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
