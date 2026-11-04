/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package io.github.h3nb.jlmodplus.librarydb

import java.io.File
import java.io.IOException
import io.github.h3nb.jlmodplus.memory.SavedVariablesDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertThrows
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
        val saved = """{"schema":2,"items":[{"id":"hp","label":"HP","locator":{"rootKind":1,"rootClass":"game.Main","segments":[{"owner":"game.Main","name":"hp","type":"int","index":-1}],"arrayIndex":-1}}]}"""
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
        val previous = """{"schema":2,"items":[{"id":"coins","label":"Coins","locator":{"rootKind":1,"rootClass":"game.Main","segments":[{"owner":"game.Main","name":"coins","type":"int","index":-1}],"arrayIndex":-1}}]}"""
        File(config, "saved-variables.json.bak").writeText(previous)
        val data = File(target, "data/destination").apply { mkdirs() }
        File(data, "old.sav").writeText("existing save")
        val imported = LibraryAppBundleImporter.restore(prepared, target, "destination")
        assertEquals(1, imported.savedAdded)
        assertEquals(0, imported.savedSkipped)
        assertEquals("existing configuration", File(config, "config.json").readText())
        assertEquals("existing save", File(data, "old.sav").readText())
        val savedBytes = File(config, "saved-variables.json").readBytes()
        SavedVariablesDocument.validate(savedBytes)
        val savedDoc = Json.parseToJsonElement(savedBytes.toString(Charsets.UTF_8)).jsonObject
        assertEquals("3", savedDoc["schema"]!!.jsonPrimitive.content)
        assertEquals(listOf("Coins", "HP"), savedDoc["items"]!!.jsonArray
            .map { it.jsonObject["label"]!!.jsonPrimitive.content })
        assertFalse(File(config, "saved-variables.json.bak").exists())
    }

    @Test fun configAndSavedRestoreReplacesExistingBookmarksWithoutTouchingSaveData() {
        val incoming = """{"schema":3,"items":[{"id":"imported","label":"Imported HP","locator":
            {"rootKind":1,"rootClass":"game.Main","steps":[
            {"kind":1,"owner":"game.Main","name":"hp","type":"int"}]}}]}"""
        val source = File(temp, "source-restore").apply { mkdirs() }
        sourceApp(source, "source", incoming)
        val archive = File(temp, "config-and-saved.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", source, "source",
                LibraryBundleComponents(configuration = true, saveData = false, savedVariables = true),
            )), archive,
        )
        val parsed = LibraryAppBundleReader.read(archive.inputStream())
        assertEquals(BundleNamespaceState.Present, parsed.apps.single().configState)
        assertEquals(BundleNamespaceState.Present, parsed.apps.single().savedState)
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            archive.inputStream(), File(temp, "staged-restore"), parsed.apps.single(), parsed.formatVersion)

        val target = File(temp, "installed-restore").apply { mkdirs() }
        File(target, "converted/destination").apply { mkdirs() }.also { installed ->
            File(installed, "res.jar").writeBytes(byteArrayOf(1, 2, 3))
            File(installed, "converted.dex.conf").writeText(
                "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1\n",
            )
        }
        val config = File(target, "configs/destination").apply { mkdirs() }
        File(config, "config.json").writeText("local configuration")
        File(config, "old.cfg").writeText("obsolete configuration")
        File(config, "saved-variables.json").writeText(
            """{"schema":2,"items":[{"id":"local","label":"Local Coins","locator":
            {"rootKind":1,"rootClass":"game.Main","segments":[
            {"owner":"game.Main","name":"coins","type":"int","index":-1}],"arrayIndex":-1}}]}""",
        )
        File(config, "saved-variables.json.bak").writeText("""{"schema":2,"items":[]}""")
        val data = File(target, "data/destination").apply { mkdirs() }
        File(data, "my-save.sav").writeText("local save")

        val outcome = LibraryAppBundleImporter.restore(prepared, target, "destination")
        assertEquals(null, outcome.savedAdded) // Backup restore is replace, not merge.
        assertEquals("source configuration", File(config, "config.json").readText())
        assertFalse(File(config, "old.cfg").exists())
        assertEquals(incoming, File(config, "saved-variables.json").readText())
        assertFalse(File(config, "saved-variables.json.bak").exists())
        assertEquals("local save", File(data, "my-save.sav").readText())
        SavedVariablesDocument.validate(File(config, "saved-variables.json").readBytes())
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
        // Configuration restore also reconciles the effective icon against the
        // already-installed retained JAR and descriptor.
        File(target, "converted/destination").apply { mkdirs() }.also { installed ->
            File(installed, "res.jar").writeBytes(byteArrayOf(1, 2, 3))
            File(installed, "converted.dex.conf").writeText(
                "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1\n",
            )
        }
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

    @Test fun malformedSavedDocumentCannotReplaceExistingDefinitions() {
        val old = """{"schema":2,"items":[]}"""
        val root = File(temp, "source").apply { mkdirs() }
        sourceApp(root, "source", old)
        val archive = File(temp, "valid.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", root, "source",
                LibraryBundleComponents(false, false, true),
            )), archive,
        )
        val parsed = LibraryAppBundleReader.read(archive.inputStream())
        val staged = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            archive.inputStream(), File(temp, "invalid-stage"),
            parsed.apps.single(), parsed.formatVersion,
        )
        // A ZIP with a valid manifest can still carry a structurally invalid Saved payload.
        File(requireNotNull(staged.savedVariablesFile).path).writeText(
            """{"schema":2,"items":[{"id":"same","label":"HP","locator":{}}]}""",
        )
        val target = File(temp, "target-invalid").apply { mkdirs() }
        val config = File(target, "configs/destination").apply { mkdirs() }
        File(config, "saved-variables.json").writeText(old)
        File(config, "config.json").writeText("local preferences")
        assertThrows(IOException::class.java) {
            LibraryAppBundleImporter.restore(staged, target, "destination")
        }
        assertEquals(old, File(config, "saved-variables.json").readText())
        assertEquals("local preferences", File(config, "config.json").readText())
        assertFalse(File(target, ".library-import-transactions").exists())
    }

    @Test fun exportUsesRecoverableAtomicBackupInsteadOfAnInterruptedMainFile() {
        val recovered = """{"schema":2,"items":[]}"""
        val root = File(temp, "backup-source").apply { mkdirs() }
        sourceApp(root, "source", recovered)
        val config = File(root, "configs/source")
        File(config, "saved-variables.json").writeText("interrupted write")
        File(config, "saved-variables.json.bak").writeText(recovered)
        val archive = File(temp, "backup.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", root, "source",
                LibraryBundleComponents(false, false, true),
            )), archive,
        )
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("apps/a0001/saved/saved-variables.json")
            assertEquals(recovered, zip.getInputStream(entry).bufferedReader().readText())
            assertFalse(zip.entries().asSequence().any { it.name.endsWith(".bak") })
        }
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
