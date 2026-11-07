/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.librarydb

import org.junit.jupiter.api.io.TempDir
import java.io.File
import io.github.h3nb.jlmodplus.testutil.TempFiles
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LibraryAppBundleExporterTest {
    @field:TempDir lateinit var temporaryFolder: File

    @Test fun exportStreamsRetainedPackageConfigAndSaveTree() {
        val root = TempFiles.newFolder(temporaryFolder, "workdir")
        val converted = File(File(root, "converted"), "game").apply { mkdirs() }
        val configs = File(File(root, "configs"), "game").apply { mkdirs() }
        val data = File(File(root, "data"), "game").apply { mkdirs() }
        File(converted, "res.jar").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(converted, "converted.dex.conf").writeText("MIDlet-Name: Game\n")
        File(converted, "icon.png").writeBytes(byteArrayOf(9, 8, 7))
        File(configs, "config.json").writeText("{\"screenWidth\":240}")
        File(configs, "VirtualKeyboardLayout").writeText("layout")
        File(data, "nested").mkdirs()
        File(data, "nested/save.bin").writeBytes(ByteArray(128 * 1024 + 7) { (it % 253).toByte() })
        val target = File(temporaryFolder, "bundle.zip")
        val progress = ArrayList<LibraryAppBundleExporter.Progress>()

        LibraryAppBundleExporter.exportToZip(root, "game", target, progress::add)

        assertTrue(target.isFile)
        ZipFile(target).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(
                listOf(
                    "bundle.json",
                    "app/converted.dex.conf",
                    "app/icon.png",
                    "app/res.jar",
                    "config/VirtualKeyboardLayout",
                    "config/config.json",
                    "data/nested/save.bin",
                ),
                names,
            )
            assertEquals(zip.getInputStream(zip.getEntry("bundle.json")).reader().readText(), , "{\"formatVersion\":1}\n")
            assertEquals(zip.getInputStream(zip.getEntry("app/converted.dex.conf")).reader().readText(), , "MIDlet-Name: Game\n")
            assertEquals(zip.getInputStream(zip.getEntry("config/VirtualKeyboardLayout")).reader().readText(), , "layout")
            assertEquals(128 * 1024 + 7L, zip.getEntry("data/nested/save.bin").size)
        }
        assertEquals(7, progress.last().completedEntries)
        assertEquals(7, progress.last().totalEntries)
        assertEquals(progress.last().totalBytes, progress.last().writtenBytes)
        assertTrue(File(converted, "res.jar").isFile)
        assertTrue(File(data, "nested/save.bin").isFile)
    }

    @Test fun missingOptionalConfigAndSaveAreHandledGracefully() {
        val root = TempFiles.newFolder(temporaryFolder, "minimal")
        val converted = File(File(root, "converted"), "game").apply { mkdirs() }
        File(converted, "res.jar").writeBytes(byteArrayOf(1))
        val target = File(temporaryFolder, "minimal.zip")

        LibraryAppBundleExporter.exportToZip(root, "game", target)

        ZipFile(target).use { zip ->
            assertEquals(listOf("bundle.json", "app/res.jar"), zip.entries().asSequence().map { it.name }.toList())
        }
    }

    @Test fun exportFailsWithoutRetainedJarEvenWhenSideDataExists() {
        val root = TempFiles.newFolder(temporaryFolder, "missing-jar")
        val converted = File(File(root, "converted"), "game").apply { mkdirs() }
        val config = File(File(root, "configs"), "game").apply { mkdirs() }
        File(converted, "converted.dex.conf").writeText("MIDlet-Name: Game\n")
        File(config, "config.json").writeText("{}")
        val target = File(temporaryFolder, "missing-jar.zip")

        try {
            LibraryAppBundleExporter.exportToZip(root, "game", target)
            throw AssertionError("Expected export without retained JAR to fail")
        } catch (_: java.io.IOException) {
            assertFalse(target.exists() && target.length() > 0L)
        }
    }

    @Test fun exportFailsWhenNothingAppOwnedExists() {
        val root = TempFiles.newFolder(temporaryFolder, "empty")
        val target = File(temporaryFolder, "empty.zip")
        try {
            LibraryAppBundleExporter.exportToZip(root, "missing", target)
            throw AssertionError("Expected empty export to fail")
        } catch (_: java.io.IOException) {
            assertFalse(target.exists() && target.length() > 0L)
        }
    }

    @Test fun exportFileNameIsSafeAndBounded() {
        assertEquals(LibraryAppBundleExporter.safeFileName("My/Game"), , "My_Game-JLModPlus.zip")
        val longName = LibraryAppBundleExporter.safeFileName("A".repeat(200))
        assertTrue(longName.length <= 72 + "-JLModPlus.zip".length)
    }
}
