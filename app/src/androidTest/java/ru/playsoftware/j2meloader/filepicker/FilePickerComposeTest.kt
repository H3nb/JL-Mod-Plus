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

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class FilePickerComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun searchAndSortControlsAreExposed() {
        val events = AtomicReference<String>("")
        composeRule.setContent {
            var state by remember { mutableStateOf(sampleState()) }
            val actions = remember {
                object : RecordingActions(events) {
                    override fun onToggleSearch() {
                        state = state.copy(searchVisible = !state.searchVisible)
                        super.onToggleSearch()
                    }

                    override fun onSortOrderSelected(sortOrder: FilePickerSortOrder) {
                        state = state.copy(sortOrder = sortOrder)
                        super.onSortOrderSelected(sortOrder)
                    }
                }
            }
            JLModPlusTheme {
                FilePickerScreen(
                    state = state,
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search").performClick()
        composeRule.onNodeWithText("Search").assertExists()
        composeRule.onNodeWithContentDescription("Sort").performClick()
        composeRule.onNodeWithText("Name A–Z").assertExists()
        composeRule.onNodeWithText("Modified Newest").assertExists()
        composeRule.onNodeWithText("zeta.jar").assertExists()
        composeRule.onNodeWithText("Modified Newest").performClick()
        assertEquals("sort:MODIFIED_NEWEST", events.get())
    }

    @Test
    fun directoryAndFileRowsExposeActions() {
        val events = AtomicReference<String>("")
        composeRule.setContent {
            JLModPlusTheme {
                FilePickerScreen(
                    state = sampleState(),
                    actions = RecordingActions(events),
                )
            }
        }

        composeRule.onNodeWithText("Games").performClick()
        assertEquals("open:Games", events.get())
    }

    @Test
    fun directoryModeOffersCurrentFolderAsTheSelection() {
        val events = AtomicReference<String>("")
        val request = FilePickerRequest(
            startPath = "/storage/emulated/0",
            mode = FilePickerContract.MODE_DIR,
            allowMultiple = false,
            singleClick = false,
            allowCreateDirectory = true,
            allowExistingFile = false,
        )
        composeRule.setContent {
            JLModPlusTheme {
                FilePickerScreen(
                    state = sampleState().copy(request = request),
                    actions = RecordingActions(events),
                )
            }
        }

        composeRule.onNodeWithText("Current Folder").assertExists()
        composeRule.onNodeWithText("Choose").assertIsEnabled().performClick()
        assertEquals("confirm", events.get())
    }

    @Test
    fun navHostReflectsLoadedStateAfterInitialLoading() {
        val events = AtomicReference<String>("")
        lateinit var publishState: (FilePickerState) -> Unit
        composeRule.setContent {
            var state by remember {
                mutableStateOf(sampleState().copy(loading = true, entries = emptyList()))
            }
            publishState = { next -> state = next }
            JLModPlusTheme {
                FilePickerNavHost(
                    state = state,
                    actions = RecordingActions(events),
                )
            }
        }

        publishState(sampleState())
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Games").assertExists()
    }

    @Test
    fun parentNavigationAndPickerExitAreDistinctActions() {
        val events = AtomicReference<String>("")
        composeRule.setContent {
            JLModPlusTheme {
                FilePickerScreen(
                    state = sampleState(),
                    actions = RecordingActions(events),
                )
            }
        }

        composeRule.onNodeWithText("Choose").assertExists()
        composeRule.onNodeWithContentDescription("Back to Parent Folder").performClick()
        assertEquals("back", events.get())
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals("exit", events.get())

        composeRule.setContent {
            JLModPlusTheme {
                FilePickerScreen(
                    state = sampleState().copy(currentPath = "/storage"),
                    actions = RecordingActions(events),
                )
            }
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals("exit", events.get())
    }

    private fun sampleState() = FilePickerState(
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
            FilePickerEntry("/storage/emulated/0/zeta.jar", "zeta.jar", FilePickerEntryKind.FILE),
        ),
    )

    private open class RecordingActions(private val events: AtomicReference<String>) : FilePickerActions {
        override fun onNavigateBack() = events.set("back")
        override fun onExit() = events.set("exit")
        override fun onOpen(entry: FilePickerEntry) = events.set("open:${entry.name}")
        override fun onConfirmSelection() = events.set("confirm")
        override fun onToggleSearch() = events.set("search")
        override fun onSearchQueryChanged(query: String) = events.set("query:$query")
        override fun onSortOrderSelected(sortOrder: FilePickerSortOrder) = events.set("sort:$sortOrder")
        override fun onGrantPermission() = events.set("permission")
        override fun onRetry() = events.set("retry")
        override fun onShowCreateFolder() = events.set("create")
        override fun onDismissCreateFolder() = events.set("dismiss")
        override fun onCreateFolderNameChanged(name: String) = events.set("name:$name")
        override fun onCreateFolder() = events.set("create-confirm")
    }
}
