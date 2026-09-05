package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkDirLayoutTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun firstInstallCreatesPublishParent() {
        val root = temporary.newFolder()
        val staging = File(root, "staging").apply { mkdir() }
        File(staging, "payload").writeText("converted")
        val converted = WorkDirLayout.prepareConverted(root, false)
        assertTrue(staging.renameTo(File(converted, "Game")))
    }

    @Test fun missingExistingLibraryIsNotRecreated() {
        val root = temporary.newFolder()
        assertThrows(IOException::class.java) { WorkDirLayout.prepareConverted(root, true) }
        assertFalse(WorkDirLayout.converted(root).exists())
    }

    @Test fun fileCannotBecomeConvertedDirectory() {
        val root = temporary.newFolder()
        val obstruction = File(root, "converted").apply { writeText("keep") }
        assertThrows(IOException::class.java) { WorkDirLayout.prepareConverted(root, false) }
        assertEquals("keep", obstruction.readText())
    }
}
