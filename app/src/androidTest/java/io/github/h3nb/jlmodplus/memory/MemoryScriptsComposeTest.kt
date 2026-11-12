// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.memory

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI behavior: Save is persistent authoring intent, not guest execution. */
class MemoryScriptsComposeTest {
    @get:Rule val rule = createComposeRule()

    private val sample = """
        {
          "formatVersion":1,
          "id":"sample.coins",
          "name":"Sample Coins",
          "game":{"mainClass":"x/Y"},
          "inputs":{"coins":{"min":0,"max":999}},
          "root":{"kind":"midlet","class":"x/Y"},
          "actions":[{"kind":"set","path":[{"kind":"field","owner":"x/Y",
          "name":"coins","descriptor":"I"}],"valueInput":"coins"}]
        }
    """.trimIndent()

    @Test fun pastedScriptSavesWithoutRunningThenExecutesWithExplicitParameter() {
        val ui = mutableStateOf(MemoryEditorUiState(
            visible = true, connected = true, supported = true, writeSupported = true,
            scriptsTab = true, runtimeToken = 81L,
        ))
        var saved: String? = null
        val executed = mutableListOf<Pair<Map<String, String>, Boolean>>()
        val actions = object : TestMemoryEditorActions() {
            override fun saveMemoryScript(document: String, replacingId: String?,
                                          onComplete: (Boolean) -> Unit) {
                saved = document
                ui.value = ui.value.copy(scripts = listOf(
                    MemoryScriptRow("sample.coins", "Sample Coins", document)))
                onComplete(true)
            }
            override fun runMemoryRecipe(document: String, inputs: Map<String, String>,
                                         freeze: Boolean, preview: Boolean) {
                executed.add(inputs to preview)
            }
        }
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(ui.value, actions) } }
        rule.onAllNodesWithText("Add script")[0].performClick()
        rule.onNodeWithTag("memory-script-editor").performTextReplacement(sample)
        rule.onNodeWithText("Save").performClick()
        rule.runOnIdle {
            assertEquals(sample, saved)
            assertTrue("Save must not execute a recipe", executed.isEmpty())
        }
        rule.onNodeWithText("Sample Coins").assertIsDisplayed()
        rule.onNodeWithText("Run").performClick()
        rule.runOnIdle {
            assertEquals(1, executed.size)
            assertEquals("0", executed.single().first["coins"])
            assertFalse(executed.single().second)
        }
    }

    @Test fun minimizeRestoresUnsavedJsonDraftInSameMidletSession() {
        val token = 91091L
        MemoryEditorSessionNavigation.read(token)
        val showing = mutableStateOf(true)
        val state = MemoryEditorUiState(
            visible = true, connected = true, supported = true, writeSupported = true,
            scriptsTab = true, runtimeToken = token,
        )
        val actions = object : TestMemoryEditorActions() {
            override fun close() {
                showing.value = false
            }
        }
        rule.setContent {
            JLModPlusTheme {
                if (showing.value) MemoryEditorRuntimeRoot(state, actions)
            }
        }
        rule.onAllNodesWithText("Add script")[0].performClick()
        rule.onNodeWithTag("memory-script-editor").performTextReplacement(sample)
        rule.onNodeWithContentDescription("Minimize Memory Editor").performClick()
        rule.runOnIdle { showing.value = true }
        val restored = rule.onNodeWithTag("memory-script-editor").assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue(restored.config[SemanticsProperties.EditableText].text.contains("sample.coins"))
        MemoryEditorSessionNavigation.read(token + 1)
        assertFalse(MemoryEditorSessionNavigation.script(token + 1).editing)
    }

    @Test fun refreshShowsDecodedCurrentValueWithoutWritingAndFlagsDisagreement() {
        val actions = object : TestMemoryEditorActions() {
            var reads = 0
            override fun readCurrentMemoryRecipe(
                document: String, inputs: Map<String, String>,
                onReady: (List<MemoryRecipeReadValue>?, String?) -> Unit,
            ) {
                reads++
                onReady(listOf(
                    MemoryRecipeReadValue("coins", "7828"),
                    MemoryRecipeReadValue("coins", "9000"),
                ), null)
            }
            override fun runMemoryRecipe(
                document: String, inputs: Map<String, String>,
                freeze: Boolean, preview: Boolean,
            ) {
                throw AssertionError("Refresh must never execute an edit")
            }
        }
        val state = MemoryEditorUiState(
            visible = true, connected = true, supported = true,
            writeSupported = true, scriptsTab = true, runtimeToken = 9203L,
            scriptsLoaded = true,
            scripts = listOf(MemoryScriptRow("sample.coins", "Sample Coins", sample)),
        )
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(state, actions) } }
        rule.onNodeWithText("Sample Coins").performClick()
        rule.onNodeWithText("Refresh").performClick()
        rule.onNodeWithText("coins: Inconsistent copies: 7828 / 9000").assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, actions.reads) }
    }

    @Test fun activeFreezeIndicatorFollowsControllerStateAfterAutomaticStop() {
        val ui = mutableStateOf(MemoryEditorUiState(
            visible = true, connected = true, supported = true, writeSupported = true,
            scriptsTab = true, runtimeToken = 9204L, scriptsLoaded = true,
            scripts = listOf(MemoryScriptRow("sample.coins", "Sample Coins", sample)),
            scriptActiveFreezes = listOf("sample.coins" to "Sample Coins"),
        ))
        val actions = object : TestMemoryEditorActions() {}
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(ui.value, actions) } }
        rule.onNodeWithText("Sample Coins").performClick()
        rule.onNodeWithText("Freeze active for this script").assertIsDisplayed()
        rule.runOnIdle { ui.value = ui.value.copy(scriptActiveFreezes = emptyList()) }
        rule.onNodeWithText("Freeze active for this script").assertDoesNotExist()
    }

    @Test fun failedSaveLeavesTheJsonEditorOpenForCorrection() {
        val actions = object : TestMemoryEditorActions() {
            override fun saveMemoryScript(document: String, replacingId: String?,
                                          onComplete: (Boolean) -> Unit) = onComplete(false)
        }
        rule.setContent {
            JLModPlusTheme {
                MemoryEditorRuntimeRoot(MemoryEditorUiState(
                    visible = true, connected = true, supported = true,
                    writeSupported = true, scriptsTab = true, runtimeToken = 83L,
                ), actions)
            }
        }
        rule.onAllNodesWithText("Add script")[0].performClick()
        rule.onNodeWithTag("memory-script-editor").performTextReplacement(sample)
        rule.onNodeWithText("Save").performClick()
        val editor = rule.onNodeWithTag("memory-script-editor").assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue(editor.config[SemanticsProperties.EditableText].text.contains("sample.coins"))
        rule.onNodeWithText("Could not save script").assertIsDisplayed()
    }
}
