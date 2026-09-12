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

package io.github.h3nb.jlmodplus.config

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class ProfilesComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun createRejectsPathCharactersAndDispatchesValidName() {
        val actions = RecordingProfilesActions()
        setProfilesContent(actions)
        composeRule.onNodeWithContentDescription("Create New Preset").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("New/Profile")
        composeRule.onNodeWithText("OK").assertIsNotEnabled()
        composeRule.onNode(hasSetTextAction()).performTextReplacement("New Profile")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("New Profile", actions.created)
    }

    @Test
    fun profileActionsPreserveDefaultEditRenameAndDeleteCallbacks() {
        val actions = RecordingProfilesActions()
        setProfilesContent(actions)

        composeRule.onNodeWithText("Playable").performClick()
        composeRule.onNodeWithText("Default for New Applications").performClick()
        assertEquals("Playable", actions.defaulted)

        composeRule.onNodeWithText("Playable").performClick()
        composeRule.onNodeWithText("Edit Preset").performClick()
        assertEquals("Playable", actions.edited)

        composeRule.onNodeWithText("Playable").performClick()
        composeRule.onNodeWithText("Rename").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput(" 2")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("Playable" to "Playable 2", actions.renamed)

        composeRule.onNodeWithText("Empty").performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("Empty", actions.deleted)
    }

    @Test
    fun builtInSettingsAreDistinctFromDefaultTemplatePolicy() {
        val actions = RecordingProfilesActions()
        setProfilesContent(actions)

        composeRule.onNodeWithText("Change").performClick()
        composeRule.onNodeWithText("Apply").performClick()
        assertEquals(1, actions.builtInDefaultCalls)

        composeRule.onNodeWithText("JL-Mod Defaults").performClick()
        composeRule.onNodeWithText("Rename").assertDoesNotExist()
        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun keyboardOnlyLegacyItemsCannotBecomeApplicationPresets() {
        val actions = RecordingProfilesActions()
        composeRule.setContent {
            JLModPlusTheme {
                ProfilesScreen(
                    state = ProfilesUiState(
                        profiles = listOf(
                            ProfileUiItem("Legacy layout", false, false, isKeyboardOnly = true),
                        ),
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Legacy layout").performClick()
        composeRule.onNode(
            hasText("Default for New Applications") and hasAnyAncestor(isDialog()),
        ).assertDoesNotExist()
        composeRule.onNodeWithText("Edit Preset").assertDoesNotExist()
        composeRule.onNodeWithText("Rename").assertExists()
        composeRule.onNodeWithText("Delete").assertExists()
    }

    @Test
    fun unavailableItemsAreNotPresentedAsSavedLayouts() {
        val actions = RecordingProfilesActions()
        composeRule.setContent {
            JLModPlusTheme {
                ProfilesScreen(
                    state = ProfilesUiState(
                        profiles = listOf(
                            ProfileUiItem("Broken", false, false, isUnavailable = true),
                        ),
                    ),
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("Unavailable").assertExists()
        composeRule.onNodeWithText("Saved Keyboard Layouts").assertDoesNotExist()
        composeRule.onNodeWithText("Broken").performClick()
        composeRule.onNodeWithText("This entry could not be loaded. Rename or delete it.").assertExists()
        composeRule.onNode(
            hasText("Default for New Applications") and hasAnyAncestor(isDialog()),
        ).assertDoesNotExist()
        composeRule.onNodeWithText("Edit Preset").assertDoesNotExist()
    }

    private fun setProfilesContent(actions: RecordingProfilesActions) {
        composeRule.setContent {
            JLModPlusTheme {
                ProfilesScreen(
                    state = ProfilesUiState(
                        profiles = listOf(
                            ProfileUiItem("", isDefault = false, canEdit = false, isBuiltIn = true),
                            ProfileUiItem("Playable", isDefault = false, canEdit = true),
                            ProfileUiItem("Empty", isDefault = false, canEdit = false),
                        ),
                    ),
                    actions = actions,
                )
            }
        }
    }

    private class RecordingProfilesActions : ProfilesActions {
        var created: String? = null
        var builtInDefaultCalls = 0
        var defaulted: String? = null
        var edited: String? = null
        var renamed: Pair<String, String>? = null
        var deleted: String? = null

        override fun onBack() = Unit
        override fun onCreate(name: String) { created = name }
        override fun onSetBuiltInDefault() { builtInDefaultCalls++ }
        override fun onSetDefault(name: String) { defaulted = name }
        override fun onEdit(name: String) { edited = name }
        override fun onRename(oldName: String, newName: String) { renamed = oldName to newName }
        override fun onDelete(name: String) { deleted = name }
    }
}
