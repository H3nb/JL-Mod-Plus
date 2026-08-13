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

package ru.playsoftware.j2meloader.crashes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

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
    fun detailActionsInvokeCopyShareAndDeleteCallbacks() {
        val actions = RecordingDetailActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportDetailsScreen(
                    state = CrashReportDetailState("diagnostic details"),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Copy report").performClick()
        assertEquals(1, actions.copyCount)

        composeRule.onNodeWithContentDescription("Share report").performClick()
        composeRule.onAllNodesWithText("Share report").get(1).performClick()
        assertEquals(1, actions.shareCount)

        composeRule.onNodeWithContentDescription("Delete report").performClick()
        composeRule.onAllNodesWithText("Delete report").get(0).performClick()
        assertEquals(1, actions.deleteCount)
    }

    private class RecordingListActions : CrashReportsActions {
        var openedId: String? = null

        override fun onBack() = Unit

        override fun onOpen(reportId: String) {
            openedId = reportId
        }
    }

    private class RecordingDetailActions : CrashReportDetailsActions {
        var copyCount = 0
        var shareCount = 0
        var deleteCount = 0

        override fun onBack() = Unit

        override fun onCopy() {
            copyCount++
        }

        override fun onShare() {
            shareCount++
        }

        override fun onDelete() {
            deleteCount++
        }
    }
}
