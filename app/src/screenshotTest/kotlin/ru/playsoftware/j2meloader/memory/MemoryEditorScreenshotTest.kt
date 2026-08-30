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

package ru.playsoftware.j2meloader.memory

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private object NoOpMemoryEditorActions : MemoryEditorActions {
    override fun close() = Unit
    override fun refreshCapabilities() = Unit
    override fun startSearch(value: String, secondValue: String, type: Int, predicate: Int, unknown: Boolean, scope: Int) = Unit
    override fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int) = Unit
    override fun groupSearch(types: IntArray, values: Array<String>, distance: Int, scope: Int) = Unit
    override fun undo() = Unit
    override fun refresh() = Unit
    override fun setWatchTab(watch: Boolean) = Unit
    override fun toggleSelection(id: Long) = Unit
    override fun selectVisible() = Unit
    override fun invertVisible() = Unit
    override fun clearSelection() = Unit
    override fun editSelected(value: String, type: Int) = Unit
    override fun removeSelected(keep: Boolean) = Unit
    override fun watchSelected(add: Boolean) = Unit
    override fun labelWatch(id: Long, label: String) = Unit
    override fun freezeSelected(mode: Int, first: String, second: String) = Unit
    override fun clearFreezeSelected() = Unit
    override fun copySelected(addresses: Boolean) = Unit
    override fun previousPage() = Unit
    override fun nextPage() = Unit
    override fun cancel() = Unit
}

private val previewRows = listOf(
    MemoryCandidateRow(
        id = 1,
        address = 0x21B99C0,
        previousAddress = 0,
        type = MemoryEngineContract.TYPE_INT,
        state = MemoryEngineContract.CANDIDATE_STABLE,
        relocations = 0,
        initialBits = 500,
        previousBits = 500,
        currentBits = 500,
    ),
    MemoryCandidateRow(
        id = 2,
        address = 0x21B99C0,
        previousAddress = 0,
        type = MemoryEngineContract.TYPE_LONG,
        state = MemoryEngineContract.CANDIDATE_STABLE,
        relocations = 0,
        initialBits = 500,
        previousBits = 500,
        currentBits = 500,
    ),
    MemoryCandidateRow(
        id = 3,
        address = 0x73492150,
        previousAddress = 0x73482150,
        type = MemoryEngineContract.TYPE_INT,
        state = MemoryEngineContract.CANDIDATE_STABLE,
        relocations = 1,
        initialBits = 100,
        previousBits = 95,
        currentBits = 90,
    ),
)

@PreviewTest
@Preview(name = "Memory Editor portrait", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun MemoryEditorPortraitScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                resultCount = 2,
                results = previewRows,
                sessionStage = MemorySessionStage.CANDIDATES,
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}

@PreviewTest
@Preview(name = "Memory Editor landscape watch", widthDp = 720, heightDp = 360, showBackground = true)
@Composable
fun MemoryEditorLandscapeWatchScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                watchTab = true,
                watches = listOf(
                    previewRows.last().copy(
                        label = "HP",
                        freezeMode = MemoryEngineContract.FREEZE_LOCK,
                        freezePaused = true,
                    ),
                ),
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}

@PreviewTest
@Preview(name = "Memory Editor landscape results", widthDp = 720, heightDp = 360, showBackground = true)
@Composable
fun MemoryEditorLandscapeResultsScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorScreen(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                resultCount = 2,
                results = previewRows,
                sessionStage = MemorySessionStage.CANDIDATES,
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}
