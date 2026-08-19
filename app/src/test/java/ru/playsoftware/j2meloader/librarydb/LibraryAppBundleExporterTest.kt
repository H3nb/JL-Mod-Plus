/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryAppBundleExporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun exportStreamsRetainedPackageConfigAndSaveTree() {
        val root = temporaryFolder.newFolder("workdir")
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
        val target = File(temporaryFolder.root, "bundle.zip")
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
            assertEquals(
                "{\"formatVersion\":1}\n",
                zip.getInputStream(zip.getEntry("bundle.json")).reader().readText(),
            )
            assertEquals(
                "MIDlet-Name: Game\n",
                zip.getInputStream(zip.getEntry("app/converted.dex.conf")).reader().readText(),
            )
            assertEquals(
                "layout",
                zip.getInputStream(zip.getEntry("config/VirtualKeyboardLayout")).reader().readText(),
            )
            assertEquals(128 * 1024 + 7L, zip.getEntry("data/nested/save.bin").size)
        }
        assertEquals(7, progress.last().completedEntries)
        assertEquals(7, progress.last().totalEntries)
        assertEquals(progress.last().totalBytes, progress.last().writtenBytes)
        assertTrue(File(converted, "res.jar").isFile)
        assertTrue(File(data, "nested/save.bin").isFile)
    }

    @Test fun missingOptionalConfigAndSaveAreHandledGracefully() {
        val root = temporaryFolder.newFolder("minimal")
        val converted = File(File(root, "converted"), "game").apply { mkdirs() }
        File(converted, "res.jar").writeBytes(byteArrayOf(1))
        val target = File(temporaryFolder.root, "minimal.zip")

        LibraryAppBundleExporter.exportToZip(root, "game", target)

        ZipFile(target).use { zip ->
            assertEquals(listOf("bundle.json", "app/res.jar"), zip.entries().asSequence().map { it.name }.toList())
        }
    }

    @Test fun exportFailsWhenNothingAppOwnedExists() {
        val root = temporaryFolder.newFolder("empty")
        val target = File(temporaryFolder.root, "empty.zip")
        try {
            LibraryAppBundleExporter.exportToZip(root, "missing", target)
            throw AssertionError("Expected empty export to fail")
        } catch (_: java.io.IOException) {
            assertFalse(target.exists() && target.length() > 0L)
        }
    }

    @Test fun exportFileNameIsSafeAndBounded() {
        assertEquals(
            "My_Game-JLModPlus.zip",
            LibraryAppBundleExporter.safeFileName("My/Game"),
        )
        val longName = LibraryAppBundleExporter.safeFileName("A".repeat(200))
        assertTrue(longName.length <= 72 + "-JLModPlus.zip".length)
    }
}
