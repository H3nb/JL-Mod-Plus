/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryShareManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun safeFileNameKeepsUsefulTitleButRemovesPathSyntax() {
        assertEquals("Prince of Persia.jar", LibraryShareManager.safeFileName("Prince of Persia"))
        assertEquals("Game.jar", LibraryShareManager.safeFileName("Game.jar"))
        assertEquals("evil__name.jar", LibraryShareManager.safeFileName("../evil/\\name"))
        assertEquals("J2ME-App.jar", LibraryShareManager.safeFileName("..///\\"))
    }

    @Test fun safeFileNameIsBounded() {
        val fileName = LibraryShareManager.safeFileName("A".repeat(200))
        assertEquals(84, fileName.length)
        assertTrue(fileName.endsWith(".jar"))
    }

    @Test fun copyFileStreamsExactBytesWithoutChangingSource() {
        val source = temporaryFolder.newFile("source.jar")
        val target = File(temporaryFolder.root, "target.jar")
        val bytes = ByteArray(256 * 1024 + 17) { index -> (index % 251).toByte() }
        source.writeBytes(bytes)
        val beforeModified = source.lastModified()

        LibraryShareManager.copyFile(source, target)

        assertTrue(source.isFile)
        assertEquals(bytes.size.toLong(), source.length())
        assertEquals(bytes.size.toLong(), target.length())
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(beforeModified, source.lastModified())
    }
}
