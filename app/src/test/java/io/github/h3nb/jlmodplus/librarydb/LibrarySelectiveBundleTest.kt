/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.librarydb

import java.io.File
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Selective export must never silently replace namespaces that were not selected. */
class LibrarySelectiveBundleTest {
    @TempDir lateinit var temp: File

    @Test fun savedOnlyIsPortableAndLeavesExistingConfigurationAndSaveAlone() {
        val root = File(temp, "source").apply { mkdirs() }
        val saved = """{"schema":2,"items":[]}"""
        sourceApp(root, "source", saved)
        val archive = File(temp, "saved-only.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", root, "source",
                LibraryBundleComponents(configuration = false, saveData = false, savedVariables = true),
            )),
            archive,
        )
        ZipFile(archive).use { zip ->
            assertEquals(listOf("bundle.json", "apps/a0001/app/res.jar",
                "apps/a0001/saved/saved-variables.json"),
                zip.entries().asSequence().map { it.name }.toList())
        }
        val parsed = LibraryAppBundleReader.read(archive.inputStream())
        assertEquals(LibraryAppBundleFormat.SELECTIVE_VERSION, parsed.formatVersion)
        assertEquals(BundleNamespaceState.Excluded, parsed.apps.single().configState)
        assertEquals(BundleNamespaceState.Excluded, parsed.apps.single().dataState)
        assertEquals(BundleNamespaceState.Present, parsed.apps.single().savedState)
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            archive.inputStream(), File(temp, "stage"), parsed.apps.single(), parsed.formatVersion)
        val target = File(temp, "target").apply { mkdirs() }
        val config = File(target, "configs/destination").apply { mkdirs() }
        File(config, "config.json").writeText("existing configuration")
        File(config, "saved-variables.json.bak").writeText("old Saved backup")
        val data = File(target, "data/destination").apply { mkdirs() }
        File(data, "old.sav").writeText("existing save")
        LibraryAppBundleImporter.restore(prepared, target, "destination")
        assertEquals("existing configuration", File(config, "config.json").readText())
        assertEquals("existing save", File(data, "old.sav").readText())
        assertEquals(saved, File(config, "saved-variables.json").readText())
        assertFalse(File(config, "saved-variables.json.bak").exists())
    }

    @Test fun configOnlyPreservesTargetSavedDefinitionsWhileReplacingConfiguration() {
        val root = File(temp, "source").apply { mkdirs() }
        sourceApp(root, "source", """{"schema":2,"items":[]}""")
        val archive = File(temp, "config-only.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", root, "source",
                LibraryBundleComponents(configuration = true, saveData = false, savedVariables = false),
            )), archive,
        )
        ZipFile(archive).use { zip ->
            assertTrue(zip.getEntry("apps/a0001/config/config.json") != null)
            assertFalse(zip.entries().asSequence().any { it.name.contains("saved-variables") })
        }
        val parsed = LibraryAppBundleReader.read(archive.inputStream())
        assertEquals(BundleNamespaceState.Present, parsed.apps.single().configState)
        assertEquals(BundleNamespaceState.Excluded, parsed.apps.single().savedState)
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            archive.inputStream(), File(temp, "stage"), parsed.apps.single(), parsed.formatVersion)
        val target = File(temp, "target").apply { mkdirs() }
        val config = File(target, "configs/destination").apply { mkdirs() }
        File(config, "old.cfg").writeText("old")
        File(config, "saved-variables.json").writeText("keep these definitions")
        File(config, "saved-variables.json.bak").writeText("recoverable backup")
        LibraryAppBundleImporter.restore(prepared, target, "destination")
        assertFalse(File(config, "old.cfg").exists())
        assertEquals("source configuration", File(config, "config.json").readText())
        assertEquals("keep these definitions", File(config, "saved-variables.json").readText())
        assertEquals("recoverable backup", File(config, "saved-variables.json.bak").readText())
    }

    private fun sourceApp(root: File, key: String, saved: String) {
        File(root, "converted/$key").apply { mkdirs() }
            .resolve("res.jar").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "configs/$key").apply { mkdirs() }.also { config ->
            File(config, "config.json").writeText("source configuration")
            File(config, "saved-variables.json").writeText(saved)
        }
        File(root, "data/$key").apply { mkdirs() }
            .resolve("save.sav").writeText("source save")
    }
}
