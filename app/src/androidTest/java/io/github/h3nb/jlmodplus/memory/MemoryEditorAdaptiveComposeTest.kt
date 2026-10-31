/* Licensed under the Apache License, Version 2.0.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0. */
package io.github.h3nb.jlmodplus.memory

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MemoryEditorAdaptiveComposeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shortInspectorWithLargeTextScrollsToTheLastEditableValue() {
        val snapshot = inspectorSnapshot()
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 430.dp))) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    JLModPlusTheme {
                        RuntimeInspectorTab(
                            MemoryEditorUiState(inspector = snapshot, writeSupported = true),
                            TestMemoryEditorActions(),
                        )
                    }
                }
            }
        }

        // A semantic scroll-to alone would miss the old userScrollEnabled=false regression.
        repeat(3) { rule.onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        rule.onNodeWithText("Value 4").assertIsDisplayed().performClick()
        rule.onNodeWithText("Apply").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithText("Value 4").assertIsDisplayed()
    }

    @Test fun shortLargeTextSearchKeepsKeypadEditingAndScanActionReachableWithoutIme() {
        var submitted: String? = null
        var dialogView: android.view.View? = null
        val actions = object : TestMemoryEditorActions() {
            override fun nextScan(value: String, secondValue: String, predicate: Int, compare: Int, type: Int) {
                submitted = value
            }
        }
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(640.dp, 320.dp))) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    JLModPlusTheme(darkTheme = true) {
                        RuntimeKnownSearchDialog(
                            MemoryEditorUiState(connected = true, supported = true, sessionStage = MemorySessionStage.CANDIDATES),
                            actions, onDismiss = {},
                        )
                        dialogView = LocalView.current
                    }
                }
            }
        }
        val field = rule.onNode(hasSetTextAction())
        field.performTextReplacement("42")
        field.performTextReplacement("invalid")
        field.assertTextContains("42")
        rule.onNodeWithText("←").performScrollTo().performClick()
        rule.onNodeWithText("7").performScrollTo().performClick()
        field.assertTextContains("472")
        rule.onNodeWithText("⌫").performScrollTo().performClick()
        field.assertTextContains("42")
        rule.onNodeWithText("Next scan").assertIsDisplayed().assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals("42", submitted)
            assertFalse(ViewCompat.getRootWindowInsets(requireNotNull(dialogView))
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true)
        }
    }

    @Test fun returningToEitherOriginTabClosesLoadedAndPendingInspector() {
        // Exercise the actual controller; no service connection or fake navigation reducer.
        rule.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val controller = MemoryEditorComposeController(ComposeView(context), 1L) {}
            val setter = controller.javaClass.getDeclaredMethod("setState", MemoryEditorUiState::class.java)
                .apply { isAccessible = true }
            val getter = controller.javaClass.getDeclaredMethod("getState").apply { isAccessible = true }
            try {
                for (watch in listOf(false, true)) {
                    for (loading in listOf(false, true)) {
                        setter.invoke(controller, MemoryEditorUiState(
                            watchTab = watch,
                            inspector = if (loading) null else inspectorSnapshot(),
                            inspectorLoading = loading,
                            selected = setOf(1L),
                        ))
                        controller.setWatchTab(watch)
                        val state = getter.invoke(controller) as MemoryEditorUiState
                        assertNull(state.inspector)
                        assertFalse(state.inspectorLoading)
                        assertEquals(if (watch) MemoryEditorTab.WATCH else MemoryEditorTab.RESULTS, state.activeTab)
                        assertEquals(setOf(1L), state.selected)
                    }
                }
            } finally {
                controller.destroy()
            }
        }
    }

    @Test fun backLeavesInspectorBeforeClosingTheWorkspace() {
        val state = mutableStateOf(MemoryEditorUiState(visible = true, inspector = inspectorSnapshot()))
        var closed = false
        val actions = object : TestMemoryEditorActions() {
            override fun closeInspector() { state.value = state.value.copy(inspector = null) }
            override fun close() { closed = true }
        }
        rule.setContent { JLModPlusTheme { MemoryEditorRuntimeRoot(state.value, actions) } }
        pressBack()
        rule.onNodeWithContentDescription("Search")
            .assertIsSelected()
        rule.runOnIdle { assertFalse(closed) }
    }

    @Test fun changingWindowWidthKeepsTheOpenSearchAndItsInput() {
        val size = mutableStateOf(DpSize(720.dp, 360.dp))
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                JLModPlusTheme {
                    MemoryEditorRuntimeRoot(MemoryEditorUiState(visible = true, connected = true, supported = true), TestMemoryEditorActions())
                }
            }
        }
        rule.onNodeWithContentDescription("Search known values").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("31415")
        rule.runOnIdle { size.value = DpSize(360.dp, 640.dp) }
        rule.onNode(hasSetTextAction()).assertTextContains("31415")
        rule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        rule.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    @Test fun modalSurfaceIsCenteredInSafeViewportAndDoesNotCoverPeek() {
        val window = mutableStateOf(DpSize(720.dp, 360.dp))
        val wideForm = mutableStateOf(true)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(window.value)) {
                JLModPlusTheme {
                    MemoryEditorDialog(
                        onDismissRequest = {},
                        maxDialogWidth = if (wideForm.value) 720.dp else 400.dp,
                        title = { Text("Dialog title") },
                        text = { Text("Modal content") },
                        confirmButton = { TextButton(onClick = {}) { Text("OK") } },
                    )
                }
            }
        }
        for ((size, form) in listOf(DpSize(720.dp, 360.dp) to true, DpSize(360.dp, 640.dp) to false)) {
            rule.runOnIdle { window.value = size; wideForm.value = form }
            val viewport = rule.onNodeWithTag("memory-editor-dialog-viewport").getUnclippedBoundsInRoot()
            val surface = rule.onNodeWithTag("adaptive-dialog-surface").getUnclippedBoundsInRoot()
            val peek = rule.onNodeWithTag("memory-editor-peek").getUnclippedBoundsInRoot()
            assertEquals("Dialog centers in the viewport above the fixed Peek target",
                (viewport.top.value + peek.top.value) / 2f,
                (surface.top.value + surface.bottom.value) / 2f, 2f)
            assertTrue("Peek must not cover a dialog action", surface.bottom <= peek.top)
        }
    }

    @Test fun wideLandscapeCentersDialogAndKeepsPeekBesideIt() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(840.dp, 360.dp))) {
                JLModPlusTheme {
                    MemoryEditorDialog(
                        onDismissRequest = {},
                        title = { Text("Search") },
                        text = { Column { repeat(24) { Text("Search option $it") } } },
                        confirmButton = { TextButton(onClick = {}) { Text("Apply") } },
                    )
                }
            }
        }
        val viewport = rule.onNodeWithTag("memory-editor-dialog-viewport").getUnclippedBoundsInRoot()
        val surface = rule.onNodeWithTag("adaptive-dialog-surface").getUnclippedBoundsInRoot()
        val peek = rule.onNodeWithTag("memory-editor-peek").getUnclippedBoundsInRoot()
        assertEquals(
            "Wide dialog should remain centered on the whole safe viewport",
            (viewport.top.value + viewport.bottom.value) / 2f,
            (surface.top.value + surface.bottom.value) / 2f, 2f,
        )
        assertTrue("Peek must have a clear side lane", surface.right <= peek.left)
        assertTrue("Wide landscape dialog should use available height",
            surface.bottom - surface.top > (viewport.bottom - viewport.top) * 0.75f)
        rule.onNodeWithText("Apply").assertIsDisplayed()
    }

    @Test fun longLandscapeDialogUsesMostAvailableHeightWithoutCoveringPeek() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(720.dp, 360.dp))) {
                JLModPlusTheme {
                    MemoryEditorDialog(
                        onDismissRequest = {},
                        title = { Text("Long dialog") },
                        text = {
                            Column {
                                repeat(30) { Text("Option $it must remain scrollable") }
                            }
                        },
                        confirmButton = { TextButton(onClick = {}) { Text("Apply") } },
                    )
                }
            }
        }
        val viewport = rule.onNodeWithTag("memory-editor-dialog-viewport").getUnclippedBoundsInRoot()
        val surface = rule.onNodeWithTag("adaptive-dialog-surface").getUnclippedBoundsInRoot()
        val peek = rule.onNodeWithTag("memory-editor-peek").getUnclippedBoundsInRoot()
        assertTrue("Tall landscape dialog should use the space above Peek",
            surface.bottom - surface.top > (viewport.bottom - viewport.top) * 0.65f)
        assertTrue(surface.bottom <= peek.top)
        rule.onNodeWithText("Apply").assertIsDisplayed()
    }

    @Test fun workspaceSurfaceFillsTheWindowInPortraitAndLandscape() {
        val windowSize = mutableStateOf(DpSize(720.dp, 360.dp))
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(windowSize.value)) {
                JLModPlusTheme {
                    Box(Modifier.fillMaxSize().testTag("memory-editor-host")) {
                        MemoryEditorRuntimeRoot(
                            MemoryEditorUiState(visible = true, connected = true, supported = true),
                            TestMemoryEditorActions(),
                        )
                    }
                }
            }
        }
        for (size in listOf(DpSize(720.dp, 360.dp), DpSize(360.dp, 640.dp))) {
            rule.runOnIdle { windowSize.value = size }
            val viewport = rule.onNodeWithTag("memory-editor-host").getUnclippedBoundsInRoot()
            val workspace = rule.onNodeWithTag("memory-editor-workspace").getUnclippedBoundsInRoot()
            assertEquals("Workspace must not retain popup-style side margins", viewport.left.value, workspace.left.value, 1f)
            assertEquals(viewport.right.value, workspace.right.value, 1f)
            assertEquals(viewport.top.value, workspace.top.value, 1f)
            assertEquals(viewport.bottom.value, workspace.bottom.value, 1f)
            rule.onNodeWithTag("memory-editor-peek").assertIsDisplayed()
        }
    }

    @Test fun portraitHeaderAndTabsDoNotOverlap() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp))) {
                JLModPlusTheme {
                    MemoryEditorRuntimeRoot(
                        MemoryEditorUiState(visible = true, connected = true, supported = true),
                        TestMemoryEditorActions(),
                    )
                }
            }
        }
        val title = rule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.memory_editor),
        ).getUnclippedBoundsInRoot()
        val tab = rule.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.memory_editor_search_tab),
        ).getUnclippedBoundsInRoot()
        assertTrue("Title and tabs must occupy separate rows in portrait", title.bottom <= tab.top)
    }

    @Test fun footerGlyphsAlignWithHeaderInPortraitAndLandscape() {
        val size = mutableStateOf(DpSize(360.dp, 640.dp))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                JLModPlusTheme {
                    MemoryEditorRuntimeRoot(
                        MemoryEditorUiState(visible = true, connected = true, supported = true),
                        TestMemoryEditorActions(),
                    )
                }
            }
        }
        for (window in listOf(DpSize(360.dp, 640.dp), DpSize(720.dp, 360.dp))) {
            rule.runOnIdle { size.value = window }
            val title = rule.onNodeWithText(context.getString(R.string.memory_editor))
                .getUnclippedBoundsInRoot()
            val settings = rule.onNodeWithContentDescription(
                context.getString(R.string.memory_editor_panel_opacity),
            ).getUnclippedBoundsInRoot()
            val close = rule.onNodeWithContentDescription(
                context.getString(R.string.memory_editor_close),
            ).getUnclippedBoundsInRoot()
            val peek = rule.onNodeWithTag("memory-editor-peek").getUnclippedBoundsInRoot()
            assertEquals("Settings glyph aligns with title", title.left.value, settings.left.value, 2f)
            assertEquals("Peek aligns with close",
                (close.left.value + close.right.value) / 2f,
                (peek.left.value + peek.right.value) / 2f, 2f)
        }
    }

    @Test fun selectCurrentPageRemainsAvailableInLandscapeAndPortrait() {
        val size = mutableStateOf(DpSize(720.dp, 360.dp))
        val rows = listOf(1L, 2L).map { id ->
            MemoryResultRow(
                id = id, valueText = "2", locationText = "test",
                aliasMask = 1 shl MemoryEngineContract.TYPE_INT,
                primaryType = MemoryEngineContract.TYPE_INT,
                state = MemoryEngineContract.CANDIDATE_STABLE, relocations = 0,
            )
        }
        val state = mutableStateOf(
            MemoryEditorUiState(
                visible = true, connected = true, supported = true, writeSupported = true,
                resultCount = 340, results = rows, sessionStage = MemorySessionStage.CANDIDATES,
            ),
        )
        val actions = object : TestMemoryEditorActions() {
            override fun selectVisible() {
                state.value = state.value.copy(selected = rows.map { it.id }.toSet())
            }
            override fun clearSelection() { state.value = state.value.copy(selected = emptySet()) }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val select = context.getString(R.string.memory_editor_select_visible)
        val clear = context.getString(R.string.memory_editor_clear_selection)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(size.value)) {
                JLModPlusTheme { MemoryEditorRuntimeRoot(state.value, actions) }
            }
        }
        for (window in listOf(DpSize(720.dp, 360.dp), DpSize(360.dp, 640.dp))) {
            rule.runOnIdle { size.value = window }
            rule.onNodeWithContentDescription(select).assertIsDisplayed().performClick()
            rule.runOnIdle { assertEquals(setOf(1L, 2L), state.value.selected) }
            rule.onNodeWithContentDescription(clear).assertIsDisplayed().performClick()
            rule.runOnIdle { assertTrue(state.value.selected.isEmpty()) }
        }
    }

    @Test fun watchTabKeepsSelectPageAccessible() {
        val watches = listOf(1L, 2L).map { id ->
            MemoryWatchRow(
                id = id, type = MemoryEngineContract.TYPE_INT,
                valueText = "2", initialValueText = "2", previousValueText = "2",
                label = "watch", locationText = "test",
                state = MemoryEngineContract.CANDIDATE_STABLE,
                relocations = 0,
            )
        }
        val state = mutableStateOf(MemoryEditorUiState(
            visible = true, connected = true, supported = true,
            watchTab = true, watches = watches,
        ))
        val actions = object : TestMemoryEditorActions() {
            override fun selectVisible() {
                state.value = state.value.copy(selected = watches.map { it.id }.toSet())
            }
        }
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.memory_editor_select_visible)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp))) {
                JLModPlusTheme { MemoryEditorRuntimeRoot(state.value, actions) }
            }
        }
        rule.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(setOf(1L, 2L), state.value.selected) }
    }

    @Test fun spaciousLandscapeShowsExtraActionsDirectlyWithoutMore() {
        val result = MemoryResultRow(
            id = 1, valueText = "2", locationText = "test",
            aliasMask = 1 shl MemoryEngineContract.TYPE_INT,
            primaryType = MemoryEngineContract.TYPE_INT,
            state = MemoryEngineContract.CANDIDATE_STABLE, relocations = 0,
        )
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(840.dp, 360.dp))) {
                JLModPlusTheme {
                    MemoryEditorRuntimeRoot(MemoryEditorUiState(
                        visible = true, connected = true, supported = true,
                        writeSupported = true, resultCount = 1,
                        results = listOf(result), selected = setOf(1),
                        sessionStage = MemorySessionStage.CANDIDATES,
                    ), TestMemoryEditorActions())
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithContentDescription(context.getString(R.string.memory_editor_inspect_memory))
            .assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.memory_editor_remove))
            .assertIsDisplayed()
        rule.onNodeWithContentDescription(context.getString(R.string.more))
            .assertDoesNotExist()
    }

    @Test fun constrainedWindowKeepsSecondaryActionsInMore() {
        val result = MemoryResultRow(
            id = 1, valueText = "2", locationText = "test",
            aliasMask = 1 shl MemoryEngineContract.TYPE_INT,
            primaryType = MemoryEngineContract.TYPE_INT,
            state = MemoryEngineContract.CANDIDATE_STABLE, relocations = 0,
        )
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(280.dp, 520.dp))) {
                JLModPlusTheme {
                    MemoryEditorRuntimeRoot(MemoryEditorUiState(
                        visible = true, connected = true, supported = true,
                        writeSupported = true, resultCount = 1,
                        results = listOf(result), selected = setOf(1),
                        sessionStage = MemorySessionStage.CANDIDATES,
                    ), TestMemoryEditorActions())
                }
            }
        }
        val more = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.more)
        rule.onNodeWithContentDescription(more).assertIsDisplayed().performClick()
        rule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getString(R.string.memory_editor_remove),
        ).assertIsDisplayed()
    }

    @Test fun roundedCornerPaddingAvoidsShiftingControlsByTheFullRadius() {
        // Normal rounded corners fit the glyph at the same x as the header icons.
        assertEquals(12.dp, memoryEditorCornerControlPadding(0.dp))
        assertEquals(12.dp, memoryEditorCornerControlPadding(60.dp))
        // An unusually large radius moves controls inward, not upward.
        assertTrue(memoryEditorCornerControlPadding(120.dp) > 12.dp)
        assertTrue(memoryEditorCornerControlPadding(120.dp) < 120.dp)
    }

    @Test fun paginationSharesTheBottomDockWithPeekInBothWindowShapes() {
        val count = 18_687L
        val nextPageLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.memory_editor_next_results_page_action)
        val windowSize = mutableStateOf(DpSize(720.dp, 360.dp))
        var nextCalls = 0
        val actions = object : TestMemoryEditorActions() {
            override fun nextPage() { nextCalls++ }
        }
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(windowSize.value)) {
                JLModPlusTheme {
                    MemoryEditorRuntimeRoot(
                        MemoryEditorUiState(visible = true, connected = true, supported = true,
                            resultCount = count, sessionStage = MemorySessionStage.CANDIDATES),
                        actions,
                    )
                }
            }
        }
        for (size in listOf(DpSize(720.dp, 360.dp), DpSize(360.dp, 640.dp))) {
            rule.runOnIdle { windowSize.value = size }
            val next = rule.onNodeWithContentDescription(nextPageLabel).assertIsDisplayed()
            val peek = rule.onNodeWithTag("memory-editor-peek").assertIsDisplayed()
            val nextBounds = next.getUnclippedBoundsInRoot()
            val peekBounds = peek.getUnclippedBoundsInRoot()
            assertEquals("Pagination and Peek must share the same footer baseline",
                peekBounds.top.value, nextBounds.top.value, 2f)
            next.performClick()
        }
        rule.runOnIdle { assertEquals(2, nextCalls) }
    }

    private fun inspectorSnapshot() = MemoryInspectorSnapshot(
        candidateId = 1L, type = MemoryEngineContract.TYPE_INT, label = "Values",
        logicalRows = List(5) { index ->
            MemoryInspectorLogicalRow(
                id = index + 1L, relativeOffset = index, type = MemoryEngineContract.TYPE_INT,
                state = MemoryEngineContract.CANDIDATE_STABLE,
                valueText = "123456789", initialValueText = "123456789", previousValueText = "123456789",
                label = "Value $index", expectedBits = 123456789L,
            )
        },
    )
}
