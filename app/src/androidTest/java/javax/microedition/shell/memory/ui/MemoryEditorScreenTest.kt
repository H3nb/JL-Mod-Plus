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

package javax.microedition.shell.memory.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import org.junit.Rule
import org.junit.Test
import javax.microedition.shell.memory.MemoryEditorRuntime

class MemoryEditorScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exactInitialSearchDoesNotShowAnIrrelevantMaximumField() {
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(
                        initialMode = MemoryEditorRuntime.SearchMode.EXACT,
                    ),
                    actions = MemoryEditorActions(),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_value").assertExists()
        composeRule.onNodeWithTag("memory_maximum").assertDoesNotExist()
    }

    @Test
    fun selectingAVisibleCandidateEnablesSelectedEditAction() {
        val selected = mutableStateOf(false)
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(
                        phase = MemoryEditorPhase.RESULTS,
                        snapshot = MemoryEditorSnapshot(
                            candidates = 1,
                            kind = MemoryEditorRuntime.ValueKind.INT,
                        ),
                        candidates = listOf(
                            MemoryCandidate(
                                id = 7,
                                value = "750",
                                storageType = "int",
                                location = "game.Player.coins",
                                frozen = false,
                                editable = true,
                            ),
                        ),
                        selectedIds = if (selected.value) setOf(7) else emptySet(),
                    ),
                    actions = MemoryEditorActions(
                        toggleSelection = { selected.value = true },
                    ),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_edit_selected").assertIsNotEnabled()
        composeRule.onNodeWithTag("memory_candidate_7").performClick()
        composeRule.onNodeWithTag("memory_edit_selected").assertIsEnabled()
    }
}
