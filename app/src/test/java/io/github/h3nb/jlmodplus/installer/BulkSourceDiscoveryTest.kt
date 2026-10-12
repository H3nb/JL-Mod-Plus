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
        assertEquals("Bounce Tales.jar", result.files.single().name)
        assertTrue(result.files.single().isFile)
        assertFalse(result.files.any { it.name.endsWith(".class") })
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
        assertEquals(setOf("Asphalt.jar", "Bounce.jar"), result.files.map { it.name }.toSet())
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
        val jarFile = found.single { it.extension == "jar" }
        val resolution = BulkInstallPlanner.resolveJadReference(jadFile, "Demo.jar", null)
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
        val copiedJad = found.single { it.extension == "jad" }
        val copiedJar = found.single { it.extension == "jar" }
        assertFalse(jad.canonicalFile == copiedJad.canonicalFile)
        assertEquals(BulkInstallPlanner.JadResolution.Local(copiedJar.canonicalFile),
            BulkInstallPlanner.resolveJadReference(copiedJad, "Demo.jar", null))
        assertTrue(source.exists())
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

    @Test(expected = CancellationException::class)
    fun cancellationAbortsDiscovery() = tempRoot { root ->
        val game = File(root, "Game.jar").apply { writeBytes(midletJar()) }
        BulkSourceDiscovery(File(root, "stage")) { false }.discover(listOf(game))
    }
}
