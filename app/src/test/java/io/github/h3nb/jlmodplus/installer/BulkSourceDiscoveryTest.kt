/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkSourceDiscoveryTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun midletJar(name: String = "Demo"): ByteArray = zip(
        "META-INF/MANIFEST.MF" to (
            "Manifest-Version: 1.0\r\n" +
                "MIDlet-Name: $name\r\n" +
                "MIDlet-Vendor: Test\r\n" +
                "MIDlet-Version: 1.0\r\n" +
                "MIDlet-1: $name, , test.Main\r\n\r\n"
            ).toByteArray(),
        "test/Main.class" to byteArrayOf(1, 2, 3),
    )

    private fun tempRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("bulk-discovery").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recursivelyFindsLocalFilesAndDeduplicatesOverlappingFolders() = tempRoot { root ->
        val games = File(root, "games").apply { mkdirs() }
        val nested = File(games, "Nokia").apply { mkdirs() }
        val jar = File(nested, "bounce.jar").apply { writeBytes(midletJar()) }
        val jad = File(games, "game.jad").apply { writeText("JAD") }
        File(games, "notes.txt").writeText("Not an application")

        val result = BulkSourceDiscovery(File(root, "stage"))
            .discover(listOf(games, nested, jar))
        assertEquals(setOf(jar.canonicalFile, jad.canonicalFile), result.files.toSet())
        assertEquals(0, result.unreadableArchives)
    }

    @Test
    fun treatsRenamedJarAsOneGameWithoutExtractingItsClasses() = tempRoot { root ->
        val source = File(root, "Bounce Tales.zip").apply { writeBytes(midletJar("Bounce Tales")) }
        val result = BulkSourceDiscovery(File(root, "stage")).discover(listOf(source))
        assertEquals(1, result.files.size)
        assertEquals(source.canonicalFile, result.files.single())
        assertTrue(result.files.single().isFile)
        assertFalse(result.files.any { it.name.endsWith(".class") })
    }

    @Test
    fun oversizedMidletManifestIsIgnoredWithoutStoppingOtherGames() = tempRoot { root ->
        val oversized = zip(
            "META-INF/MANIFEST.MF" to (
                "Manifest-Version: 1.0\r\n" +
                    "MIDlet-Name: Huge\r\n" +
                    "MIDlet-Vendor: Test\r\n" +
                    "MIDlet-Version: 1.0\r\n" +
                    "MIDlet-Description: " + "x".repeat(300_000) + "\r\n\r\n"
                ).toByteArray(),
        )
        val container = File(root, "Pack.zip").apply {
            writeBytes(zip("Huge.zip" to oversized, "Valid.jar" to midletJar()))
        }
        val discovered = BulkSourceDiscovery(File(root, "stage")).discover(listOf(container))
        assertEquals(listOf("Valid.jar"), discovered.files.map { it.name })
    }

    @Test
    fun findsCollectionEntriesAndNestedArchiveGames() = tempRoot { root ->
        val source = File(root, "Collection.zip").apply {
            writeBytes(zip(
                "Action/Asphalt.jar" to midletJar("Asphalt"),
                "Action/Java Pack.zip" to zip(
                    "Bounce.zip" to midletJar("Bounce"),
                    "notes.txt" to "Ignore".toByteArray(),
                ),
                "cover.png" to byteArrayOf(1, 2),
            ))
        }
        val result = BulkSourceDiscovery(File(root, "stage")).discover(listOf(source))
        assertEquals(setOf("Asphalt.jar", "Bounce.zip"), result.files.map { it.name }.toSet())
        assertEquals(0, result.unreadableArchives)
    }

    @Test
    fun pairsJadWithRenamedJarInsideCollection() = tempRoot { root ->
        val jad = (
            "MIDlet-Name: Demo\nMIDlet-Vendor: Test\nMIDlet-Version: 1.0\n" +
                "MIDlet-Jar-URL: Demo.jar\nMIDlet-Jar-Size: 1024\n"
            ).toByteArray()
        val source = File(root, "Pack.zip").apply {
            writeBytes(zip(
                "Games/Demo.jad" to jad,
                "Games/Demo.zip" to midletJar(),
            ))
        }
        val found = BulkSourceDiscovery(File(root, "stage")).discover(listOf(source)).files
        val jadFile = found.single { it.extension == "jad" }
        val jarFile = found.single { it.extension == "zip" }
        val resolution = BulkInstallPlanner.resolveJadReference(
            jadFile, "Demo.jar", null, setOf(jarFile.canonicalPath),
        )
        assertEquals(BulkInstallPlanner.JadResolution.Local(jarFile.canonicalFile), resolution)
    }

    @Test
    fun pairsAdjacentJadWithJarRenamedZipWithoutTouchingOriginals() = tempRoot { root ->
        val source = File(root, "Demo.zip").apply { writeBytes(midletJar()) }
        val jad = File(root, "Demo.jad").apply {
            writeText(
                "MIDlet-Name: Demo\nMIDlet-Vendor: Test\nMIDlet-Version: 1.0\n" +
                    "MIDlet-Jar-URL: Demo.jar\nMIDlet-Jar-Size: 1024\n",
            )
        }
        val found = BulkSourceDiscovery(File(root, "stage")).discover(listOf(root)).files
        val foundJad = found.single { it.extension == "jad" }
        val foundJar = found.single { it.extension == "zip" }
        assertEquals(jad.canonicalFile, foundJad)
        assertEquals(source.canonicalFile, foundJar)
        assertEquals(BulkInstallPlanner.JadResolution.Local(foundJar.canonicalFile),
            BulkInstallPlanner.resolveJadReference(
                foundJad, "Demo.jar", null, setOf(foundJar.canonicalPath),
            ))
        assertFalse(File(root, "stage").exists())
    }

    @Test
    fun ignoresTraversalEntriesButContinuesAfterBadArchive() = tempRoot { root ->
        val source = File(root, "Pack.zip").apply {
            writeBytes(zip(
                "../../escape.jar" to midletJar("Bad"),
                "Good.jar" to midletJar("Good"),
            ))
        }
        val invalid = File(root, "broken.zip").apply { writeText("Not a ZIP") }
        val result = BulkSourceDiscovery(File(root, "stage")).discover(listOf(source, invalid))
        assertEquals(listOf("Good.jar"), result.files.map { it.name })
        assertEquals(1, result.unreadableArchives)
        assertTrue(result.skippedArchiveEntries >= 1)
        assertFalse(File(root, "escape.jar").exists())
    }

    @Test
    fun thousandsOfRenamedMidletsRequireNoStagingCopies() = tempRoot { root ->
        val games = File(root, "Collection").apply { mkdirs() }
        val bytes = midletJar()
        repeat(1200) { index ->
            File(games, "game-$index.zip").writeBytes(bytes)
        }
        val stage = File(root, "stage")
        val result = BulkSourceDiscovery(stage).discover(listOf(games))
        assertEquals(1200, result.files.size)
        assertEquals(0, result.unreadableArchives)
        assertFalse(stage.exists())
    }

    @Test(expected = CancellationException::class)
    fun cancellationAbortsDiscovery() = tempRoot { root ->
        val game = File(root, "Game.jar").apply { writeBytes(midletJar()) }
        BulkSourceDiscovery(File(root, "stage")) { false }.discover(listOf(game))
    }
}
