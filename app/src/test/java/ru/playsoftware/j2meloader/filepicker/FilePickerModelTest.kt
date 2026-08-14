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

package ru.playsoftware.j2meloader.filepicker

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilePickerModelTest {
    @Test
    fun filtersOnlySupportedFilesAndSortsFoldersFirst() {
        val root = Files.createTempDirectory("jlmod-picker").toFile()
        try {
            File(root, "zeta.jar").createNewFile()
            File(root, "alpha.JAD").createNewFile()
            File(root, "ignored.txt").createNewFile()
            File(root, ".hidden.jar").createNewFile()
            File(root, "Games").mkdir()

            val entries = FilePickerRules.sortEntries(
                root.listFiles()!!.asIterable(),
                FilePickerContract.MODE_FILE,
            )

            assertEquals(listOf("Games", "alpha.JAD", "zeta.jar"), entries.map { it.name })
            assertTrue(entries.first().kind == FilePickerEntryKind.DIRECTORY)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun searchAndSortAreIndependentOfFilesystemImplementation() {
        val entries = listOf(
            FilePickerEntry("/a", "Zeta.jar", FilePickerEntryKind.FILE, modifiedAt = 3L),
            FilePickerEntry("/b", "alpha.jar", FilePickerEntryKind.FILE, modifiedAt = 1L),
            FilePickerEntry("/c", "Games", FilePickerEntryKind.DIRECTORY, modifiedAt = 2L),
        )

        assertEquals(
            listOf("alpha.jar", "Zeta.jar"),
            FilePickerRules.filterAndSort(
                entries,
                query = ".jar",
                sortOrder = FilePickerSortOrder.NAME_ASCENDING,
            ).map { it.name },
        )
        assertEquals(
            listOf("Zeta.jar", "Games", "alpha.jar"),
            FilePickerRules.filterAndSort(
                entries,
                query = "",
                sortOrder = FilePickerSortOrder.MODIFIED_NEWEST,
            ).map { it.name },
        )
    }

    @Test
    fun modeContractsKeepDirectoryAndExistingFileSelectionDistinct() {
        val existingFile = Files.createTempFile("existing", ".jar").toFile()
        try {
            val directoryRequest = FilePickerRequest(
                startPath = null,
                mode = FilePickerContract.MODE_FILE_AND_DIR,
                allowMultiple = false,
                singleClick = false,
                allowCreateDirectory = false,
                allowExistingFile = true,
            )
            val multipleRequest = directoryRequest.copy(allowMultiple = true)
            val directoryState = FilePickerState(
                request = directoryRequest,
                rootPath = "/storage",
                currentPath = "/storage/emulated/0",
            )

            assertTrue(directoryState.canConfirm)
            assertFalse(directoryState.copy(request = multipleRequest).canConfirm)
            assertTrue(
                FilePickerRules.isVisible(
                    existingFile,
                    FilePickerContract.MODE_NEW_FILE,
                    allowExistingFile = true,
                ),
            )
            assertFalse(
                FilePickerRules.isVisible(
                    existingFile,
                    FilePickerContract.MODE_NEW_FILE,
                    allowExistingFile = false,
                ),
            )
        } finally {
            existingFile.delete()
        }
    }

    @Test
    fun startPathAndParentStayInsideRoot() {
        val root = Files.createTempDirectory("jlmod-root").toFile()
        val nested = File(root, "emulated/0").apply { mkdirs() }
        try {
            assertEquals(nested, FilePickerRules.normalizeStartPath(nested.path, root))
            assertEquals(root, FilePickerRules.normalizeStartPath(File(root, "outside").path, root))
            assertEquals(
                root,
                FilePickerRules.normalizeStartPath(File(root, "../outside").path, root),
            )
            assertEquals(File(root, "emulated"), FilePickerRules.parent(nested, root))
            assertFalse(FilePickerRules.isValidFolderName("../escape"))
            assertTrue(FilePickerRules.isValidFolderName("My Games"))
        } finally {
            root.deleteRecursively()
        }
    }
}
