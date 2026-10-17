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

import android.content.Intent
import android.net.Uri
import io.github.h3nb.jlmodplus.R
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

private fun uiString(resId: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

private fun uiQuantityString(resId: Int, quantity: Int, vararg formatArgs: Any): String =
    InstrumentationRegistry.getInstrumentation().targetContext.resources
        .getQuantityString(resId, quantity, *formatArgs)

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
        composeRule.onNodeWithContentDescription(uiString(R.string.crash_reports_loading)).assertIsDisplayed()
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
        composeRule.onNodeWithText(uiString(R.string.crash_reports_empty)).assertIsDisplayed()
    }

    @Test
    fun emptyInboxStillAllowsManualLogSnapshot() {
        val actions = RecordingListActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = CrashReportsListState(loading = false, records = emptyList()),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText(uiString(R.string.save_log)).performClick()
        assertEquals(1, actions.saveLogCount)
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
                                subtitle = "Process exit report · today",
                            ),
                        ),
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Demo MIDlet").performTouchInput { longClick() }
        composeRule.onNodeWithText(
            uiQuantityString(R.plurals.crash_reports_selected_count, 1, 1),
        ).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(uiString(R.string.share_selected_reports)).performClick()
        assertEquals(listOf("report-1"), actions.sharedIds)

        composeRule.onNodeWithText("Other MIDlet").performClick()
        composeRule.onNodeWithText(
            uiQuantityString(R.plurals.crash_reports_selected_count, 2, 2),
        ).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(uiString(R.string.share_selected_reports)).performClick()
        assertEquals(listOf("report-1", "report-2"), actions.sharedIds)

        composeRule.onNodeWithContentDescription(uiString(R.string.delete_selected_reports)).performClick()
        composeRule.onNodeWithText(uiString(R.string.delete_selected_reports)).performClick()
        assertEquals(listOf("report-1", "report-2"), actions.deletedIds)
    }

    @Test
    fun batchShareKeepsSelectionLockedUntilPreparationFinishes() {
        val sharing = mutableStateOf(false)
        val actions = RecordingListActions()
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = CrashReportsListState(
                        loading = false,
                        records = listOf(
                            CrashReportListItem("report-1", "Demo MIDlet", "Today"),
                            CrashReportListItem("report-2", "Other MIDlet", "Today"),
                        ),
                        sharingReports = sharing.value,
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Demo MIDlet").performTouchInput { longClick() }
        composeRule.runOnIdle { sharing.value = true }

        composeRule.onNodeWithContentDescription(uiString(R.string.action_back)).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(uiString(R.string.select_all_reports))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(uiString(R.string.share_selected_reports))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(uiString(R.string.delete_selected_reports))
            .assertIsNotEnabled()

        composeRule.onNodeWithText("Other MIDlet").performTouchInput { click() }
        composeRule.onNodeWithText(
            uiQuantityString(R.plurals.crash_reports_selected_count, 1, 1),
        ).assertIsDisplayed()

        composeRule.runOnIdle { sharing.value = false }
        composeRule.onNodeWithText("Other MIDlet").performClick()
        composeRule.onNodeWithText(
            uiQuantityString(R.plurals.crash_reports_selected_count, 2, 2),
        ).assertIsDisplayed()
    }

    @Test
    fun batchShareIntentContainsOnlySelectedZipUrisWithReadPermission() {
        val first = Uri.parse("content://media/external/downloads/111")
        val second = Uri.parse("content://media/external/downloads/222")
        val intent = CrashReportsActivity.shareIntent(arrayListOf(first, second))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("application/zip", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(2, intent.clipData?.itemCount)
        assertEquals(first, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(second, intent.clipData?.getItemAt(1)?.uri)
        @Suppress("DEPRECATION")
        val attachments = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(listOf(first, second), attachments)

        val one = CrashReportsActivity.shareIntent(arrayListOf(first))
        assertEquals(Intent.ACTION_SEND, one.action)
        assertEquals(first, one.clipData?.getItemAt(0)?.uri)
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
        composeRule.onNodeWithContentDescription(uiString(R.string.select_all_reports)).performClick()
        composeRule.onNodeWithText(
            uiQuantityString(R.plurals.crash_reports_selected_count, 2, 2),
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(uiString(R.string.clear_report_selection)).performClick()
        composeRule.onNodeWithText(uiString(R.string.crash_reports)).assertIsDisplayed()
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

        composeRule.onNodeWithText(uiString(R.string.crash_report_bundle_ready_title)).assertIsDisplayed()
        composeRule.onNodeWithText(uiString(R.string.locate_bundle)).performClick()
        assertEquals(1, actions.locateCount)
        composeRule.onNodeWithText("Open GitHub").performClick()
        assertEquals(1, actions.openGitHubCount)
    }

    @Test
    fun reportDetailLoadingDisablesActions() {
        composeRule.setContent {
            JLModPlusTheme {
                CrashReportDetailsScreen(
                    state = CrashReportDetailState(displayText = "", loading = true),
                    actions = RecordingDetailActions(),
                )
            }
        }

        composeRule.onNodeWithContentDescription(uiString(R.string.copy_report))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(uiString(R.string.report_on_github))
            .assertIsNotEnabled()
        composeRule.onNodeWithContentDescription(uiString(R.string.delete_report))
            .assertIsNotEnabled()
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

        composeRule.onNodeWithText(uiString(R.string.diagnostic_report)).assertIsDisplayed()
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
        var saveLogCount = 0
        var sharedIds: List<String> = emptyList()
        var deletedIds: List<String> = emptyList()

        override fun onBack() = Unit

        override fun onOpen(reportId: String) {
            openedId = reportId
        }

        override fun onSaveLog() {
            saveLogCount++
        }

        override fun onShareSelected(reportIds: List<String>) {
            sharedIds = reportIds
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
