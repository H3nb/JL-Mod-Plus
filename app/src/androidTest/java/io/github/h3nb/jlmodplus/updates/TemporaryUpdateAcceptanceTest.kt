package io.github.h3nb.jlmodplus.updates

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.h3nb.jlmodplus.MainActivity
import io.github.h3nb.jlmodplus.R
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URL

@RunWith(AndroidJUnit4::class)
class TemporaryUpdateAcceptanceTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test
    fun deferredUpdateAndExternalReleaseHandoff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        preferences.edit().remove("jlmod_updates_result")
            .remove("jlmod_updates_last_success")
            .remove("jlmod_updates_last_attempt")
            .remove("jlmod_updates_handled_version_Preview")
            .putString("jlmod_updates_channel", "Preview")
            .putBoolean("jlmod_updates_enabled", true)
            .commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val install = context.getString(R.string.install)
            val menuItem = context.getString(R.string.bulk_install_choose_files)
            rule.waitUntil(50_000) {
                rule.onAllNodesWithText(menuItem).fetchSemanticsNodes().isEmpty() &&
                    rule.onAllNodesWithText(context.getString(R.string.library_destination_apps))
                        .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription(install).performClick()
            rule.onNodeWithText(menuItem).assertIsDisplayed()

            URL("http://10.0.2.2:8765/unlock").openConnection().getInputStream().use { it.readBytes() }
            rule.waitUntil(25_000) {
                preferences.getString("jlmod_updates_result", null) == "available"
            }
            rule.onAllNodesWithText(context.getString(R.string.updates_skip_version))
                .assertCountEquals(0)
            Espresso.pressBack()
            android.util.Log.i("UpdateAcceptance", "Menu nodes after Back: " +
                rule.onAllNodesWithText(menuItem).fetchSemanticsNodes().size)
            rule.waitUntil(20_000) {
                rule.onAllNodesWithText(context.getString(R.string.updates_skip_version))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(context.getString(R.string.updates_skip_version))
                .assertIsDisplayed().performClick()
            rule.waitUntil(15_000) {
                preferences.getString("jlmod_updates_handled_version_Preview", null) != null
            }

            val filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("https")
                addDataAuthority("github.com", null)
            }
            val monitor = Instrumentation.ActivityMonitor(
                filter, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent()), true
            )
            instrumentation.addMonitor(monitor)
            try {
                rule.onNodeWithText(context.getString(R.string.library_destination_more))
                    .performClick()
                rule.onNodeWithText(context.getString(R.string.updates_title)).performClick()
                rule.onNodeWithText(context.getString(R.string.updates_view_release)).performClick()
                rule.waitUntil(10_000) { monitor.hits > 0 }
                assertTrue("Release ACTION_VIEW was not delivered", monitor.hits > 0)
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        } finally {
            scenario.close()
        }
    }
}
