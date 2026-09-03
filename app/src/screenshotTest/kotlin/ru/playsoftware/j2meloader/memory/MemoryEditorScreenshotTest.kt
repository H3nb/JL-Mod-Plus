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
    MemoryResultRow(
        id = 1,
        valueText = "500",
        addressText = "0x021B99C0",
        aliasMask = 1 shl MemoryEngineContract.TYPE_INT,
        primaryType = MemoryEngineContract.TYPE_INT,
        state = MemoryEngineContract.CANDIDATE_STABLE,
        relocations = 0,
    ),
    MemoryResultRow(
        id = 2,
        valueText = "500",
        addressText = "0x021B99C0",
        aliasMask = 1 shl MemoryEngineContract.TYPE_LONG,
        primaryType = MemoryEngineContract.TYPE_LONG,
        state = MemoryEngineContract.CANDIDATE_STABLE,
        relocations = 0,
    ),
    MemoryResultRow(
        id = 3,
        valueText = "90",
        addressText = "0x73492150",
        aliasMask = 1 shl MemoryEngineContract.TYPE_INT,
        primaryType = MemoryEngineContract.TYPE_INT,
        state = MemoryEngineContract.CANDIDATE_STABLE,
        relocations = 1,
    ),
)

private val previewWatch = MemoryWatchRow(
    id = 3,
    type = MemoryEngineContract.TYPE_INT,
    state = MemoryEngineContract.CANDIDATE_STABLE,
    relocations = 1,
    valueText = "90",
    initialValueText = "100",
    previousValueText = "95",
    addressText = "0x73492150",
    label = "HP",
    freezeMode = MemoryEngineContract.FREEZE_LOCK,
    freezePaused = true,
)

@PreviewTest
@Preview(name = "Memory Editor portrait", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun MemoryEditorPortraitScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorRuntimeRoot(
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
        MemoryEditorRuntimeRoot(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                watchTab = true,
                watches = listOf(previewWatch),
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
        MemoryEditorRuntimeRoot(
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
@Preview(name = "Memory Editor short landscape search", widthDp = 640, heightDp = 320, showBackground = true)
@Composable
fun MemoryEditorShortLandscapeSearchScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorRuntimeRoot(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                sessionStage = MemorySessionStage.EMPTY,
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}

private val previewInspectorSnapshot = MemoryInspectorSnapshot(
    candidateId = 1,
    type = MemoryEngineContract.TYPE_INT,
    label = "HP",
    startAddress = 0x21B9980,
    anchorAddress = 0x21B99C0,
    bytes = ByteArray(128) { index -> (index * 3 + 7).toByte() },
)

@PreviewTest
@Preview(name = "Memory Inspector compact", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun MemoryInspectorCompactScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorRuntimeRoot(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                inspector = previewInspectorSnapshot,
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}

@PreviewTest
@Preview(name = "Memory Inspector wide", widthDp = 840, heightDp = 480, showBackground = true)
@Composable
fun MemoryInspectorWideScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        MemoryEditorRuntimeRoot(
            state = MemoryEditorUiState(
                bubbleEnabled = true,
                visible = true,
                connected = true,
                supported = true,
                writeSupported = true,
                runtimeToken = 1,
                inspector = previewInspectorSnapshot,
            ),
            actions = NoOpMemoryEditorActions,
        )
    }
}
