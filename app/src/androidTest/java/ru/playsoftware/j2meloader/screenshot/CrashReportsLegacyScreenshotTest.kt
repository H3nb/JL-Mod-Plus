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

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ScrollView
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
            installLegacyList(activity, emptyList())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            dropshots.assertSnapshot(activity, name = "CrashReportsList_Empty_Legacy", filePath = "crashes/legacy")
        }
    }

    @Test
    fun crashReportsListContentLegacy() {
        activityRule.scenario.onActivity { activity ->
            installLegacyList(activity,
                listOf(
                    ReportRow("Demo MIDlet", "MIDlet session failure · Aug 13, 2026, 12:00 PM"),
                    ReportRow("Java diagnostic report", "Java diagnostic report · Aug 13, 2026, 11:45 AM"),
                ))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        activityRule.scenario.onActivity { activity ->
            dropshots.assertSnapshot(activity, name = "CrashReportsList_Content_Legacy", filePath = "crashes/legacy")
        }
    }

    @Test
    fun crashReportDetailsLegacy() {
        activityRule.scenario.onActivity { activity ->
            installLegacyDetails(activity,
                "JL-Mod Plus diagnostic report\n\n" +
                    "Type: Java diagnostic report\n" +
                    "MIDlet: Demo MIDlet\n" +
                    "Android: 16\n\n" +
                    "Stack trace:\njava.lang.IllegalStateException: sample")
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

    private fun installLegacyList(
        activity: ScreenshotHostActivity,
        rows: List<ReportRow>,
    ) {
        prepareHost(activity)
        val root = FrameLayout(activity)
        val list = ListView(activity).apply {
            dividerHeight = 1.dp(activity)
        }
        val empty = TextView(activity).apply {
            text = "No local crash reports"
            setPadding(24.dp(activity), 24.dp(activity), 24.dp(activity), 24.dp(activity))
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
        }
        root.addView(list, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        root.addView(empty, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        activity.setContentView(root)
        configureLegacyActionBar(activity)
        EdgeToEdgeCompat.protectHostContent(activity)
        list.emptyView = empty
        list.adapter = ReportAdapter(rows)
    }

    private fun installLegacyDetails(activity: ScreenshotHostActivity, text: String) {
        prepareHost(activity)
        val scroll = ScrollView(activity).apply {
            isFillViewport = true
        }
        val details = TextView(activity).apply {
            setPadding(16.dp(activity), 16.dp(activity), 16.dp(activity), 16.dp(activity))
            setTextIsSelectable(true)
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            typeface = android.graphics.Typeface.MONOSPACE
            this.text = text
        }
        scroll.addView(details, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        activity.setContentView(scroll)
        configureLegacyActionBar(activity)
        EdgeToEdgeCompat.protectHostContent(activity)
    }

    private fun configureLegacyActionBar(activity: ScreenshotHostActivity) {
        activity.setTitle(R.string.crash_reports)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    private fun Int.dp(activity: ScreenshotHostActivity): Int =
        (this * activity.resources.displayMetrics.density + 0.5f).toInt()

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
