// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.memory

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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

    @Test fun descriptionAppearsInScriptListAndLegacyDetail() {
        val note = "Check your quest state before running."
        val document = sample.replace("\"game\":", "\"description\":\"$note\",\"game\":")
        val state = MemoryEditorUiState(
            visible = true, connected = true, supported = true,
            scriptsTab = true, runtimeToken = 9207L, scriptsLoaded = true,
            scripts = listOf(MemoryScriptRow("sample.coins", "Sample Coins", document)),
        )
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(state, TestMemoryEditorActions()) } }
        rule.onNodeWithText(note).assertIsDisplayed()
        rule.onNodeWithText("Sample Coins").performClick()
        rule.onNodeWithText("Description").assertIsDisplayed()
        rule.onNodeWithText(note).assertIsDisplayed()
    }

    @Test fun descriptionAppearsInTypedScriptDetail() {
        val note = "Use only after selecting Character 1."
        val document = sample.replace("\"formatVersion\":1",
            "\"formatVersion\":2,\"mode\":\"form\"")
            .replace("\"game\":", "\"description\":\"$note\",\"game\":")
        val state = MemoryEditorUiState(
            visible = true, connected = true, supported = true,
            scriptsTab = true, runtimeToken = 9208L, scriptsLoaded = true,
            scripts = listOf(MemoryScriptRow("sample.coins", "Sample Coins", document)),
        )
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(state, TestMemoryEditorActions()) } }
        rule.onNodeWithText(note).assertIsDisplayed()
        rule.onNodeWithText("Sample Coins").performClick()
        rule.onNodeWithText("Description").assertIsDisplayed()
        rule.onNodeWithText(note).assertIsDisplayed()
    }

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


    @Test fun formLoadsActualValueAndSubmitsVerifiedSnapshot() {
        val document = sample.replace(
            "\"formatVersion\":1", "\"formatVersion\":2,\"mode\":\"form\"")
        val applied = mutableListOf<Pair<Map<String, String>, List<String>?>>()
        val actions = object : TestMemoryEditorActions() {
            override fun readCurrentMemoryRecipe(
                document: String, inputs: Map<String, String>,
                onReady: (List<MemoryRecipeReadValue>?, String?) -> Unit,
            ) = onReady(listOf(MemoryRecipeReadValue("coins", "10", "test-receipt")), null)
            override fun runMemoryRecipeChecked(
                document: String, inputs: Map<String, String>, freeze: Boolean,
                expectedValues: List<String>?, snapshotTicket: String?,
                onComplete: (Boolean) -> Unit,
            ) {
                assertFalse(freeze)
                assertEquals("test-receipt", snapshotTicket)
                applied += inputs to expectedValues
                onComplete(true)
            }
        }
        val state = MemoryEditorUiState(
            visible = true, connected = true, supported = true, writeSupported = true,
            scriptsTab = true, runtimeToken = 92501L, scriptsLoaded = true,
            scripts = listOf(MemoryScriptRow("sample.coins", "Sample Coins", document)),
        )
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(state, actions) } }
        rule.onNodeWithText("Sample Coins").performClick()
        rule.onNodeWithTag("memory-script-input-coins").assertTextContains("10")
        rule.onNodeWithTag("memory-script-input-coins").performTextReplacement("37")
        rule.onNodeWithText("Refresh").performClick()
        rule.onNodeWithTag("memory-script-input-coins").assertTextContains("37")
        rule.onNodeWithTag("memory-script-apply").performClick()
        rule.runOnIdle {
            assertEquals("37", applied.single().first["coins"])
            assertEquals(listOf("10"), applied.single().second)
        }
    }

    @Test fun oneShotQuickRunIsSingleFlightUntilCompletion() {
        val document = sample.replace(
            "\"formatVersion\":1", "\"formatVersion\":2,\"mode\":\"oneShot\"")
            .replace("\"max\":999}", "\"max\":999,\"default\":100}")
        var calls = 0
        var complete: ((Boolean) -> Unit)? = null
        val actions = object : TestMemoryEditorActions() {
            override fun runMemoryRecipeChecked(
                document: String, inputs: Map<String, String>, freeze: Boolean,
                expectedValues: List<String>?, snapshotTicket: String?,
                onComplete: (Boolean) -> Unit,
            ) {
                assertFalse(freeze)
                assertEquals(null, expectedValues)
                calls++
                complete = onComplete
            }
        }
        val state = MemoryEditorUiState(
            visible = true, connected = true, supported = true, writeSupported = true,
            scriptsTab = true, runtimeToken = 92502L, scriptsLoaded = true,
            scripts = listOf(MemoryScriptRow("sample.coins", "Sample Coins", document)),
        )
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(state, actions) } }
        rule.onNodeWithTag("memory-script-run-sample.coins").performClick()
        rule.onNodeWithTag("memory-script-run-sample.coins").assertIsNotEnabled()
        rule.runOnIdle {
            assertEquals(1, calls)
            complete?.invoke(true)
        }
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
