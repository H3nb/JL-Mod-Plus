/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.librarydb

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibraryBundleSkinTransferTest {
    @TempDir lateinit var temp: File

    @Test fun selectedScreenAndControlSkinsImportWithoutOverwritingExistingGlobalAssets() {
        val id = UUID.randomUUID().toString()
        val source = File(temp, "source").apply { mkdirs() }
        createSource(source, id)
        val zip = File(temp, "selected-skins.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", source, "game",
                LibraryBundleComponents(
                    configuration = true, saveData = false, savedVariables = false,
                    layoutSkin = true, controlSkin = true,
                ),
            )), zip,
        )
        ZipFile(zip).use { input ->
            assertTrue(input.getEntry("apps/a0001/skin-layout/frame.png") != null)
            assertTrue(input.getEntry("apps/a0001/control-skin/skin.json") != null)
            assertTrue(input.getEntry("apps/a0001/control-skin/key_1.png") != null)
        }
        val parsed = LibraryAppBundleReader.read(zip.inputStream())
        assertEquals(BundleNamespaceState.Present, parsed.apps.single().layoutSkinState)
        assertEquals(BundleNamespaceState.Present, parsed.apps.single().controlSkinState)
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            zip.inputStream(), File(temp, "staging"), parsed.apps.single(), parsed.formatVersion)
        val target = File(temp, "target").apply { mkdirs() }
        installTarget(target)
        val existingImage = File(target, "skins/frame.png").apply {
            parentFile!!.mkdirs()
            writeBytes(png(88))
        }
        val existingSet = File(target, "skins/controls/Favorite").apply { mkdirs() }
        File(existingSet, "skin.json").writeText("""{"id":"$id","defaultMode":"icon"}""")
        val originalControl = File(existingSet, "key_1.png").apply { writeBytes(png(66)) }

        LibraryAppBundleImporter.restore(prepared, target, "game")

        val imported = Json.parseToJsonElement(
            File(target, "configs/game/config.json").readText(),
        ).jsonObject
        val selectedScreen = imported.getValue("ScreenBackgroundImage").jsonPrimitive.content
        val selectedControl = imported.getValue("ControlSkinSetId").jsonPrimitive.content
        assertNotEquals("frame.png", selectedScreen)
        assertTrue(File(target, "skins/$selectedScreen").isFile)
        assertNotEquals(id, selectedControl)
        val installedSet = LibraryBundleSkinAssets.controlSkin(target, selectedControl)!!
        assertEquals("Favorite (2)", installedSet.name)
        assertEquals(JsonNull, imported.getValue("SoundBank"))
        assertEquals(JsonNull, imported.getValue("Shader"))
        assertTrue(existingImage.readBytes().contentEquals(png(88)))
        assertTrue(originalControl.readBytes().contentEquals(png(66)))
    }

    @Test fun exportRejectsScreenSkinThatImporterWouldRefuse() {
        val id = UUID.randomUUID().toString()
        val source = File(temp, "oversized-source").apply { mkdirs() }
        createSource(source, id)
        RandomAccessFile(File(source, "skins/frame.png"), "rw").use {
            it.setLength(LibraryBundleSkinAssets.MAX_LAYOUT_SKIN_BYTES + 1)
        }
        val archive = File(temp, "oversized.zip")
        assertThrows(IOException::class.java) {
            LibraryUniversalBundleExporter.exportToZip(
                listOf(LibraryUniversalBundleExporter.AppSource(
                    "a0001", "Game", "Vendor", "1", source, "game",
                    LibraryBundleComponents(
                        configuration = false, saveData = false, savedVariables = false,
                        layoutSkin = true, controlSkin = false,
                    ),
                )), archive,
            )
        }
        assertFalse(archive.exists())
    }

    @Test fun repeatedImportReusesRemappedSkinIdAfterSourceIdCollision() {
        val id = UUID.randomUUID().toString()
        val source = File(temp, "skin-source").apply { mkdirs() }
        createSource(source, id)
        val sourceSet = File(source, "skins/controls/Favorite")
        val target = File(temp, "skin-destination").apply { mkdirs() }
        val colliding = File(target, "skins/controls/Favorite").apply { mkdirs() }
        File(colliding, "skin.json").writeText("""{"id":"$id","defaultMode":"icon"}""")
        File(colliding, "key_1.png").writeBytes(png(67))
        val first = LibraryBundleSkinAssets.importControlSkin(target, sourceSet, "Favorite")
        val second = LibraryBundleSkinAssets.importControlSkin(target, sourceSet, "Favorite")
        assertNotEquals(id, first)
        assertEquals(first, second)
        assertEquals(
            listOf("Favorite", "Favorite (2)"),
            File(target, "skins/controls").listFiles()!!.map { it.name }.sorted(),
        )
        assertTrue(File(colliding, "key_1.png").readBytes().contentEquals(png(67)))
    }

    @Test fun configOnlyFallsBackForMissingSharedAssetsWithoutCopyingGlobalAssets() {
        val id = UUID.randomUUID().toString()
        val source = File(temp, "source").apply { mkdirs() }
        createSource(source, id)
        val zip = File(temp, "config-only.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", source, "game",
                LibraryBundleComponents(
                    configuration = true, saveData = false, savedVariables = false,
                    layoutSkin = false, controlSkin = false,
                ),
            )), zip,
        )
        ZipFile(zip).use { input ->
            assertFalse(input.entries().asSequence().any {
                it.name.contains("/skin-layout/") || it.name.contains("/control-skin/")
            })
        }
        val parsed = LibraryAppBundleReader.read(zip.inputStream())
        assertEquals(BundleNamespaceState.Excluded, parsed.apps.single().layoutSkinState)
        assertEquals(BundleNamespaceState.Excluded, parsed.apps.single().controlSkinState)
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            zip.inputStream(), File(temp, "staging"), parsed.apps.single(), parsed.formatVersion)
        val target = File(temp, "target").apply { mkdirs() }
        installTarget(target)
        LibraryAppBundleImporter.restore(prepared, target, "game")
        val imported = Json.parseToJsonElement(
            File(target, "configs/game/config.json").readText(),
        ).jsonObject
        assertEquals(JsonNull, imported.getValue("ScreenBackgroundImage"))
        assertEquals(JsonNull, imported.getValue("ControlSkinSetId"))
        assertEquals(JsonNull, imported.getValue("SoundBank"))
        assertEquals(JsonNull, imported.getValue("Shader"))
        assertFalse(File(target, "skins/controls").exists())
    }

    @Test fun skinOnlyImportAppliesSkinsWithoutResettingUnrelatedLocalConfiguration() {
        val id = UUID.randomUUID().toString()
        val source = File(temp, "source").apply { mkdirs() }
        createSource(source, id)
        val zip = File(temp, "skins-only.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", source, "game",
                LibraryBundleComponents(
                    configuration = false, saveData = false, savedVariables = false,
                    layoutSkin = true, controlSkin = true,
                ),
            )), zip,
        )
        val parsed = LibraryAppBundleReader.read(zip.inputStream())
        assertEquals(BundleNamespaceState.Excluded, parsed.apps.single().configState)
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            zip.inputStream(), File(temp, "stage-only"), parsed.apps.single(), parsed.formatVersion)
        val target = File(temp, "target-only").apply { mkdirs() }
        installTarget(target)
        val config = File(target, "configs/game").apply { mkdirs() }
        File(config, "config.json").writeText(
            """{"ScreenWidth":480,"SoundBank":"keep-me.sf2","ScreenBackgroundImage":"old.png"}""",
        )
        val data = File(target, "data/game").apply { mkdirs() }
        File(data, "old.sav").writeText("keep this save")
        LibraryAppBundleImporter.restore(prepared, target, "game")
        val restored = Json.parseToJsonElement(File(config, "config.json").readText()).jsonObject
        assertEquals("480", restored.getValue("ScreenWidth").jsonPrimitive.content)
        assertEquals("keep-me.sf2", restored.getValue("SoundBank").jsonPrimitive.content)
        assertEquals("keep this save", File(data, "old.sav").readText())
        val screen = restored.getValue("ScreenBackgroundImage").jsonPrimitive.content
        val control = restored.getValue("ControlSkinSetId").jsonPrimitive.content
        assertTrue(File(target, "skins/$screen").isFile)
        assertTrue(LibraryBundleSkinAssets.controlSkin(target, control)!!.isDirectory)
    }

    @Test fun existingSharedAssetsAreKeptWhenValid() {
        val id = UUID.randomUUID().toString()
        val workdir = File(temp, "available").apply { mkdirs() }
        val config = File(workdir, "configs/game").apply { mkdirs() }
        val json = """{"ScreenBackgroundImage":"frame.png","ControlSkinSetId":"$id","SoundBank":"music.sf2","Shader":{"name":"CRT Filter","author":"Example","fragment":"crt.fsh","vertex":"crt.vsh"}}"""
        File(config, "config.json").writeText(json)
        File(workdir, "skins").apply { mkdirs() }.resolve("frame.png").writeBytes(png(21))
        File(workdir, "soundbanks").apply { mkdirs() }.resolve("music.sf2").writeText("asset")
        File(workdir, "shaders").apply { mkdirs() }.resolve("crt.ini").writeText(
            "[CRT preset]\nName = CRT Filter\nAuthor = Example\nFragment = crt.fsh\nVertex = crt.vsh\n",
        )
        File(workdir, "skins/controls/Shared").apply { mkdirs() }.also {
            File(it, "skin.json").writeText("""{"id":"$id"}""")
        }
        LibraryBundleSkinAssets.normalizeConfig(config, workdir, null, null)
        assertEquals(
            Json.parseToJsonElement(json),
            Json.parseToJsonElement(File(config, "config.json").readText()),
        )
    }

    private fun createSource(root: File, id: String) {
        File(root, "converted/game").apply { mkdirs() }.also {
            File(it, "res.jar").writeBytes(byteArrayOf(1, 2, 3))
            File(it, "converted.dex.conf").writeText(
                "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1\n",
            )
        }
        File(root, "configs/game").apply { mkdirs() }.also {
            File(it, "config.json").writeText(
                """{"Version":9,"ScreenBackgroundImage":"frame.png","ControlSkinSetId":"$id","SoundBank":"absent.sf2","Shader":{"name":"missing","fragment":"a","vertex":"b"}}""",
            )
        }
        File(root, "skins").apply { mkdirs() }.also {
            File(it, "frame.png").writeBytes(png(15))
        }
        File(root, "skins/controls/Favorite").apply { mkdirs() }.also {
            File(it, "skin.json").writeText("""{"id":"$id","defaultMode":"icon"}""")
            File(it, "key_1.png").writeBytes(png(16))
        }
    }

    private fun installTarget(root: File) {
        File(root, "converted/game").apply { mkdirs() }.also {
            File(it, "res.jar").writeBytes(byteArrayOf(1, 2, 3))
            File(it, "converted.dex.conf").writeText(
                "MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1\n",
            )
        }
    }

    private fun png(tail: Int) = byteArrayOf(
        -119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, tail.toByte(),
    )
}
