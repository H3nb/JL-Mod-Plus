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

package ru.playsoftware.j2meloader.settings

import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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

    @Before
    fun capturePreferences() {
        preferences = PreferenceManager.getDefaultSharedPreferences(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        hadKeepScreen = preferences.contains("pref_wakelock_switch")
        previousKeepScreen = preferences.getBoolean("pref_wakelock_switch", false)
    }

    @After
    fun restorePreferences() {
        val editor = preferences.edit()
        if (hadKeepScreen) {
            editor.putBoolean("pref_wakelock_switch", previousKeepScreen)
        } else {
            editor.remove("pref_wakelock_switch")
        }
        editor.commit()
    }

    @Test
    fun activityHostsSettingsComposeScreen() {
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Theme").assertIsDisplayed()
        composeRule.onNodeWithText("Working directory").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun switchWritesTheExistingPreferenceKey() {
        preferences.edit().putBoolean("pref_wakelock_switch", false).commit()
        composeRule.onNodeWithText("Keep screen on").performClick()
        assertTrue(preferences.getBoolean("pref_wakelock_switch", false))
    }
}
