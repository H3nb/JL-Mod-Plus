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

import android.content.Intent
import java.io.File
import java.io.IOException
import java.util.Locale

/** The launch configuration consumed by the app-owned picker. */
data class FilePickerRequest(
    val startPath: String?,
    val mode: Int,
    val allowMultiple: Boolean,
    val singleClick: Boolean,
    val allowCreateDirectory: Boolean,
    val allowExistingFile: Boolean,
) {
    val allowsDirectories: Boolean
        get() = mode == FilePickerContract.MODE_DIR || mode == FilePickerContract.MODE_FILE_AND_DIR

    val allowsFiles: Boolean
        get() = mode == FilePickerContract.MODE_FILE ||
            mode == FilePickerContract.MODE_FILE_AND_DIR ||
            (mode == FilePickerContract.MODE_NEW_FILE && allowExistingFile)

    companion object {
        fun fromIntent(intent: Intent): FilePickerRequest = FilePickerRequest(
            startPath = intent.getStringExtra(FilePickerContract.EXTRA_START_PATH),
            mode = intent.getIntExtra(FilePickerContract.EXTRA_MODE, FilePickerContract.MODE_FILE),
            allowMultiple = intent.getBooleanExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, false),
            singleClick = intent.getBooleanExtra(FilePickerContract.EXTRA_SINGLE_CLICK, false),
            allowCreateDirectory = intent.getBooleanExtra(
                FilePickerContract.EXTRA_ALLOW_CREATE_DIR,
                false,
            ),
            allowExistingFile = intent.getBooleanExtra(
                FilePickerContract.EXTRA_ALLOW_EXISTING_FILE,
                true,
            ),
        )
    }
}

enum class FilePickerEntryKind {
    DIRECTORY,
    FILE,
    VOLUME,
}

enum class FilePickerSortOrder {
    NAME_ASCENDING,
    NAME_DESCENDING,
    TYPE_THEN_NAME,
    MODIFIED_NEWEST,
    MODIFIED_OLDEST,
}

/** A display-ready row. The path remains the actual filesystem path. */
data class FilePickerEntry(
    val path: String,
    val name: String,
    val kind: FilePickerEntryKind,
    val modifiedAt: Long = 0L,
)

data class FilePickerState(
    val request: FilePickerRequest,
    val rootPath: String,
    val currentPath: String,
    val entries: List<FilePickerEntry> = emptyList(),
    val selectedPaths: Set<String> = emptySet(),
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val sortOrder: FilePickerSortOrder = FilePickerSortOrder.TYPE_THEN_NAME,
    val loading: Boolean = false,
    val permissionRequired: Boolean = false,
    val errorMessage: String? = null,
    val showCreateFolder: Boolean = false,
    val createFolderName: String = "",
    val createFolderError: String? = null,
) {
    val canGoUp: Boolean
        get() = currentPath != rootPath

    val canConfirm: Boolean
        get() = !permissionRequired && !loading && errorMessage == null &&
            (request.mode == FilePickerContract.MODE_DIR ||
                (request.mode == FilePickerContract.MODE_FILE_AND_DIR &&
                    !request.allowMultiple && selectedPaths.isEmpty()) ||
                selectedPaths.isNotEmpty())
}

/** Pure path, filtering, and ordering rules shared by production code and tests. */
object FilePickerRules {
    private val allowedExtensions = setOf(".jad", ".jar", ".kjx")

    fun normalizeStartPath(startPath: String?, root: File): File {
        val candidate = startPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.let { if (it.isDirectory) it else it.parentFile }
            ?: root
        val absolute = canonicalFile(candidate)
        val rootAbsolute = canonicalFile(root)
        return if (isWithinRoot(absolute, rootAbsolute)) absolute else rootAbsolute
    }

    fun isVisible(file: File, mode: Int, allowExistingFile: Boolean = false): Boolean {
        // Android marks hidden files through File.isHidden on device storage, but
        // dotfiles are not consistently reported as hidden on every host/JVM.
        if (file.isHidden || file.name.startsWith(".")) {
            return false
        }
        if (file.isDirectory) {
            return true
        }
        if (!file.isFile) {
            return false
        }
        val acceptsExistingFile = mode == FilePickerContract.MODE_FILE ||
            mode == FilePickerContract.MODE_FILE_AND_DIR ||
            (mode == FilePickerContract.MODE_NEW_FILE && allowExistingFile)
        if (!acceptsExistingFile) {
            return false
        }
        val name = file.name.lowercase(Locale.ROOT)
        return allowedExtensions.any(name::endsWith)
    }

    fun sortEntries(
        files: Iterable<File>,
        mode: Int,
        allowExistingFile: Boolean = false,
        root: File? = null,
    ): List<FilePickerEntry> = files
        .filter { file ->
            (root == null || isWithinRoot(file, root)) &&
                isVisible(file, mode, allowExistingFile)
        }
        .sortedWith(
            compareBy<File> { !it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
        )
        .map {
            FilePickerEntry(
                path = it.absolutePath,
                name = it.name,
                kind = if (it.isDirectory) FilePickerEntryKind.DIRECTORY
                else FilePickerEntryKind.FILE,
                modifiedAt = it.lastModified(),
            )
        }

    fun filterAndSort(
        entries: Iterable<FilePickerEntry>,
        query: String,
        sortOrder: FilePickerSortOrder,
    ): List<FilePickerEntry> {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        return entries
            .filter { normalizedQuery.isEmpty() || it.name.lowercase(Locale.ROOT).contains(normalizedQuery) }
            .sortedWith(
                when (sortOrder) {
                    FilePickerSortOrder.NAME_ASCENDING ->
                        compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    FilePickerSortOrder.NAME_DESCENDING ->
                        compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name }
                    FilePickerSortOrder.TYPE_THEN_NAME -> compareBy<FilePickerEntry> {
                        it.kind == FilePickerEntryKind.FILE
                    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    FilePickerSortOrder.MODIFIED_NEWEST -> compareByDescending<FilePickerEntry> {
                        it.modifiedAt
                    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                    FilePickerSortOrder.MODIFIED_OLDEST -> compareBy<FilePickerEntry> {
                        it.modifiedAt
                    }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                },
            )
    }

    fun parent(path: File, root: File): File {
        val absolute = canonicalFile(path)
        val rootAbsolute = canonicalFile(root)
        if (absolute == rootAbsolute) {
            return rootAbsolute
        }
        val parent = absolute.parentFile ?: rootAbsolute
        return if (isWithinRoot(parent, rootAbsolute)) parent else rootAbsolute
    }

    fun isValidFolderName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && trimmed != "." && trimmed != ".." &&
            !trimmed.contains('/') && !trimmed.contains('\\')
    }

    fun isWithinRoot(path: File, root: File): Boolean {
        val canonicalPath = canonicalFile(path)
        val canonicalRoot = canonicalFile(root)
        val pathValue = canonicalPath.absolutePath.trimEnd(File.separatorChar)
        val rootValue = canonicalRoot.absolutePath.trimEnd(File.separatorChar)
        return rootValue == File.separator || pathValue == rootValue ||
            pathValue.startsWith(rootValue + File.separator)
    }

    private fun canonicalFile(file: File): File = try {
        file.canonicalFile
    } catch (_: IOException) {
        file.absoluteFile
    } catch (_: SecurityException) {
        file.absoluteFile
    }
}
