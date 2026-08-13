/*
 * Modified for JL-Mod Plus.
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

package ru.playsoftware.j2meloader.screenshot

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dropbox.dropshots.Dropshots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.crashes.CrashReportDetailState
import ru.playsoftware.j2meloader.crashes.CrashReportListItem
import ru.playsoftware.j2meloader.crashes.CrashReportsActions
import ru.playsoftware.j2meloader.crashes.CrashReportsListState
import ru.playsoftware.j2meloader.crashes.CrashReportsScreen
import ru.playsoftware.j2meloader.crashes.CrashReportDetailsActions
import ru.playsoftware.j2meloader.crashes.CrashReportDetailsScreen
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat

/** Compose baselines share the same host/inset contract as the migrated Crash Reports screens. */
@RunWith(AndroidJUnit4::class)
class CrashReportsComposeScreenshotTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ComposeScreenshotHostActivity::class.java)

    @get:Rule
    val dropshots = Dropshots()

    @Test
    fun crashReportsListEmptyCompose() {
        setContent {
            CrashReportsScreen(
                state = CrashReportsListState(loading = false, records = emptyList()),
                actions = NoopListActions,
            )
        }
        assertSnapshot("CrashReportsList_Empty_Compose")
    }

    @Test
    fun crashReportsListEmptyComposeDark() {
        setContent(darkTheme = true) {
            CrashReportsScreen(
                state = CrashReportsListState(loading = false, records = emptyList()),
                actions = NoopListActions,
            )
        }
        assertSnapshot("CrashReportsList_Empty_Compose_Dark")
    }

    @Test
    fun crashReportsListContentCompose() {
        setContent {
            CrashReportsScreen(
                state = CrashReportsListState(
                    loading = false,
                    records = listOf(
                        CrashReportListItem(
                            id = "report-1",
                            title = "Demo MIDlet",
                            subtitle = "MIDlet session failure · Aug 13, 2026, 12:00 PM",
                        ),
                        CrashReportListItem(
                            id = "report-2",
                            title = "Java diagnostic report",
                            subtitle = "Java diagnostic report · Aug 13, 2026, 11:45 AM",
                        ),
                    ),
                ),
                actions = NoopListActions,
            )
        }
        assertSnapshot("CrashReportsList_Content_Compose")
    }

    @Test
    fun crashReportDetailsCompose() {
        setContent {
            CrashReportDetailsScreen(
                state = CrashReportDetailState(
                    "JL-Mod Plus diagnostic report\n\n" +
                        "Type: Java diagnostic report\n" +
                        "MIDlet: Demo MIDlet\n" +
                        "Android: 16\n\n" +
                        "Stack trace:\njava.lang.IllegalStateException: sample",
                ),
                actions = NoopDetailsActions,
            )
        }
        assertSnapshot("CrashReportDetails_Content_Compose")
    }

    @Test
    fun crashReportDetailsComposeDark() {
        setContent(darkTheme = true) {
            CrashReportDetailsScreen(
                state = CrashReportDetailState("JL-Mod Plus diagnostic report\n\nStack trace: sample"),
                actions = NoopDetailsActions,
            )
        }
        assertSnapshot("CrashReportDetails_Content_Compose_Dark")
    }

    private fun setContent(darkTheme: Boolean = false, content: @Composable () -> Unit) {
        activityRule.scenario.onActivity { activity ->
            EdgeToEdgeCompat.enableIfSupported(activity)
            val composeView = ComposeView(activity).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            }
            activity.setContentView(composeView)
            EdgeToEdgeCompat.protectHostContent(activity)
            composeView.setContent {
                JLModPlusTheme(darkTheme = darkTheme, content = content)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertSnapshot(name: String) {
        activityRule.scenario.onActivity { activity ->
            dropshots.assertSnapshot(activity, name = name, filePath = "crashes/compose")
        }
    }

    private object NoopListActions : CrashReportsActions {
        override fun onBack() = Unit

        override fun onOpen(reportId: String) = Unit
    }

    private object NoopDetailsActions : CrashReportDetailsActions {
        override fun onBack() = Unit

        override fun onCopy() = Unit

        override fun onShare() = Unit

        override fun onDelete() = Unit
    }
}
