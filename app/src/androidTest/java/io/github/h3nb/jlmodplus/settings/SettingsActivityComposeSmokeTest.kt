/*
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

package io.github.h3nb.jlmodplus.settings

import io.github.h3nb.jlmodplus.R
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsActivityComposeSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<SettingsActivity>()

    private lateinit var preferences: SharedPreferences
    private var hadKeepScreen = false
    private var previousKeepScreen = false
    private var hadLayout = false
    private var previousLayout = 0
    private var hadGridIconSize = false
    private var previousGridIconSize = 1
    private var hadHiddenGridTitles = false
    private var previousHiddenGridTitles = false

    @Before
    fun capturePreferences() {
        preferences = PreferenceManager.getDefaultSharedPreferences(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        hadKeepScreen = preferences.contains("pref_wakelock_switch")
        previousKeepScreen = preferences.getBoolean("pref_wakelock_switch", false)
        hadLayout = preferences.contains("pref_apps_view")
        previousLayout = preferences.getInt("pref_apps_view", 0)
        hadGridIconSize = preferences.contains("pref_apps_grid_icon_size")
        previousGridIconSize = preferences.getInt("pref_apps_grid_icon_size", 1)
        hadHiddenGridTitles = preferences.contains("pref_apps_hide_grid_titles")
        previousHiddenGridTitles = preferences.getBoolean("pref_apps_hide_grid_titles", false)
    }

    @After
    fun restorePreferences() {
        val editor = preferences.edit()
        if (hadKeepScreen) {
            editor.putBoolean("pref_wakelock_switch", previousKeepScreen)
        } else {
            editor.remove("pref_wakelock_switch")
        }
        if (hadLayout) editor.putInt("pref_apps_view", previousLayout)
        else editor.remove("pref_apps_view")
        if (hadGridIconSize) editor.putInt("pref_apps_grid_icon_size", previousGridIconSize)
        else editor.remove("pref_apps_grid_icon_size")
        if (hadHiddenGridTitles) editor.putBoolean("pref_apps_hide_grid_titles", previousHiddenGridTitles)
        else editor.remove("pref_apps_hide_grid_titles")
        editor.commit()
    }

    @Test
    fun gridPreferencesRemainAccessibleWhileLibraryUsesListView() {
        preferences.edit()
            .putInt("pref_apps_view", 0)
            .putInt("pref_apps_grid_icon_size", 1)
            .putBoolean("pref_apps_hide_grid_titles", false)
            .commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.recreate()
        }
        composeRule.waitForIdle()

        val sizeOption = hasText(composeRule.activity.getString(R.string.library_grid_icon_size_title)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(sizeOption)
        composeRule.onNode(sizeOption).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.library_grid_icon_size_large))
            .performClick()
        assertEquals(2, preferences.getInt("pref_apps_grid_icon_size", 1))

        val hideOption = hasText(composeRule.activity.getString(R.string.library_hide_grid_titles)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hideOption)
        composeRule.onNode(hideOption).performClick()
        assertTrue(preferences.getBoolean("pref_apps_hide_grid_titles", false))
    }

    @Test
    fun activityHostsSettingsComposeScreen() {
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        val directorySetting = hasText(composeRule.activity.getString(R.string.pref_emulator_dir)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(directorySetting)
        composeRule.onNode(directorySetting).assertIsDisplayed()
    }

    @Test
    fun switchWritesTheExistingPreferenceKey() {
        preferences.edit().putBoolean("pref_wakelock_switch", false).commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.recreate()
        }
        composeRule.waitForIdle()
        val keepScreenSetting = hasText(composeRule.activity.getString(R.string.pref_wakelock_title)) and hasClickAction()
        composeRule.onNode(hasScrollAction()).performScrollToNode(keepScreenSetting)
        composeRule.onNode(keepScreenSetting).performClick()
        assertTrue(preferences.getBoolean("pref_wakelock_switch", false))
    }
}
