/*
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

package io.github.h3nb.jlmodplus.crashes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class CrashReportsComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listExposesLoadingState() {
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = CrashReportsListState(loading = true, records = emptyList()),
                    actions = RecordingListActions(),
                )
            }
        }
        composeRule.onNodeWithContentDescription("Loading crash reports").assertIsDisplayed()
    }

    @Test
    fun listExposesEmptyState() {
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = CrashReportsListState(loading = false, records = emptyList()),
                    actions = RecordingListActions(),
                )
            }
        }
        composeRule.onNodeWithText("No local crash reports").assertIsDisplayed()
    }

    @Test
    fun listExposesContentAndOpensReport() {
        val actions = RecordingListActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = CrashReportsListState(
                        loading = false,
                        records = listOf(
                            CrashReportListItem(
                                id = "report-1",
                                title = "Demo MIDlet",
                                subtitle = "MIDlet session failure · today",
                            ),
                        ),
                    ),
                    actions = actions,
                )
            }
        }
        composeRule.onNodeWithText("Demo MIDlet").performClick()
        assertEquals("report-1", actions.openedId)
    }

    @Test
    fun listLongPressEntersBatchSelectionAndInvokesActions() {
        val actions = RecordingListActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = CrashReportsListState(
                        loading = false,
                        records = listOf(
                            CrashReportListItem(
                                id = "report-1",
                                title = "Demo MIDlet",
                                subtitle = "MIDlet session failure · today",
                            ),
                            CrashReportListItem(
                                id = "report-2",
                                title = "Other MIDlet",
                                subtitle = "Process exit diagnostic · today",
                            ),
                        ),
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Demo MIDlet").performTouchInput { longClick() }
        composeRule.onNodeWithText("1 Selected").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Copy Selected Reports").performClick()
        assertEquals(listOf("report-1"), actions.copiedIds)

        composeRule.onNodeWithText("Other MIDlet").performClick()
        composeRule.onNodeWithText("2 Selected").assertIsDisplayed()

        assertEquals(
            0,
            composeRule.onAllNodesWithText("Share selected reports").fetchSemanticsNodes().size,
        )

        composeRule.onNodeWithContentDescription("Delete Selected Reports").performClick()
        composeRule.onNodeWithText("Delete Selected Reports").performClick()
        assertEquals(listOf("report-1", "report-2"), actions.deletedIds)
    }

    @Test
    fun batchSelectionCanSelectAllAndClearSelection() {
        val actions = RecordingListActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = CrashReportsListState(
                        loading = false,
                        records = listOf(
                            CrashReportListItem("report-1", "Demo MIDlet", "MIDlet failure"),
                            CrashReportListItem("report-2", "Other MIDlet", "Process exit"),
                        ),
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Demo MIDlet").performTouchInput { longClick() }
        composeRule.onNodeWithContentDescription("Select All Reports").performClick()
        composeRule.onNodeWithText("2 Selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Clear Report Selection").performClick()
        composeRule.onNodeWithText("Diagnostic Reports").assertIsDisplayed()
    }

    @Test
    fun bundleReadyDialogInvokesLocateAndOpenActions() {
        val actions = RecordingDetailActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportDetailsScreen(
                    state = CrashReportDetailState(
                        displayText = "diagnostic details",
                        readyBundle = DiagnosticBundleReadyState("20260926-134500-012-deadbeef.zip"),
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Diagnostic Bundle Ready").assertIsDisplayed()
        composeRule.onNodeWithText("Locate Bundle").performClick()
        assertEquals(1, actions.locateCount)
        composeRule.onNodeWithText("Open GitHub").performClick()
        assertEquals(1, actions.openGitHubCount)
    }

    @Test
    fun detailActionsExposeOnlyCopyGitHubAndDeleteCallbacks() {
        val actions = RecordingDetailActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportDetailsScreen(
                    state = CrashReportDetailState("diagnostic details"),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Diagnostic Report").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Copy").performClick()
        assertEquals(1, actions.copyCount)

        assertEquals(
            0,
            composeRule.onAllNodesWithText("Share Report").fetchSemanticsNodes().size,
        )

        composeRule.onNodeWithContentDescription("Report on GitHub").performClick()
        assertEquals(1, actions.githubCount)

        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onAllNodesWithText("Delete").get(0).performClick()
        assertEquals(1, actions.deleteCount)
    }

    private class RecordingListActions : CrashReportsActions {
        var openedId: String? = null
        var copiedIds: List<String> = emptyList()
        var deletedIds: List<String> = emptyList()

        override fun onBack() = Unit

        override fun onOpen(reportId: String) {
            openedId = reportId
        }

        override fun onCopySelected(reportIds: List<String>) {
            copiedIds = reportIds
        }

        override fun onDeleteSelected(reportIds: List<String>) {
            deletedIds = reportIds
        }
    }

    private class RecordingDetailActions : CrashReportDetailsActions {
        var copyCount = 0
        var githubCount = 0
        var locateCount = 0
        var openGitHubCount = 0
        var deleteCount = 0

        override fun onBack() = Unit

        override fun onCopy() {
            copyCount++
        }

        override fun onReportGitHub() {
            githubCount++
        }

        override fun onDismissBundleReady() = Unit

        override fun onLocateBundle() {
            locateCount++
        }

        override fun onOpenGitHub() {
            openGitHubCount++
        }

        override fun onDelete() {
            deleteCount++
        }
    }
}
