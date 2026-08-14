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

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val PreviewActions = object : FilePickerActions {
    override fun onNavigateBack() = Unit
    override fun onExit() = Unit
    override fun onOpen(entry: FilePickerEntry) = Unit
    override fun onConfirmSelection() = Unit
    override fun onToggleSearch() = Unit
    override fun onSearchQueryChanged(query: String) = Unit
    override fun onSortOrderSelected(sortOrder: FilePickerSortOrder) = Unit
    override fun onGrantPermission() = Unit
    override fun onRetry() = Unit
    override fun onShowCreateFolder() = Unit
    override fun onDismissCreateFolder() = Unit
    override fun onCreateFolderNameChanged(name: String) = Unit
    override fun onCreateFolder() = Unit
}

private val PreviewState = FilePickerState(
    request = FilePickerRequest(
        startPath = "/storage/emulated/0",
        mode = FilePickerContract.MODE_FILE,
        allowMultiple = false,
        singleClick = true,
        allowCreateDirectory = false,
        allowExistingFile = false,
    ),
    rootPath = "/storage",
    currentPath = "/storage/emulated/0",
    entries = listOf(
        FilePickerEntry("/storage/emulated/0/Games", "Games", FilePickerEntryKind.DIRECTORY),
        FilePickerEntry("/storage/emulated/0/Downloads", "Downloads", FilePickerEntryKind.DIRECTORY),
        FilePickerEntry("/storage/emulated/0/Example.jar", "Example.jar", FilePickerEntryKind.FILE),
        FilePickerEntry("/storage/emulated/0/Example.jad", "Example.jad", FilePickerEntryKind.FILE),
    ),
)

@PreviewTest
@Preview(name = "File picker light", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun FilePickerLightScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        FilePickerScreen(state = PreviewState, actions = PreviewActions)
    }
}

@PreviewTest
@Preview(
    name = "File picker dark search",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun FilePickerDarkSearchScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        FilePickerScreen(
            state = PreviewState.copy(searchVisible = true, searchQuery = "jar"),
            actions = PreviewActions,
        )
    }
}

@PreviewTest
@Preview(name = "File picker permission", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun FilePickerPermissionScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        FilePickerScreen(
            state = PreviewState.copy(
                permissionRequired = true,
                entries = emptyList(),
            ),
            actions = PreviewActions,
        )
    }
}

@PreviewTest
@Preview(name = "File picker root", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun FilePickerRootScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        FilePickerScreen(
            state = PreviewState.copy(
                rootPath = "/storage",
                currentPath = "/storage",
            ),
            actions = PreviewActions,
        )
    }
}

@PreviewTest
@Preview(name = "File picker wide grid", widthDp = 840, heightDp = 480, showBackground = true)
@Composable
fun FilePickerWideGridScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        FilePickerScreen(
            state = PreviewState,
            actions = PreviewActions,
        )
    }
}

@PreviewTest
@Preview(name = "File picker landscape", widthDp = 640, heightDp = 360, showBackground = true)
@Composable
fun FilePickerLandscapeScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        FilePickerScreen(
            state = PreviewState.copy(
                searchVisible = true,
                searchQuery = "jar",
                selectedPaths = setOf("/storage/emulated/0/Example.jar"),
            ),
            actions = PreviewActions,
        )
    }
}
