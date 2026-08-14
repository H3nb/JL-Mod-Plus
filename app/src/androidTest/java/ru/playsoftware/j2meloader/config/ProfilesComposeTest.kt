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

package ru.playsoftware.j2meloader.config

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

@RunWith(AndroidJUnit4::class)
class ProfilesComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun createFiltersFileNameCharactersAndDispatchesName() {
        val actions = RecordingProfilesActions()
        setProfilesContent(actions)
        composeRule.onNodeWithContentDescription("Create New Profile").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("New/Profile")
        composeRule.onNodeWithText("OK").performClick()
        assertEquals("NewProfile", actions.created)
    }

    @Test
    fun profileActionsPreserveDefaultEditRenameAndDeleteCallbacks() {
        val actions = RecordingProfilesActions()
        setProfilesContent(actions)

        composeRule.onNodeWithText("Playable").performClick()
        composeRule.onNodeWithText("Set As Default").performClick()
        assertEquals("Playable", actions.defaulted)

        composeRule.onNodeWithText("Playable").performClick()
        composeRule.onNodeWithText("Edit").performClick()
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

    private fun setProfilesContent(actions: RecordingProfilesActions) {
        composeRule.setContent {
            JLModPlusTheme {
                ProfilesScreen(
                    state = ProfilesUiState(
                        profiles = listOf(
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
        var defaulted: String? = null
        var edited: String? = null
        var renamed: Pair<String, String>? = null
        var deleted: String? = null

        override fun onBack() = Unit
        override fun onCreate(name: String) { created = name }
        override fun onSetDefault(name: String) { defaulted = name }
        override fun onEdit(name: String) { edited = name }
        override fun onRename(oldName: String, newName: String) { renamed = oldName to newName }
        override fun onDelete(name: String) { deleted = name }
    }
}
