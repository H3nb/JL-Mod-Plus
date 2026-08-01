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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
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
    fun compactHeaderKeepsNeutralStatusAndAccessibleCloseAction() {
        var closed = false
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(),
                    actions = MemoryEditorActions(),
                    onClose = { closed = true },
                )
            }
        }

        composeRule.onNodeWithTag("memory_experimental_badge").assertExists()
        composeRule.onNodeWithTag("memory_close").performClick()
        composeRule.runOnIdle { assertEquals(true, closed) }
    }

    @Test
    fun autoIsSelectedByDefaultWhileManualTypesRemainAvailable() {
        var selected: MemoryEditorRuntime.SearchType? = null
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(),
                    actions = MemoryEditorActions(setKind = { selected = it }),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithText("Auto (numeric types)").assertExists()
        composeRule.onNodeWithTag("memory_type").performClick()
        composeRule.onNodeWithText("Integer (int)").assertExists()
        composeRule.onNodeWithText("High-precision decimal (double)").assertExists()
        composeRule.onNodeWithText("Integer (int)").performClick()
        composeRule.runOnIdle {
            assertEquals(MemoryEditorRuntime.SearchType.INT, selected)
        }
    }

    @Test
    fun comparisonSearchOpensAValueDialogWithPermanentKeypad() {
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(
                        initialMode = MemoryEditorRuntime.SearchMode.LESS_THAN,
                    ),
                    actions = MemoryEditorActions(),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_value").performClick()
        composeRule.onNodeWithTag("memory_maximum").assertDoesNotExist()
        composeRule.onNodeWithTag("memory_input_dialog_value").assertExists()
        composeRule.onAllNodesWithTag("memory_input_dialog_value").assertCountEquals(1)
        composeRule.onNodeWithTag("memory_numeric_keypad").assertExists()
        composeRule.onNodeWithText("HEX").assertDoesNotExist()
    }

    @Test
    fun preparingSearchUsesPopupWithoutShowingTheOldCollectionScreen() {
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(
                        phase = MemoryEditorPhase.COLLECTING,
                        preparingSearch = true,
                    ),
                    actions = MemoryEditorActions(),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_preparing_search").assertExists()
        composeRule.onNodeWithTag("memory_finish").assertDoesNotExist()
    }

    @Test
    fun collectingPhaseShowsStatusInsteadOfBlankContent() {
        var closed = false
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(phase = MemoryEditorPhase.COLLECTING),
                    actions = MemoryEditorActions(),
                    onClose = { closed = true },
                )
            }
        }

        composeRule.onNodeWithTag("memory_collecting_status").assertExists()
        composeRule.onNodeWithText("Collecting while the game runs").assertExists()
        composeRule.onNodeWithText("Return to game").performClick()
        composeRule.runOnIdle { assertEquals(true, closed) }
    }

    @Test
    fun finishedInitialSearchCanContinueCollectingFromResults() {
        var continued = false
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(
                        phase = MemoryEditorPhase.RESULTS,
                        snapshot = MemoryEditorSnapshot(
                            searchSessionId = 7,
                            searchType = MemoryEditorRuntime.SearchType.INT,
                            mode = MemoryEditorRuntime.SearchMode.UNKNOWN,
                            collecting = false,
                        ),
                    ),
                    actions = MemoryEditorActions(
                        continueCollection = { continued = true },
                    ),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_continue_collection").performClick()
        composeRule.runOnIdle { assertEquals(true, continued) }
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
                            searchType = MemoryEditorRuntime.SearchType.INT,
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

    @Test
    fun resultsScrollIncludesActionToolbarInACompactWindow() {
        composeRule.setContent {
            Box(Modifier.requiredSize(760.dp, 420.dp)) {
                MemoryEditorTheme {
                    MemoryEditorScreen(
                        state = MemoryEditorUiState(
                            phase = MemoryEditorPhase.RESULTS,
                            snapshot = MemoryEditorSnapshot(
                                candidates = 1,
                                searchType = MemoryEditorRuntime.SearchType.INT,
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
                            selectedIds = setOf(7),
                        ),
                        actions = MemoryEditorActions(),
                        onClose = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("memory_results").performScrollToIndex(4)
        composeRule.onNodeWithTag("memory_edit_selected").assertIsEnabled()
    }

    @Test
    fun savedTabSupportsSelectingAndDeletingAnUnfrozenWatch() {
        val selected = mutableStateOf(false)
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(
                        phase = MemoryEditorPhase.RESULTS,
                        snapshot = MemoryEditorSnapshot(
                            candidates = 1,
                            saved = 1,
                            searchType = MemoryEditorRuntime.SearchType.INT,
                        ),
                        savedCandidates = listOf(
                            MemoryCandidate(
                                id = 9,
                                value = "750",
                                storageType = "int",
                                location = "game.Player.coins",
                                frozen = false,
                                saved = true,
                                editable = true,
                            ),
                        ),
                        selectedIds = if (selected.value) setOf(9) else emptySet(),
                    ),
                    actions = MemoryEditorActions(
                        toggleSelection = { selected.value = true },
                    ),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_tab_saved").performClick()
        composeRule.onNodeWithTag("memory_delete_saved").assertIsNotEnabled()
        composeRule.onNodeWithTag("memory_candidate_9").performClick()
        composeRule.onNodeWithTag("memory_delete_saved").assertIsEnabled()
    }

    @Test
    fun pauseIsOffByDefaultInSettings() {
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(),
                    actions = MemoryEditorActions(),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_tab_settings").performClick()
        composeRule.onNodeWithTag("memory_pause").assertIsOff()
        composeRule.onNodeWithTag("memory_layout_transparency").assertExists()
    }

    @Test
    fun holdToViewGameReportsPressAndRelease() {
        val events = mutableListOf<Boolean>()
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(layoutTransparency = 0.4f),
                    actions = MemoryEditorActions(setPeeking = events::add),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_peek").performTouchInput {
            down(center)
            advanceEventTime(600)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(true, false), events)
        }
    }

    @Test
    fun peekActivatesBeforeThePlatformDefaultLongPressDelay() {
        val events = mutableListOf<Boolean>()
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(),
                    actions = MemoryEditorActions(setPeeking = events::add),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_peek").performTouchInput {
            down(center)
            advanceEventTime(350)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(listOf(true, false), events)
        }
    }

    @Test
    fun shortTapDoesNotActivateGamePeek() {
        val events = mutableListOf<Boolean>()
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(),
                    actions = MemoryEditorActions(setPeeking = events::add),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_peek").performTouchInput {
            down(center)
            advanceEventTime(150)
            up()
        }

        composeRule.runOnIdle {
            assertEquals(emptyList<Boolean>(), events)
        }
    }

    @Test
    fun landscapeInputDialogKeepsItsInternalKeypadVisible() {
        composeRule.setContent {
            Box(Modifier.requiredSize(760.dp, 420.dp)) {
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
        }

        composeRule.onNodeWithTag("memory_value").performClick()
        composeRule.onNodeWithTag("memory_input_dialog_value").assertExists()
        composeRule.onNodeWithTag("memory_numeric_keypad").assertExists()
    }

    @Test
    fun searchAndResultsUseOneTab() {
        composeRule.setContent {
            MemoryEditorTheme {
                MemoryEditorScreen(
                    state = MemoryEditorUiState(phase = MemoryEditorPhase.RESULTS),
                    actions = MemoryEditorActions(),
                    onClose = {},
                )
            }
        }

        composeRule.onNodeWithTag("memory_tab_search").assertExists()
        composeRule.onNodeWithTag("memory_tab_results").assertDoesNotExist()
        composeRule.onNodeWithTag("memory_results").assertExists()
    }
}
