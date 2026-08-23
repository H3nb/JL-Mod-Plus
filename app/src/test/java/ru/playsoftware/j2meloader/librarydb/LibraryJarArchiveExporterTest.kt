/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.librarydb

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryJarArchiveExporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun archiveSortsAppsSanitizesNamesAndResolvesCollisionsDeterministically() {
        val first = temporaryFolder.newFile("first.jar").apply { writeBytes(byteArrayOf(1, 2)) }
        val second = temporaryFolder.newFile("second.jar").apply { writeBytes(byteArrayOf(3, 4)) }
        val target = File(temporaryFolder.root, "jars.zip")
        LibraryJarArchiveExporter.exportToZip(
            sources = listOf(
                LibraryJarArchiveExporter.JarSource(2L, "A\\Game", second),
                LibraryJarArchiveExporter.JarSource(1L, "A/Game", first),
            ),
            target = target,
        )

        ZipFile(target).use { zip ->
            assertEquals(listOf("A_Game.jar", "A_Game-2.jar"), zip.entries().asSequence().map { it.name }.toList())
            assertArrayEquals(byteArrayOf(1, 2), zip.getInputStream(zip.getEntry("A_Game.jar")).readBytes())
            assertArrayEquals(byteArrayOf(3, 4), zip.getInputStream(zip.getEntry("A_Game-2.jar")).readBytes())
            zip.entries().asSequence().forEach { entry -> assertEquals(0L, entry.time) }
        }

        val secondTarget = File(temporaryFolder.root, "jars-again.zip")
        LibraryJarArchiveExporter.exportToZip(
            sources = listOf(
                LibraryJarArchiveExporter.JarSource(1L, "A/Game", first),
                LibraryJarArchiveExporter.JarSource(2L, "A\\Game", second),
            ),
            target = secondTarget,
        )
        assertArrayEquals(Files.readAllBytes(target.toPath()), Files.readAllBytes(secondTarget.toPath()))
    }

    @Test
    fun emptyOrMissingSourceIsRejected() {
        val target = File(temporaryFolder.root, "empty.zip")
        try {
            LibraryJarArchiveExporter.exportToZip(
                listOf(LibraryJarArchiveExporter.JarSource(1L, "Missing", File(temporaryFolder.root, "missing.jar"))),
                target,
            )
            throw AssertionError("Expected missing JAR to fail")
        } catch (_: java.io.IOException) {
            assertTrue(!target.exists() || target.length() == 0L)
        }
    }
}
