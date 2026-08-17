/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyLibraryImporterTest {
    private val importer = LegacyLibraryImporter()

    @Test fun renamedLegacyTitleBecomesCustomTitleOnly() {
        val source = app("game", "Original Title")
        val result = importer.merge(
            listOf(source),
            listOf(LegacyLibraryImporter.LegacyRow(7, "game", "My Rename")),
        )

        assertEquals(1, result.customTitlesImported)
        val merged = result.apps.single()
        assertEquals("Original Title", merged.sourceTitle)
        assertEquals("Vendor", merged.sourceVendor)
        assertEquals("1.0", merged.sourceVersion)
        assertEquals("My Rename", merged.customTitle)
        assertNull(merged.customVendor)
        assertNull(merged.customVersion)
        assertNull(merged.customDescription)
    }

    @Test fun unchangedLegacyTitleDoesNotCreateOverride() {
        val result = importer.merge(
            listOf(app("game", "Original Title")),
            listOf(LegacyLibraryImporter.LegacyRow(1, "game", "Original Title")),
        )

        assertEquals(0, result.customTitlesImported)
        assertNull(result.apps.single().customTitle)
    }

    @Test fun rowsMatchByStorageKeyAndUnmatchedRowsAreIgnored() {
        val result = importer.merge(
            listOf(app("actual", "Actual")),
            listOf(LegacyLibraryImporter.LegacyRow(1, "other", "Wrong app")),
        )

        assertEquals(0, result.customTitlesImported)
        assertEquals("Actual", result.apps.single().sourceTitle)
        assertNull(result.apps.single().customTitle)
    }

    @Test fun legacyRowOrderIsPreservedWherePractical() {
        val result = importer.merge(
            listOf(app("third", "Third"), app("first", "First"), app("second", "Second")),
            listOf(
                LegacyLibraryImporter.LegacyRow(30, "third", "Third"),
                LegacyLibraryImporter.LegacyRow(10, "first", "First"),
                LegacyLibraryImporter.LegacyRow(20, "second", "Second"),
            ),
        )

        assertEquals(listOf("first", "second", "third"), result.apps.map { it.storageKey })
    }

    private fun app(key: String, title: String) = LibraryAppEntity(
        storageKey = key,
        sourceTitle = title,
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
    )
}
