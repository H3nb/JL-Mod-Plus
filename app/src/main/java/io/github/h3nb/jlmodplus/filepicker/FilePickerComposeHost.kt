/*
 * Copyright 2026 H3NB
 *
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

package io.github.h3nb.jlmodplus.filepicker

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import java.io.File

/** Java-callable callbacks for the file-system and result owner. */
interface FilePickerCallbacks {
    fun onNavigateUp()
    fun onEntryClick(file: File)
    fun onEntryLongClick(file: File): Boolean
    fun onToggleSelection(file: File, checked: Boolean)
    fun onCancel()
    fun onChoose()
    fun onCreateDirectory()
    fun onCreateDirectoryConfirmed(name: String)
}

class FilePickerUiState {
    var currentPathLabel by mutableStateOf("")
        private set
    var hasParent by mutableStateOf(false)
        private set
    var entries by mutableStateOf<List<File>>(emptyList())
        private set
    var statusText by mutableStateOf("")
        private set
    var loading by mutableStateOf(false)
        private set
    var selectedPathsState by mutableStateOf<Set<String>>(emptySet())
        private set
    var chooseEnabled by mutableStateOf(false)
        private set
    var allowMultiple by mutableStateOf(false)
        private set
    var allowCreateDir by mutableStateOf(false)
        private set
    var createFolderVisible by mutableStateOf(false)
        private set
    var createFolderNameState by mutableStateOf("")
        private set
    var createFolderErrorState by mutableStateOf<String?>(null)
        private set

    fun configure(allowMultiple: Boolean, allowCreateDir: Boolean) {
        this.allowMultiple = allowMultiple
        this.allowCreateDir = allowCreateDir
    }

    fun setChrome(path: File?, hasParent: Boolean, canChoose: Boolean) {
        currentPathLabel = path?.path.orEmpty()
        this.hasParent = hasParent
        chooseEnabled = canChoose
    }

    fun setLoading() {
        loading = true
        statusText = ""
    }

    fun setEntries(entries: List<File>, statusText: String) {
        loading = false
        this.entries = entries.toList()
        this.statusText = statusText
    }

    fun setSelectedPaths(paths: Set<String>) {
        selectedPathsState = paths.toSet()
    }

    fun showCreateFolderDialog() {
        createFolderNameState = ""
        createFolderErrorState = null
        createFolderVisible = true
    }

    fun setCreateFolderError(error: String) {
        createFolderErrorState = error
    }

    fun setCreateFolderName(name: String) {
        createFolderNameState = name
        createFolderErrorState = null
    }

    fun hideCreateFolderDialog() {
        createFolderVisible = false
        createFolderErrorState = null
    }

    @Composable
    fun Render(callbacks: FilePickerCallbacks) {
        AppComposeTheme {
            FilePickerScreen(this, callbacks)
        }
    }
}

object FilePickerComposeHost {
    @JvmStatic
    fun install(
        activity: ComponentActivity,
        state: FilePickerUiState,
        callbacks: FilePickerCallbacks,
    ) {
        activity.setContent { state.Render(callbacks) }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerScreen(state: FilePickerUiState, callbacks: FilePickerCallbacks) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                modifier = Modifier.statusBarsPadding(),
                title = { Text(state.currentPathLabel) },
                navigationIcon = if (state.hasParent) {
                    {
                        val description = stringResource(R.string.file_picker_up)
                        IconButton(
                            modifier = Modifier.semantics {
                                contentDescription = description
                            },
                            onClick = callbacks::onNavigateUp,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_upward_24),
                                contentDescription = null,
                            )
                        }
                    }
                } else {
                    {}
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                TextButton(onClick = callbacks::onCancel) {
                    Text(stringResource(android.R.string.cancel))
                }
                if (state.allowCreateDir) {
                    TextButton(onClick = callbacks::onCreateDirectory) {
                        Text(stringResource(R.string.file_picker_new_folder))
                    }
                }
                Button(
                    enabled = state.chooseEnabled,
                    onClick = callbacks::onChoose,
                ) {
                    Text(stringResource(R.string.choose))
                }
            }
        },
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.loading) {
                Text(
                    text = stringResource(R.string.file_picker_loading),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                if (state.hasParent) {
                    item(key = "__parent__") {
                        FilePickerItemContent(
                            title = "..",
                            isDirectory = true,
                            checkable = false,
                            checked = false,
                            enabled = true,
                            onClick = callbacks::onNavigateUp,
                            onLongClick = { false },
                            onCheckboxClick = {},
                        )
                    }
                }
                items(
                    items = state.entries,
                    key = { FilePickerModel.canonicalFile(it).path },
                ) { file ->
                    val key = FilePickerModel.canonicalFile(file).path
                    val selectable = file.isDirectory() || file.canRead()
                    FilePickerItemContent(
                        title = file.name,
                        isDirectory = file.isDirectory,
                        checkable = state.allowMultiple && file.isFile,
                        checked = key in state.selectedPathsState,
                        enabled = selectable,
                        onClick = { callbacks.onEntryClick(file) },
                        onLongClick = { callbacks.onEntryLongClick(file) },
                        onCheckboxClick = { checked -> callbacks.onToggleSelection(file, checked) },
                    )
                }
            }
            if (!state.loading && state.statusText.isNotEmpty()) {
                Text(
                    text = state.statusText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (state.createFolderVisible) {
        AlertDialog(
            onDismissRequest = state::hideCreateFolderDialog,
            title = { Text(stringResource(R.string.file_picker_create_folder_title)) },
            text = {
                TextField(
                    value = state.createFolderNameState,
                    onValueChange = {
                        state.setCreateFolderName(it)
                    },
                    label = { Text(stringResource(R.string.file_picker_folder_name_hint)) },
                    supportingText = state.createFolderErrorState?.let { error ->
                        { Text(error) }
                    },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    callbacks.onCreateDirectoryConfirmed(state.createFolderNameState)
                }) {
                    Text(stringResource(R.string.file_picker_create))
                }
            },
            dismissButton = {
                TextButton(onClick = state::hideCreateFolderDialog) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
