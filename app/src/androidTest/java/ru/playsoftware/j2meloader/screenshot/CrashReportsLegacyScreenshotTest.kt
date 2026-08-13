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

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dropbox.dropshots.Dropshots
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat

/** Legacy View baselines are intentionally kept beside the future Compose pilot baselines. */
@RunWith(AndroidJUnit4::class)
class CrashReportsLegacyScreenshotTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(ScreenshotHostActivity::class.java)

    @get:Rule
    val dropshots = Dropshots()

    @Test
    fun crashReportsListEmptyLegacy() {
        activityRule.scenario.onActivity { activity ->
            prepareHost(activity)
            activity.setContentView(R.layout.activity_crash_reports)
            activity.setTitle(R.string.crash_reports)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            EdgeToEdgeCompat.protectHostContent(activity)
            val list = activity.findViewById<ListView>(R.id.crash_reports_list)
            list.emptyView = activity.findViewById(R.id.crash_reports_empty)
            list.adapter = ReportAdapter(emptyList())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            dropshots.assertSnapshot(activity, name = "CrashReportsList_Empty_Legacy", filePath = "crashes/legacy")
        }
    }

    @Test
    fun crashReportsListContentLegacy() {
        activityRule.scenario.onActivity { activity ->
            prepareHost(activity)
            activity.setContentView(R.layout.activity_crash_reports)
            activity.setTitle(R.string.crash_reports)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            EdgeToEdgeCompat.protectHostContent(activity)
            val list = activity.findViewById<ListView>(R.id.crash_reports_list)
            list.emptyView = activity.findViewById(R.id.crash_reports_empty)
            list.adapter = ReportAdapter(
                listOf(
                    ReportRow("Demo MIDlet", "MIDlet session failure · Aug 13, 2026, 12:00 PM"),
                    ReportRow("Java diagnostic report", "Java diagnostic report · Aug 13, 2026, 11:45 AM"),
                ),
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            dropshots.assertSnapshot(activity, name = "CrashReportsList_Content_Legacy", filePath = "crashes/legacy")
        }
    }

    @Test
    fun crashReportDetailsLegacy() {
        activityRule.scenario.onActivity { activity ->
            prepareHost(activity)
            activity.setContentView(R.layout.activity_crash_report_details)
            activity.setTitle(R.string.crash_reports)
            activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
            EdgeToEdgeCompat.protectHostContent(activity)
            activity.findViewById<TextView>(R.id.crash_report_details_text).text =
                "JL-Mod Plus diagnostic report\n\n" +
                    "Type: Java diagnostic report\n" +
                    "MIDlet: Demo MIDlet\n" +
                    "Android: 16\n\n" +
                    "Stack trace:\njava.lang.IllegalStateException: sample"
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            dropshots.assertSnapshot(activity, name = "CrashReportDetails_Content_Legacy", filePath = "crashes/legacy")
        }
    }

    private fun prepareHost(activity: ScreenshotHostActivity) {
        EdgeToEdgeCompat.enableIfSupported(activity)
        activity.setTitle(R.string.crash_reports)
    }

    private data class ReportRow(val title: String, val subtitle: String)

    private class ReportAdapter(private val rows: List<ReportRow>) : BaseAdapter() {
        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): ReportRow = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: android.view.LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            val row = getItem(position)
            view.findViewById<TextView>(android.R.id.text1).text = row.title
            view.findViewById<TextView>(android.R.id.text2).text = row.subtitle
            return view
        }
    }
}
