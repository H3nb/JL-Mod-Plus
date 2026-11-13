/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.librarydb

import io.github.h3nb.jlmodplus.config.Config
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibraryIconFilesTest {
    @field:TempDir lateinit var root: File

    @Test fun iconPresenceRefreshesOnRowChangeAndIsIsolatedByWorkdir() {
        val first = File(root, "first").apply { mkdirs() }
        val second = File(root, "second").apply { mkdirs() }
        val row = app(1)
        val icon = File(File(first, "converted/game").absolutePath + Config.MIDLET_ICON_FILE)
        icon.parentFile!!.mkdirs()
        icon.writeText("icon")

        val initial = resolveLibraryIconFiles(first, listOf(row))
        assertTrue(initial.getValue(1).mainIconPresent)
        assertFalse(initial.getValue(1).portraitIconPresent)
        assertFalse(initial.getValue(1).customIconPresent)

        icon.delete()
        val unchanged = resolveLibraryIconFiles(first, listOf(row), initial)
        assertSame(initial.getValue(1), unchanged.getValue(1))

        val portrait = LibraryIconOverride.portraitOverrideFile(first, "game")
        portrait.parentFile!!.mkdirs()
        portrait.writeText("portrait")
        val custom = LibraryIconOverride.persistedOverrideFile(first, "game")
        custom.parentFile!!.mkdirs()
        custom.writeText("custom")
        val changed = resolveLibraryIconFiles(first, listOf(row.copy(iconRevision = 1)), unchanged)
        assertFalse(changed.getValue(1).mainIconPresent)
        assertTrue(changed.getValue(1).portraitIconPresent)
        assertTrue(changed.getValue(1).customIconPresent)

        val otherWorkdir = resolveLibraryIconFiles(second, listOf(row))
        assertFalse(otherWorkdir.getValue(1).mainIconPresent)
        assertFalse(otherWorkdir.getValue(1).portraitIconPresent)
        assertFalse(otherWorkdir.getValue(1).customIconPresent)
    }

    @Test fun removedRowsAreNotRetainedAndCancellationCannotPublishPartialResults() {
        val first = resolveLibraryIconFiles(root, listOf(app(1), app(2)))
        val next = resolveLibraryIconFiles(root, listOf(app(2)), first)
        assertEquals(setOf(2L), next.keys)
        assertSame(first.getValue(2), next.getValue(2))
        assertThrows(IllegalStateException::class.java) {
            resolveLibraryIconFiles(root, (1L..150L).map(::app), next) {
                throw IllegalStateException("cancelled")
            }
        }
        assertEquals(setOf(2L), next.keys)
    }

    private fun app(id: Long) = LibraryAppRow(
        id = id,
        storageKey = "game",
        sourceTitle = "Game",
        sourceVendor = "Vendor",
        sourceVersion = "1.0",
        title = "Game",
        vendor = "Vendor",
        version = "1.0",
        description = "",
        favorite = false,
        addedAt = null,
        lastPlayedAt = null,
        iconRevision = 0,
    )
}
