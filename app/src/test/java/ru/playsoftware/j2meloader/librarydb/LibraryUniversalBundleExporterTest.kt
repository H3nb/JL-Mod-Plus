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
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryUniversalBundleExporterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun singleAndBulkUseOneDeterministicNamespaceAndManifest() {
        val root = temporaryFolder.newFolder("workdir")
        val first = createApp(root, "first", byteArrayOf(1, 2), config = Empty, data = Missing)
        val second = createApp(root, "second", byteArrayOf(3, 4), config = FileContent("config.json", "{}"), data = Empty)
        File(File(File(root, "converted"), "second"), "converted.dex.conf")
            .writeText("MIDlet-Name: Second\n")
        val target = File(temporaryFolder.root, "universal.zip")

        LibraryUniversalBundleExporter.exportToZip(
            apps = listOf(
                LibraryUniversalBundleExporter.AppSource("a0002", "Second", "Vendor", "2.0", root, "second"),
                LibraryUniversalBundleExporter.AppSource("a0001", "First", "Vendor", "1.0", root, "first"),
            ),
            target = target,
        )

        ZipFile(target).use { zip ->
            assertEquals(
                listOf(
                    "bundle.json",
                    "apps/a0001/app/res.jar",
                    "apps/a0001/config/",
                    "apps/a0002/app/converted.dex.conf",
                    "apps/a0002/app/res.jar",
                    "apps/a0002/config/",
                    "apps/a0002/config/config.json",
                    "apps/a0002/data/",
                ),
                zip.entries().asSequence().map { it.name }.toList(),
            )
            zip.entries().asSequence().forEach { entry ->
                assertEquals("ZIP entries must use the fixed epoch timestamp", 0L, entry.time)
            }
            val manifest = zip.getInputStream(zip.getEntry("bundle.json"))
                .bufferedReader()
                .readText()
            assertTrue(manifest.indexOf("a0001") < manifest.indexOf("a0002"))
            assertTrue(manifest.contains("\"configState\":\"present-empty\""))
            assertTrue(manifest.contains("\"dataState\":\"absent\""))
            assertTrue(manifest.contains("\"dataState\":\"present-empty\""))
            assertTrue(manifest.contains(sha256(first.jar)))
            assertTrue(manifest.contains(sha256(second.jar)))
        }

        val parsed = LibraryAppBundleReader.read(target.inputStream())
        assertEquals(listOf("a0001", "a0002"), parsed.apps.map(BundleApp::bundleId))
        assertEquals(BundleNamespaceState.PresentEmpty, parsed.apps[0].configState)
        assertEquals(BundleNamespaceState.Absent, parsed.apps[0].dataState)
        assertEquals(BundleNamespaceState.Present, parsed.apps[1].configState)
        assertEquals(BundleNamespaceState.PresentEmpty, parsed.apps[1].dataState)
        assertFalse(parsed.legacyAssurance)

        val secondTarget = File(temporaryFolder.root, "universal-again.zip")
        LibraryUniversalBundleExporter.exportToZip(
            apps = listOf(
                LibraryUniversalBundleExporter.AppSource("a0001", "First", "Vendor", "1.0", root, "first"),
                LibraryUniversalBundleExporter.AppSource("a0002", "Second", "Vendor", "2.0", root, "second"),
            ),
            target = secondTarget,
        )
        assertArrayEquals(Files.readAllBytes(target.toPath()), Files.readAllBytes(secondTarget.toPath()))
    }

    @Test
    fun rejectsDuplicateOrUnsafeArchiveIdentityBeforeWriting() {
        val root = temporaryFolder.newFolder("workdir")
        createApp(root, "first", byteArrayOf(1), config = Missing, data = Missing)
        val target = File(temporaryFolder.root, "rejected.zip")
        val source = LibraryUniversalBundleExporter.AppSource("a0001", "First", "Vendor", "1.0", root, "first")

        assertExportFails(target) {
            LibraryUniversalBundleExporter.exportToZip(listOf(source, source), target)
        }
        assertExportFails(target) {
            LibraryUniversalBundleExporter.exportToZip(
                listOf(source.copy(bundleId = "../unsafe")),
                target,
            )
        }
    }

    @Test
    fun rejectsCanonicalDirectoryCyclesBeforeRecursing() {
        val root = temporaryFolder.newFolder("workdir-cycle")
        createApp(root, "cycle", byteArrayOf(1), config = Empty, data = Missing)
        val namespace = File(File(root, "configs"), "cycle")
        val cycle = namespace.toPath().resolve("loop")
        try {
            Files.createSymbolicLink(cycle, namespace.toPath())
        } catch (error: Exception) {
            // Symbolic-link creation requires elevated privileges on some Windows test runners.
            org.junit.Assume.assumeNoException(error)
        }

        assertExportFails(File(temporaryFolder.root, "cycle.zip")) {
            LibraryUniversalBundleExporter.exportToZip(
                apps = listOf(
                    LibraryUniversalBundleExporter.AppSource(
                        "a0001",
                        "Cycle",
                        "Vendor",
                        "1.0",
                        root,
                        "cycle",
                    ),
                ),
                target = File(temporaryFolder.root, "cycle.zip"),
            )
        }
    }

    private fun assertExportFails(target: File, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected universal export to fail")
        } catch (_: IOException) {
            assertTrue(!target.exists() || target.length() == 0L)
        } catch (_: IllegalArgumentException) {
            assertTrue(!target.exists() || target.length() == 0L)
        }
    }

    private fun createApp(
        root: File,
        storageKey: String,
        jarBytes: ByteArray,
        config: Namespace,
        data: Namespace,
    ): AppFixture {
        val converted = File(File(root, "converted"), storageKey).apply { mkdirs() }
        val jar = File(converted, "res.jar").apply { writeBytes(jarBytes) }
        createNamespace(root, "configs", storageKey, config)
        createNamespace(root, "data", storageKey, data)
        return AppFixture(jar)
    }

    private fun createNamespace(root: File, parent: String, storageKey: String, namespace: Namespace) {
        val directory = File(File(root, parent), storageKey)
        when (namespace) {
            Missing -> Unit
            Empty -> assertTrue(directory.mkdirs())
            is FileContent -> {
                assertTrue(directory.mkdirs())
                File(directory, namespace.name).writeText(namespace.content)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.readBytes())
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class AppFixture(val jar: File)

    private sealed interface Namespace
    private object Missing : Namespace
    private object Empty : Namespace
    private data class FileContent(val name: String, val content: String) : Namespace
}
