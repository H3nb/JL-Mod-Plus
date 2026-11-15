/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.librarydb

import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LibraryBundleConfigTest {
    @TempDir lateinit var temp: File

    @Test fun appBundleExcludesGlobalSkinFilesAndNormalizesMissingReferences() {
        val id = UUID.randomUUID().toString()
        val source = File(temp, "source").apply { mkdirs() }
        File(source, "converted/game").apply { mkdirs() }.resolve("res.jar")
            .writeBytes(byteArrayOf(1, 2, 3))
        File(source, "converted/game")
            .resolve("converted.dex.conf").writeText("MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1\n")
        File(source, "configs/game").apply { mkdirs() }.resolve("config.json")
            .writeText("""{"ScreenBackgroundImage":"frame.png","ControlSkinSetId":"$id","SoundBank":"absent.sf2"}""")
        File(source, "skins").apply { mkdirs() }.resolve("frame.png").writeBytes(byteArrayOf(1))
        File(source, "skins/controls/Favorite").apply { mkdirs() }.resolve("skin.json")
            .writeText("""{"id":"$id","version":1}""")
        val zip = File(temp, "bundle.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource("a0001", "Game", "Vendor", "1",
                source, "game", LibraryBundleComponents(true, false, false))), zip,
        )
        ZipFile(zip).use { archive ->
            assertFalse(archive.entries().asSequence().any {
                "/skin-layout/" in it.name || "/control-skin/" in it.name
            })
            val manifest = archive.getInputStream(archive.getEntry("bundle.json"))
                .bufferedReader().use { it.readText() }
            assertFalse("layoutSkinState" in manifest || "controlSkinState" in manifest)
        }
        val parsed = LibraryAppBundleReader.read(zip.inputStream())
        val prepared = LibraryAppBundleImporter.extractUniversalSingleToStaging(
            zip.inputStream(), File(temp, "stage"), parsed.apps.single(), parsed.formatVersion,
        )
        val target = File(temp, "target").apply { mkdirs() }
        File(target, "converted/game").apply { mkdirs() }.resolve("res.jar")
            .writeBytes(byteArrayOf(1, 2, 3))
        File(target, "converted/game")
            .resolve("converted.dex.conf").writeText("MIDlet-Name: Game\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1\n")
        LibraryAppBundleImporter.restore(prepared, target, "game")
        val restored = Json.parseToJsonElement(File(target, "configs/game/config.json").readText())
            .jsonObject
        assertEquals(JsonNull, restored["ScreenBackgroundImage"])
        assertEquals(JsonNull, restored["ControlSkinSetId"])
        assertEquals(JsonNull, restored["SoundBank"])
        assertFalse(File(target, "skins").exists())
    }

    @Test fun appBundleReaderRejectsSkinNamespaces() {
        val source = File(temp, "rejected-source").apply { mkdirs() }
        File(source, "converted/game").apply { mkdirs() }.resolve("res.jar")
            .writeBytes(byteArrayOf(1, 2, 3))
        val base = File(temp, "base.zip")
        LibraryUniversalBundleExporter.exportToZip(
            listOf(LibraryUniversalBundleExporter.AppSource(
                "a0001", "Game", "Vendor", "1", source, "game",
            )), base,
        )
        for (extra in listOf("skin-layout/frame.png", "control-skin/skin.json")) {
            val modified = File(temp, extra.substringBefore('/') + ".zip")
            ZipFile(base).use { input ->
                java.util.zip.ZipOutputStream(modified.outputStream()).use { output ->
                    input.entries().asSequence().forEach { entry ->
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        input.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                    output.putNextEntry(java.util.zip.ZipEntry("apps/a0001/" + extra))
                    output.write(byteArrayOf(1, 2, 3))
                    output.closeEntry()
                }
            }
            org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
                LibraryAppBundleReader.read(modified.inputStream())
            }
        }
    }

    @Test fun existingSharedAssetReferencesRemainValid() {
        val id = UUID.randomUUID().toString()
        val dir = File(temp, "available").apply { mkdirs() }
        val config = File(dir, "configs/game").apply { mkdirs() }
        val json = """{"ScreenBackgroundImage":"frame.png","ControlSkinSetId":"$id","SoundBank":"music.sf2","Shader":{"name":"CRT Filter","author":"Example","fragment":"crt.fsh","vertex":"crt.vsh"}}"""
        File(config, "config.json").writeText(json)
        File(dir, "skins").apply { mkdirs() }.resolve("frame.png").writeBytes(byteArrayOf(1))
        File(dir, "soundbanks").apply { mkdirs() }.resolve("music.sf2").writeText("asset")
        File(dir, "shaders").apply { mkdirs() }.resolve("crt.ini")
            .writeText("[CRT preset]\nName = CRT Filter\nAuthor = Example\nFragment = crt.fsh\nVertex = crt.vsh\n")
        File(dir, "skins/controls/Shared").apply { mkdirs() }.resolve("skin.json")
            .writeText("""{"id":"$id","version":1}""")
        LibraryBundleConfigNormalizer.normalizeConfig(config, dir)
        assertEquals(Json.parseToJsonElement(json),
            Json.parseToJsonElement(File(config, "config.json").readText()))
    }
}
