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

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.util.StoragePermissionHelper

/**
 * Owns filesystem work and screen state independently of Compose. This keeps
 * permission, loading, and result behavior testable without making the UI
 * responsible for file-system operations.
 */
class FilePickerController(
    private val context: Context,
    val request: FilePickerRequest,
    restoredPath: String?,
    restoredSelection: Set<String>,
    restoredSearchVisible: Boolean = false,
    restoredSearchQuery: String = "",
    restoredSortOrder: FilePickerSortOrder = FilePickerSortOrder.TYPE_THEN_NAME,
    private val onStateChanged: (FilePickerState) -> Unit,
    private val onFilesPicked: (List<File>) -> Unit,
) : AutoCloseable {
    private val root = rootDirectory()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "jlmod-file-picker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var closed = false
    private var loadGeneration = 0L
    private var folderOperationGeneration = 0L
    private var currentDirectory = FilePickerRules.normalizeStartPath(restoredPath, root)
    private var state = FilePickerState(
        request = request,
        rootPath = root.absolutePath,
        currentPath = currentDirectory.absolutePath,
        selectedPaths = restoredSelection,
        searchVisible = restoredSearchVisible,
        searchQuery = restoredSearchQuery,
        sortOrder = restoredSortOrder,
        permissionRequired = !StoragePermissionHelper.isGranted(context),
    )

    init {
        if (!state.permissionRequired) {
            loadCurrentDirectory()
        }
    }

    fun currentState(): FilePickerState = state

    fun toggleSearch() {
        publish(
            state.copy(
                searchVisible = !state.searchVisible,
                searchQuery = if (state.searchVisible) "" else state.searchQuery,
            ),
        )
    }

    fun setSearchQuery(query: String) {
        publish(state.copy(searchQuery = query))
    }

    fun setSortOrder(sortOrder: FilePickerSortOrder) {
        publish(state.copy(sortOrder = sortOrder))
    }

    fun navigateBack(): Boolean {
        if (!state.canGoUp) {
            return false
        }
        navigateTo(FilePickerRules.parent(currentDirectory, root))
        return true
    }

    fun open(entry: FilePickerEntry) {
        if (entry.kind != FilePickerEntryKind.FILE) {
            navigateTo(File(entry.path))
            return
        }
        if (request.singleClick && !request.allowMultiple && request.allowsFiles) {
            complete(listOf(File(entry.path)))
            return
        }
        toggleSelection(entry.path)
    }

    fun toggleSelection(path: String) {
        if (!request.allowsFiles) {
            return
        }
        val selected = LinkedHashSet(state.selectedPaths)
        if (request.allowMultiple) {
            if (!selected.add(path)) {
                selected.remove(path)
            }
        } else {
            selected.clear()
            selected.add(path)
        }
        publish(state.copy(selectedPaths = selected, errorMessage = null))
    }

    fun confirmSelection() {
        if (!state.canConfirm) {
            return
        }
        if (request.mode == FilePickerContract.MODE_DIR ||
            (request.mode == FilePickerContract.MODE_FILE_AND_DIR &&
                !request.allowMultiple && state.selectedPaths.isEmpty())
        ) {
            complete(listOf(currentDirectory))
            return
        }
        val selected = state.selectedPaths.map(::File)
        if (selected.isNotEmpty()) {
            complete(selected)
        }
    }

    fun requestPermission() {
        // The Activity owns the ActivityResult launcher. The UI only exposes this intent.
        publish(state.copy(permissionRequired = true, errorMessage = null))
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            publish(state.copy(permissionRequired = false, errorMessage = null))
            loadCurrentDirectory()
        } else {
            publish(
                state.copy(
                    permissionRequired = true,
                    errorMessage = context.getString(R.string.file_picker_permission_denied),
                ),
            )
        }
    }

    fun retry() {
        if (StoragePermissionHelper.isGranted(context)) {
            publish(state.copy(permissionRequired = false, errorMessage = null))
            loadCurrentDirectory()
        } else {
            requestPermission()
        }
    }

    fun refresh() {
        if (!state.permissionRequired) {
            loadCurrentDirectory()
        }
    }

    fun showCreateFolder() {
        if (request.allowCreateDirectory) {
            publish(state.copy(showCreateFolder = true, createFolderError = null))
        }
    }

    fun dismissCreateFolder() {
        publish(state.copy(showCreateFolder = false, createFolderError = null))
    }

    fun setCreateFolderName(name: String) {
        publish(state.copy(createFolderName = name, createFolderError = null))
    }

    fun createFolder() {
        val name = state.createFolderName.trim()
        if (!FilePickerRules.isValidFolderName(name)) {
            publish(
                state.copy(createFolderError = context.getString(R.string.file_picker_invalid_folder_name)),
            )
            return
        }

        val parent = currentDirectory
        val target = File(parent, name)
        val targetExists = try {
            target.exists()
        } catch (_: SecurityException) {
            false
        }
        if (targetExists) {
            publish(state.copy(createFolderError = context.getString(R.string.file_picker_folder_exists)))
            return
        }

        val operation = ++folderOperationGeneration
        publish(state.copy(loading = true, createFolderError = null))
        if (!execute {
            val created = try {
                target.mkdir()
            } catch (_: SecurityException) {
                false
            }
            mainHandler.post {
                if (closed || operation != folderOperationGeneration) {
                    return@post
                }
                if (created) {
                    if (currentDirectory == parent) {
                        navigateTo(target)
                    }
                } else if (currentDirectory == parent) {
                    publish(
                        state.copy(
                            loading = false,
                            createFolderError = context.getString(R.string.file_picker_create_folder_failed),
                        ),
                    )
                }
            }
        }) {
            if (operation == folderOperationGeneration) {
                publish(
                    state.copy(
                        loading = false,
                        createFolderError = context.getString(R.string.file_picker_create_folder_failed),
                    ),
                )
            }
        }
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun navigateTo(directory: File) {
        folderOperationGeneration++
        currentDirectory = FilePickerRules.normalizeStartPath(directory.absolutePath, root)
        publish(
            state.copy(
                currentPath = currentDirectory.absolutePath,
                entries = emptyList(),
                selectedPaths = emptySet(),
                searchVisible = false,
                searchQuery = "",
                loading = true,
                errorMessage = null,
                showCreateFolder = false,
                createFolderName = "",
                createFolderError = null,
            ),
        )
        loadCurrentDirectory()
    }

    private fun loadCurrentDirectory() {
        if (closed) {
            return
        }
        val directory = currentDirectory
        val generation = ++loadGeneration
        if (!StoragePermissionHelper.isGranted(context)) {
            publish(state.copy(permissionRequired = true, loading = false))
            return
        }

        publish(state.copy(loading = true, errorMessage = null))
        if (!execute {
            try {
                val entries = loadEntries(directory)
                mainHandler.post {
                    if (closed || generation != loadGeneration) {
                        return@post
                    }
                    val availableFilePaths = entries
                        .asSequence()
                        .filter { it.kind == FilePickerEntryKind.FILE }
                        .map { it.path }
                        .toSet()
                    publish(
                        state.copy(
                            currentPath = currentDirectory.absolutePath,
                            entries = entries,
                            selectedPaths = state.selectedPaths.intersect(availableFilePaths),
                            loading = false,
                            permissionRequired = false,
                            errorMessage = null,
                        ),
                    )
                }
            } catch (_: Exception) {
                mainHandler.post {
                    if (closed || generation != loadGeneration) {
                        return@post
                    }
                    publish(
                        state.copy(
                            entries = emptyList(),
                            loading = false,
                            errorMessage = context.getString(R.string.file_picker_load_failed),
                        ),
                    )
                }
            }
        }) {
            if (generation == loadGeneration) {
                publish(
                    state.copy(
                        entries = emptyList(),
                        loading = false,
                        errorMessage = context.getString(R.string.file_picker_load_failed),
                    ),
                )
            }
        }
    }

    private fun loadEntries(directory: File): List<FilePickerEntry> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            directory.absolutePath == Environment.getStorageDirectory().absolutePath
        ) {
            val storageManager = ContextCompat.getSystemService(context, StorageManager::class.java)
            val volumes = (storageManager?.storageVolumes ?: emptyList()).mapNotNull { volume ->
                val volumeDirectory = volume.directory ?: return@mapNotNull null
                FilePickerEntry(
                    path = volumeDirectory.absolutePath,
                    name = volume.getDescription(context) ?: volumeDirectory.name,
                    kind = FilePickerEntryKind.VOLUME,
                )
            }
            if (volumes.isNotEmpty()) {
                return volumes.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }
        }

        if (!directory.isDirectory || !directory.canRead()) {
            throw IOException("Unable to read ${directory.absolutePath}")
        }
        val files = directory.listFiles() ?: throw IOException("Unable to list ${directory.absolutePath}")
        return FilePickerRules.sortEntries(
            files.asList(),
            request.mode,
            request.allowExistingFile,
            root,
        )
    }

    private fun complete(files: List<File>) {
        val existing = files.filter { file ->
            try {
                file.exists() &&
                    FilePickerRules.isWithinRoot(file, root) &&
                    if (file.isDirectory) {
                        request.allowsDirectories
                    } else {
                        request.allowsFiles &&
                            file.isFile &&
                            FilePickerRules.isVisible(file, request.mode, request.allowExistingFile)
                    }
            } catch (_: SecurityException) {
                false
            }
        }
        if (existing.isNotEmpty()) {
            onFilesPicked(existing)
        } else {
            publish(
                state.copy(
                    selectedPaths = emptySet(),
                    loading = false,
                    errorMessage = context.getString(R.string.file_picker_selection_unavailable),
                ),
            )
        }
    }

    private fun execute(task: () -> Unit): Boolean {
        if (closed) {
            return false
        }
        try {
            executor.execute(task)
            return true
        } catch (_: RejectedExecutionException) {
            return false
        }
    }

    private fun publish(next: FilePickerState) {
        if (closed) {
            return
        }
        state = next
        onStateChanged(next)
    }

    private fun rootDirectory(): File = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.getStorageDirectory()
    } else {
        File(File.separator)
    }
}
