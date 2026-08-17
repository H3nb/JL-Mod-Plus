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

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val PreviewReports = listOf(
    CrashReportListItem(
        id = "midlet-failure",
        title = "Demo MIDlet",
        subtitle = "MIDlet session failure · Aug 13, 2026, 12:00 PM",
    ),
    CrashReportListItem(
        id = "java-report",
        title = "Java diagnostic report",
        subtitle = "Java diagnostic report · Aug 13, 2026, 11:45 AM",
    ),
)

private const val PreviewReportText = """JL-Mod Plus diagnostic report

Type: Java diagnostic report
MIDlet: Demo MIDlet
Android: 16

Stack trace:
java.lang.IllegalStateException: sample"""

@PreviewTest
@Preview(name = "List content", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun CrashReportsListContentScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        CrashReportsScreen(
            state = CrashReportsListState(loading = false, records = PreviewReports),
            actions = NoOpListActions,
        )
    }
}

@PreviewTest
@Preview(name = "List empty dark", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun CrashReportsListEmptyDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        CrashReportsScreen(
            state = CrashReportsListState(loading = false, records = emptyList()),
            actions = NoOpListActions,
        )
    }
}

@PreviewTest
@Preview(name = "Detail", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun CrashReportDetailsScreenshot() {
    CrashReportDetailsPreview(darkTheme = false)
}

@PreviewTest
@Preview(
    name = "Detail large font",
    widthDp = 360,
    heightDp = 640,
    fontScale = 1.5f,
    showBackground = true,
)
@Composable
fun CrashReportDetailsLargeFontScreenshot() {
    CrashReportDetailsPreview(darkTheme = false)
}

@PreviewTest
@Preview(
    name = "Detail dark",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun CrashReportDetailsDarkScreenshot() {
    CrashReportDetailsPreview(darkTheme = true)
}

@PreviewTest
@Preview(name = "Share confirmation", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun CrashReportShareConfirmationScreenshot() {
    CrashReportConfirmationPreview(CrashReportConfirmation.Share)
}

@PreviewTest
@Preview(name = "Delete confirmation", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun CrashReportDeleteConfirmationScreenshot() {
    CrashReportConfirmationPreview(CrashReportConfirmation.Delete)
}

@Composable
private fun CrashReportDetailsPreview(darkTheme: Boolean) {
    JLModPlusTheme(darkTheme = darkTheme) {
        CrashReportDetailsScreen(
            state = CrashReportDetailState(PreviewReportText),
            actions = NoOpDetailActions,
        )
    }
}

@Composable
private fun CrashReportConfirmationPreview(confirmation: CrashReportConfirmation) {
    JLModPlusTheme(darkTheme = false) {
        CrashReportConfirmationDialog(
            confirmation = confirmation,
            onDismiss = {},
            onConfirm = {},
        )
    }
}

private object NoOpListActions : CrashReportsActions {
    override fun onBack() = Unit

    override fun onOpen(reportId: String) = Unit

    override fun onCopySelected(reportIds: List<String>) = Unit

    override fun onShareSelected(reportIds: List<String>) = Unit

    override fun onDeleteSelected(reportIds: List<String>) = Unit
}

private object NoOpDetailActions : CrashReportDetailsActions {
    override fun onBack() = Unit

    override fun onCopy() = Unit

    override fun onShare() = Unit

    override fun onReportGitHub() = Unit

    override fun onDelete() = Unit
}
