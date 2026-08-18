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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibraryScannerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scanner = LibraryScanner()

    @Test
    fun scanReadsSourceMetadataWithoutWritingConvertedDirectory() {
        val root = temporaryFolder.newFolder("workdir")
        val appDir = createConvertedApp(
            root = root,
            storageKey = "Game",
            descriptor = """
                MIDlet-Name: Example Game
                MIDlet-Vendor: Example Vendor
                MIDlet-Version: 1.2.3
                MIDlet-Description: A useful description
            """.trimIndent(),
        )

        val before = appDir.listFiles()!!.map { it.name }.sorted()
        val result = scanner.scan(root)
        val after = appDir.listFiles()!!.map { it.name }.sorted()

        assertEquals(1, result.apps.size)
        assertTrue(result.failures.isEmpty())
        val app = result.apps.single()
        assertEquals("Game", app.storageKey)
        assertEquals("Example Game", app.sourceTitle)
        assertEquals("Example Vendor", app.sourceVendor)
        assertEquals("1.2.3", app.sourceVersion)
        assertEquals("A useful description", app.sourceDescription)
        assertEquals(before, after)
        assertFalse(File(appDir, "icon.png").exists())
    }

    @Test
    fun malformedDescriptorIsReportedWithoutDeletingEvidence() {
        val root = temporaryFolder.newFolder("workdir")
        val appDir = createConvertedApp(
            root = root,
            storageKey = "Broken",
            descriptor = "MIDlet-Name: Missing required fields",
        )
        val descriptor = File(appDir, "converted.dex.conf")
        val payload = File(appDir, "converted.dex")

        val result = scanner.scan(root)

        assertTrue(result.apps.isEmpty())
        assertEquals(listOf("Broken"), result.failures.map { it.storageKey })
        assertTrue(appDir.isDirectory)
        assertTrue(descriptor.isFile)
        assertTrue(payload.isFile)
    }

    @Test
    fun missingConvertedPayloadIsReportedWithoutDeletingDirectory() {
        val root = temporaryFolder.newFolder("workdir")
        val appDir = File(File(root, "converted").apply { mkdir() }, "Incomplete").apply { mkdir() }
        File(appDir, "converted.dex.conf").writeText(
            "MIDlet-Name: Incomplete\nMIDlet-Vendor: Vendor\nMIDlet-Version: 1.0\n",
        )

        val result = scanner.scan(root)

        assertTrue(result.apps.isEmpty())
        assertEquals("Incomplete", result.failures.single().storageKey)
        assertTrue(appDir.isDirectory)
        assertTrue(File(appDir, "converted.dex.conf").isFile)
    }

    @Test
    fun legacyConvertedStagingDirectoryIsIgnored() {
        val root = temporaryFolder.newFolder("workdir")
        val converted = File(root, "converted").apply { mkdir() }
        val legacy = File(converted, LibraryInstallRecovery.LEGACY_STAGING_DIR_NAME).apply { mkdir() }
        File(legacy, "converted.dex").writeText("legacy partial")
        val current = LibraryInstallRecovery.stagingDirectory(root).apply { mkdir() }
        File(current, "converted.dex").writeText("current sibling partial")

        val result = scanner.scan(root)

        assertTrue(result.apps.isEmpty())
        assertTrue(result.failures.isEmpty())
        assertTrue(legacy.isDirectory)
        assertTrue(current.isDirectory)
    }

    @Test
    fun storageKeySnapshotDoesNotRequireDescriptorParsing() {
        val root = temporaryFolder.newFolder("workdir")
        val converted = File(root, "converted").apply { mkdir() }
        File(converted, "One").mkdir()
        File(converted, "Two").mkdir()
        File(converted, LibraryInstallRecovery.LEGACY_STAGING_DIR_NAME).mkdir()
        LibraryInstallRecovery.stagingDirectory(root).mkdir()

        assertEquals(linkedSetOf("One", "Two"), scanner.storageKeys(root))
    }

    @Test(expected = IOException::class)
    fun convertedPathThatIsNotDirectoryIsNotTreatedAsEmptyLibrary() {
        val root = temporaryFolder.newFolder("workdir")
        File(root, "converted").writeText("not a directory")

        scanner.storageKeys(root)
    }

    private fun createConvertedApp(root: File, storageKey: String, descriptor: String): File {
        val converted = File(root, "converted").apply { mkdir() }
        val appDir = File(converted, storageKey).apply { mkdir() }
        File(appDir, "converted.dex").writeText("payload")
        File(appDir, "converted.dex.conf").writeText(descriptor + "\n")
        return appDir
    }
}
