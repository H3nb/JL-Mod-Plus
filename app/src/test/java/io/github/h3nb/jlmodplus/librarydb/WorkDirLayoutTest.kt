package io.github.h3nb.jlmodplus.librarydb

import org.junit.jupiter.api.io.TempDir
import io.github.h3nb.jlmodplus.testutil.TempFiles
import java.io.File
import java.io.IOException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkDirLayoutTest {
    @field:TempDir lateinit var temporary: File

    @Test fun firstInstallCreatesPublishParent() {
        val root = TempFiles.newFolder(temporary)
        val staging = File(root, "staging").apply { mkdir() }
        File(staging, "payload").writeText("converted")
        val converted = WorkDirLayout.prepareConverted(root, false)
        assertTrue(staging.renameTo(File(converted, "Game")))
    }

    @Test fun missingExistingLibraryIsNotRecreated() {
        val root = TempFiles.newFolder(temporary)
        assertThrows(IOException::class.java) { WorkDirLayout.prepareConverted(root, true) }
        assertFalse(WorkDirLayout.converted(root).exists())
    }

    @Test fun fileCannotBecomeConvertedDirectory() {
        val root = TempFiles.newFolder(temporary)
        val obstruction = File(root, "converted").apply { writeText("keep") }
        assertThrows(IOException::class.java) { WorkDirLayout.prepareConverted(root, false) }
        assertEquals("keep", obstruction.readText())
    }
}
