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

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat
import ru.playsoftware.j2meloader.util.StoragePermissionHelper
import java.io.File

/**
 * App-owned file picker Activity. The class name remains stable for existing
 * manifest and ActivityResult callers while its presentation and file-system
 * coordination are fully independent of the former picker library.
 */
class FilteredFilePickerActivity : AppCompatActivity() {
    private lateinit var controller: FilePickerController
    private var pickerState by mutableStateOf<FilePickerState?>(null)
    private var lastBackPressAt = 0L

    private val storagePermissionHelper = StoragePermissionHelper(this) { granted ->
        if (::controller.isInitialized) {
            controller.onPermissionResult(granted)
            if (!granted) {
                Toast.makeText(
                    this,
                    R.string.file_picker_permission_denied,
                    Toast.LENGTH_SHORT,
                ).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
    }

    override fun onCreate(@Nullable savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdgeCompat.enableIfSupported(this)

        val request = FilePickerRequest.fromIntent(intent)
        val restoredPath = savedInstanceState?.getString(STATE_CURRENT_PATH)
        val restoredSelection = savedInstanceState
            ?.getStringArrayList(STATE_SELECTED_PATHS)
            ?.toSet()
            .orEmpty()
        val restoredSortOrder = savedInstanceState
            ?.getString(STATE_SORT_ORDER)
            ?.let { name -> runCatching { FilePickerSortOrder.valueOf(name) }.getOrNull() }
            ?: FilePickerSortOrder.TYPE_THEN_NAME

        controller = FilePickerController(
            context = this,
            request = request,
            restoredPath = restoredPath ?: request.startPath,
            restoredSelection = restoredSelection,
            restoredSearchVisible = savedInstanceState?.getBoolean(STATE_SEARCH_VISIBLE) ?: false,
            restoredSearchQuery = savedInstanceState?.getString(STATE_SEARCH_QUERY).orEmpty(),
            restoredSortOrder = restoredSortOrder,
            onStateChanged = { pickerState = it },
            onFilesPicked = ::finishWithFiles,
        )
        pickerState = controller.currentState()

        val composeView = ComposeView(this).apply {
            id = R.id.file_picker_compose_root
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state = pickerState ?: return@setContent
                JLModPlusTheme {
                    FilePickerNavHost(state = state, actions = createActions())
                }
            }
        }
        setContentView(composeView)
        supportActionBar?.hide()
        EdgeToEdgeCompat.protectHostContent(this)

    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::controller.isInitialized) {
            val state = controller.currentState()
            outState.putString(STATE_CURRENT_PATH, state.currentPath)
            outState.putStringArrayList(
                STATE_SELECTED_PATHS,
                ArrayList(state.selectedPaths),
            )
            outState.putBoolean(STATE_SEARCH_VISIBLE, state.searchVisible)
            outState.putString(STATE_SEARCH_QUERY, state.searchQuery)
            outState.putString(STATE_SORT_ORDER, state.sortOrder.name)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::controller.isInitialized) {
            controller.close()
        }
        super.onDestroy()
    }

    private fun createActions(): FilePickerActions = object : FilePickerActions {
        override fun onNavigateBack() = handleBack()

        override fun onExit() = exitPicker()

        override fun onOpen(entry: FilePickerEntry) = controller.open(entry)

        override fun onConfirmSelection() = controller.confirmSelection()

        override fun onToggleSearch() = controller.toggleSearch()

        override fun onSearchQueryChanged(query: String) = controller.setSearchQuery(query)

        override fun onSortOrderSelected(sortOrder: FilePickerSortOrder) =
            controller.setSortOrder(sortOrder)

        override fun onGrantPermission() {
            controller.requestPermission()
            storagePermissionHelper.launch(this@FilteredFilePickerActivity)
        }

        override fun onRetry() {
            controller.retry()
            if (!StoragePermissionHelper.isGranted(this@FilteredFilePickerActivity)) {
                storagePermissionHelper.launch(this@FilteredFilePickerActivity)
            }
        }

        override fun onShowCreateFolder() = controller.showCreateFolder()

        override fun onDismissCreateFolder() = controller.dismissCreateFolder()

        override fun onCreateFolderNameChanged(name: String) = controller.setCreateFolderName(name)

        override fun onCreateFolder() = controller.createFolder()
    }

    private fun handleBack() {
        if (controller.navigateBack()) {
            // A directory navigation is not an exit attempt. Do not let a back
            // press from the previous screen count toward the root double-back
            // confirmation after returning here.
            lastBackPressAt = 0L
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBackPressAt <= BACK_PRESS_WINDOW_MS) {
            finish()
            return
        }
        lastBackPressAt = now
        Toast.makeText(this, R.string.msg_press_again_to_close, Toast.LENGTH_SHORT).show()
    }

    private fun exitPicker() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun finishWithFiles(files: List<File>) {
        if (files.isEmpty()) {
            return
        }
        val result = createFilePickerResult(this, controller.request, files)
        setResult(RESULT_OK, result)
        finish()
    }

    companion object {
        private const val STATE_CURRENT_PATH = "file_picker.current_path"
        private const val STATE_SELECTED_PATHS = "file_picker.selected_paths"
        private const val STATE_SEARCH_VISIBLE = "file_picker.search_visible"
        private const val STATE_SEARCH_QUERY = "file_picker.search_query"
        private const val STATE_SORT_ORDER = "file_picker.sort_order"
        private const val BACK_PRESS_WINDOW_MS = 1_500L
    }
}

/** Builds the legacy-compatible raw-path result without depending on the old picker library. */
internal fun createFilePickerResult(
    context: Context,
    request: FilePickerRequest,
    files: List<File>,
): Intent {
    if (files.isEmpty()) {
        return Intent()
    }
    val uris = files.map(Uri::fromFile)
    return Intent().apply {
        if (request.allowMultiple) {
            putExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, true)
            putStringArrayListExtra(
                FilePickerContract.EXTRA_PATHS,
                ArrayList(uris.map(Uri::toString)),
            )
            var selected = ClipData.newUri(context.contentResolver, "Paths", uris.first())
            uris.drop(1).forEach { selected = selected.apply { addItem(ClipData.Item(it)) } }
            clipData = selected
        } else {
            data = uris.first()
        }
    }
}
