/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryCollectionRow
import io.github.h3nb.jlmodplus.librarydb.LibraryQuickView
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Observe the real pager's displayed row bounds; no private navigation or viewport test hooks. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class LibraryViewportNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listViewportsSurvivePagerRoundTrip() = verifyPagerRoundTrip(LibraryLayout.List)

    @Test
    fun gridViewportsSurvivePagerRoundTrip() = verifyPagerRoundTrip(LibraryLayout.Grid)

    private fun verifyPagerRoundTrip(layout: LibraryLayout) {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(layout), actions = host) }
            }
        }

        scrollAwayFromTop(APP_PREFIX)
        val apps = visibleRow(APP_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(rowMatcher(MEMBER_PREFIX)).fetchSemanticsNodes().isNotEmpty()
        }
        scrollAwayFromTop(MEMBER_PREFIX)
        val members = visibleRow(MEMBER_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(uiString(R.string.action_settings)).assertIsDisplayed()

        swipeToPreviousPage()
        composeRule.onNode(navigationMatcher(uiString(R.string.library_destination_apps))).assertIsDisplayed()
        assertViewport(members, MEMBER_PREFIX)
        swipeToPreviousPage()
        composeRule.onNode(navigationMatcher(uiString(R.string.library_destination_apps))).assertIsDisplayed()
        assertViewport(apps, APP_PREFIX)
    }

    @Test
    fun collectionViewportSurvivesHostIdReassignmentAndPrepend() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(state = libraryState(LibraryLayout.List), actions = host)
                }
            }
        }
        swipeToNextPage()
        composeRule.onNodeWithText(COLLECTION_NAME).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(rowMatcher(MEMBER_PREFIX)).fetchSemanticsNodes().isNotEmpty()
        }
        scrollAwayFromTop(MEMBER_PREFIX)
        val before = visibleRow(MEMBER_PREFIX)

        composeRule.runOnIdle { host.reassignMemberHostIds() }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(rowMatcher(MEMBER_PREFIX)).fetchSemanticsNodes().isNotEmpty()
        }
        assertViewport(before, MEMBER_PREFIX)
    }

    @Test
    fun distantNavigationBarTapSkipsCollectionInBothDirections() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }

        // Freeze frame advancement while tapping, then inspect every transition frame.
        // An animated Apps -> More transition incorrectly makes Collections visible.
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(navigationMatcher(uiString(R.string.library_destination_more)))
                .performClick()
            repeat(18) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodesWithText(COLLECTION_NAME).assertCountEquals(0)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(uiString(R.string.action_settings)).assertIsDisplayed()

        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(navigationMatcher(uiString(R.string.library_destination_apps)))
                .performClick()
            repeat(18) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.onAllNodesWithText(COLLECTION_NAME).assertCountEquals(0)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun collectionOverviewViewportSurvivesPagerRoundTrip() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        swipeToNextPage()
        scrollAwayFromTop(FOLDER_PREFIX)
        val folders = visibleRow(FOLDER_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(uiString(R.string.action_settings)).assertIsDisplayed()
        swipeToPreviousPage()
        assertViewport(folders, FOLDER_PREFIX)
    }

    @Test
    fun endOfLibraryViewportSurvivesDifferentFooterPagerRoundTrip() {
        val host = ViewportHost()
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        activeList(APP_PREFIX).performScrollToNode(hasText(rowTitle(APP_PREFIX, 70)))
        composeRule.waitForIdle()
        activeList(APP_PREFIX).performTouchInput {
            down(Offset(width * 0.5f, height * 0.85f))
            moveSmoothlyTo(Offset(width * 0.5f, height * 0.15f), durationMillis = 600)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        val appsNavigation = uiString(R.string.library_destination_apps)
        composeRule.onAllNodes(navigationMatcher(appsNavigation)).assertCountEquals(0)
        // Reach the actual end, including its trailing inset. Scroll-to-node only makes the row
        // visible and can stop with part of it inside the area revealed by hidden navigation.
        activeList(APP_PREFIX).performScrollToIndex(80)
        composeRule.waitForIdle()
        composeRule.onAllNodes(navigationMatcher(appsNavigation)).assertCountEquals(0)
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 79), useUnmergedTree = true).assertIsDisplayed()
        val hiddenApps = visibleRow(APP_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(COLLECTION_NAME).assertIsDisplayed()
        composeRule.onNode(navigationMatcher(appsNavigation)).assertIsDisplayed()
        activeList(FOLDER_PREFIX).performScrollToIndex(80)
        composeRule.waitForIdle()
        val folders = visibleRow(FOLDER_PREFIX)
        swipeToNextPage()
        composeRule.onNodeWithText(uiString(R.string.action_settings)).assertIsDisplayed()
        swipeToPreviousPage()
        assertViewport(folders, FOLDER_PREFIX)
        composeRule.onNode(navigationMatcher(appsNavigation)).assertIsDisplayed()
        swipeToPreviousPage()

        // Returning reveals navigation over the same viewport. The final app remains reachable
        // above it, without replaying an obsolete hidden-bar anchor.
        composeRule.onNode(navigationMatcher(appsNavigation)).assertIsDisplayed()
        val lastApp = composeRule.onNodeWithText(rowTitle(APP_PREFIX, 79), useUnmergedTree = true)
        lastApp.assertIsDisplayed()
        val navigationTop = composeRule.onNode(navigationMatcher(appsNavigation)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Returning navigation covered the final app", lastApp.fetchSemanticsNode().boundsInRoot.bottom <= navigationTop)
        assertViewport(hiddenApps, APP_PREFIX)
        val apps = visibleRow(APP_PREFIX)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        composeRule.onNode(navigationMatcher(appsNavigation)).assertIsDisplayed()
        assertViewport(apps, APP_PREFIX)
        swipeToNextPage()
        composeRule.onNode(navigationMatcher(appsNavigation)).assertIsDisplayed()
        assertViewport(folders, FOLDER_PREFIX)
    }

    @Test
    fun reverseScrollMovesHeaderWithDeepListRows() =
        verifyReverseScrollMovesHeaderWithRows(LibraryLayout.List)

    @Test
    fun reverseScrollMovesHeaderWithDeepGridRows() =
        verifyReverseScrollMovesHeaderWithRows(LibraryLayout.Grid)

    private fun verifyReverseScrollMovesHeaderWithRows(layout: LibraryLayout) {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(layout), actions = host) }
            }
        }
        // Deep enough that the expanding spacer is still well above the visible viewport.
        activeList(APP_PREFIX).performScrollToIndex(35)
        composeRule.waitForIdle()
        activeList(APP_PREFIX).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.65f)
            down(start)
            moveSmoothlyTo(Offset(start.x, start.y - height * 0.12f), durationMillis = 750)
            advanceEventTime(350)
            up()
        }
        composeRule.waitForIdle()
        val chip = uiString(R.string.library_filter_favorites)
        val headerBefore = composeRule.onNodeWithText(chip).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val before = visibleRow(APP_PREFIX)

        activeList(APP_PREFIX).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.38f)
            down(start)
            moveSmoothlyTo(Offset(start.x, start.y + height * 0.07f), durationMillis = 750)
            advanceEventTime(350)
            up()
        }
        composeRule.waitForIdle()
        val headerAfter = composeRule.onNodeWithText(chip).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val rowAfter = composeRule.onNodeWithText(before.title, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        val headerTravel = headerAfter - headerBefore
        val rowTravel = rowAfter - before.top
        val minimumTravel = with(composeRule.density) { 16.dp.toPx() }
        assertTrue("Header did not expand on reverse drag: $headerTravel px",
            headerTravel > minimumTravel)
        assertTrue("MIDlets stayed fixed while header expanded: $rowTravel px",
            rowTravel > minimumTravel)
        val allowedDifference = with(composeRule.density) { 6.dp.toPx() }
        assertEquals("Header and MIDlets must travel together", headerTravel, rowTravel,
            allowedDifference)

        // Reverse again while the chrome is still only partially revealed. Both positions
        // must reverse together; pre-consuming a deep upward drag used to freeze MIDlet rows.
        activeList(APP_PREFIX).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.6f)
            down(start)
            moveSmoothlyTo(Offset(start.x, start.y - height * 0.035f), durationMillis = 750)
            advanceEventTime(350)
            up()
        }
        composeRule.waitForIdle()
        val headerAfterReversal = composeRule.onNodeWithText(chip).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val rowAfterReversal = composeRule.onNodeWithText(before.title, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        val headerReverseTravel = headerAfterReversal - headerAfter
        val rowReverseTravel = rowAfterReversal - rowAfter
        assertTrue("Header did not collapse on drag reversal: $headerReverseTravel px",
            headerReverseTravel < -minimumTravel / 2)
        assertTrue("MIDlets froze while header collapsed: $rowReverseTravel px",
            rowReverseTravel < -minimumTravel / 2)
        assertEquals("Header and MIDlets diverged on drag reversal",
            headerReverseTravel, rowReverseTravel, allowedDifference)
    }

    @Test
    fun quickFilterPreservesPartialLibraryHeaderInList() =
        verifyQuickFilterPreservesPartialHeader(LibraryLayout.List)

    @Test
    fun quickFilterPreservesPartialLibraryHeaderInGrid() =
        verifyQuickFilterPreservesPartialHeader(LibraryLayout.Grid)

    private fun verifyQuickFilterPreservesPartialHeader(layout: LibraryLayout) {
        val initial = libraryState(layout)
        val visibleState = mutableStateOf(initial)
        val host = ViewportHost().apply {
            onQuickViewChanged = { quickView ->
                visibleState.value = visibleState.value.copy(
                    quickView = quickView,
                    // A single filtered result forces the Lazy viewport to clamp at the top.
                    // That data-driven transition must not reopen the search/header.
                    apps = if (quickView == LibraryQuickView.Favorites) initial.apps.takeLast(1)
                        else initial.apps,
                )
            }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = visibleState.value, actions = host) }
            }
        }

        // The search row is obscured, while the lower quick-filter row is still in the safe area.
        activeList(APP_PREFIX).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.7f)
            down(start)
            moveSmoothlyTo(
                Offset(start.x, start.y - height * 0.155f),
                durationMillis = 800,
            )
            advanceEventTime(250)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        val favorites = uiString(R.string.library_filter_favorites)
        val filterTop = composeRule.onNodeWithText(favorites)
            .assertIsDisplayed()
            .assertIsEnabled()
            .fetchSemanticsNode().boundsInRoot.top

        composeRule.onNodeWithText(favorites).performClick()
        composeRule.waitForIdle()
        assertEquals(LibraryQuickView.Favorites, visibleState.value.quickView)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(
            "Quick filter changed the collapsed header position",
            filterTop,
            composeRule.onNodeWithText(favorites)
                .assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot.top,
            1f,
        )
        val firstFiltered = composeRule.onNodeWithText(rowTitle(APP_PREFIX, 79), useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true)
            .assertDoesNotExist()
        // The visible header bottom and first result must remain adjacent even when
        // filtered data cannot scroll enough to consume the original header spacer.
        val filterBottom = composeRule.onNodeWithText(favorites)
            .fetchSemanticsNode().boundsInRoot.bottom
        val gap = firstFiltered.fetchSemanticsNode().boundsInRoot.top - filterBottom
        val maxGapDp = if (layout == LibraryLayout.List) 100.dp else 180.dp
        assertTrue(
            "Quick filter left an oversized header-to-results gap: $gap px",
            gap in 0f..with(composeRule.density) { maxGapDp.toPx() },
        )

        // The same chrome position survives switching back to the larger projection.
        composeRule.onNodeWithText(uiString(R.string.library_filter_all)).performClick()
        composeRule.waitForIdle()
        assertEquals(LibraryQuickView.All, visibleState.value.quickView)
        composeRule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        assertEquals(
            filterTop,
            composeRule.onNodeWithText(favorites).fetchSemanticsNode().boundsInRoot.top,
            1f,
        )

        // A single filtered MIDlet can no longer consume backward scroll. Explicitly
        // pulling down must still recover the hidden search rather than trapping the user.
        // Before the second filter switch, drive the unfiltered list deeper so
        // the next result projection must begin at its first item rather than
        // keeping the previous MIDlet scroll anchor.
        composeRule.onNodeWithText(favorites).performClick()
        composeRule.waitForIdle()
        activeList(APP_PREFIX).performTouchInput {
            down(Offset(width * 0.5f, height * 0.45f))
            moveSmoothlyTo(
                Offset(width * 0.5f, height * 0.85f),
                durationMillis = 500,
            )
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun quickFilterRestartsDeepResultsWithoutRevealingHeader() {
        val initial = libraryState(LibraryLayout.List)
        val visibleState = mutableStateOf(initial)
        val host = ViewportHost().apply {
            onQuickViewChanged = { filter ->
                visibleState.value = visibleState.value.copy(
                    quickView = filter,
                    apps = if (filter == LibraryQuickView.Favorites) initial.apps.drop(60)
                        else initial.apps,
                )
            }
        }
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = visibleState.value, actions = host) }
            }
        }
        activeList(APP_PREFIX).performScrollToNode(hasText(rowTitle(APP_PREFIX, 45)))
        composeRule.waitForIdle()
        assertTrue("Deep scroll was not reached", visibleRow(APP_PREFIX).title != rowTitle(APP_PREFIX, 0))
        // A deep semantics scroll may leave the header hidden. Pull the chip back into
        // reach before tapping it, while preserving a deep MIDlet viewport.
        activeList(APP_PREFIX).performTouchInput {
            val start = Offset(width * 0.5f, height * 0.35f)
            down(start)
            moveSmoothlyTo(Offset(start.x, start.y + height * 0.18f), durationMillis = 750)
            advanceEventTime(350)
            up()
        }
        composeRule.waitForIdle()
        val favorites = composeRule.onNodeWithText(uiString(R.string.library_filter_favorites))
            .assertIsDisplayed()
            .assertIsEnabled()
        val chipTop = favorites.fetchSemanticsNode().boundsInRoot.top

        favorites.performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 60), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(chipTop, favorites.fetchSemanticsNode().boundsInRoot.top, 1f)

        composeRule.onNodeWithText(uiString(R.string.library_filter_all)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(chipTop, favorites.fetchSemanticsNode().boundsInRoot.top, 1f)
    }

    @Test
    fun backDismissesFocusedLibrarySearchBeforeLeavingScreen() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme {
                    LibraryScreen(
                        state = libraryState(LibraryLayout.List).copy(appliedFilter = "Library"),
                        actions = host,
                    )
                }
            }
        }
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        pressBack()
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onNode(hasSetTextAction())
                .fetchSemanticsNode().config[SemanticsProperties.Focused] == false
        }
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        composeRule.onNode(hasSetTextAction()).assertTextEquals("Library")
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun backDismissesLibrarySearchAfterImeBecomesVisible() {
        val host = ViewportHost()
        val imeVisible = mutableStateOf(false)
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { imeVisible.value = imeBottom > 0 }
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeRule.waitUntil(timeoutMillis = 5_000) { imeVisible.value }
        composeRule.waitForIdle()

        pressBack()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !imeVisible.value && composeRule.onNode(hasSetTextAction())
                .fetchSemanticsNode().config[SemanticsProperties.Focused] == false
        }
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun searchRevealsCompleteHeaderAndStartsResultsAtTop() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        scrollAwayFromTop(APP_PREFIX)
        activeList(APP_PREFIX).performTouchInput {
            down(Offset(width * 0.5f, height * 0.25f))
            moveSmoothlyTo(Offset(width * 0.5f, height * 0.8f), durationMillis = 500)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performClick().performTextInput("Library")
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(uiString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        val searchBottom = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.bottom
        val resultTop = composeRule.onNodeWithText(rowTitle(APP_PREFIX, 0), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.top
        val gapPx = resultTop - searchBottom
        val maximumGapPx = with(composeRule.density) { 100.dp.toPx() }
        assertTrue("Search gap $gapPx px must be within 0..$maximumGapPx px",
            gapPx in 0f..maximumGapPx)
        // Settled tab navigation closes search focus/IME so another surface remains usable.
        swipeToNextPage()
        composeRule.onNodeWithText(COLLECTION_NAME).assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onNode(navigationMatcher(uiString(R.string.library_destination_apps))).isDisplayed()
        }
        composeRule.onNode(navigationMatcher(uiString(R.string.library_destination_apps))).assertIsDisplayed()
    }

    @Test
    fun reversedPagerDragPreservesSelection() {
        val host = ViewportHost()
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = libraryState(LibraryLayout.List), actions = host) }
            }
        }
        val title = rowTitle(APP_PREFIX, 0)
        composeRule.onNodeWithText(title).performTouchInput { longClick() }
        composeRule.onNodeWithText(uiString(R.string.library_action_select)).performClick()
        val checkbox = uiString(R.string.library_selection_checkbox_description, title)
        composeRule.onNodeWithContentDescription(checkbox).assertIsOn()

        // Hold beyond the midpoint so currentPage changes and Compose observes that transient page.
        // Returning before release leaves settledPage unchanged and must retain selection.
        composeRule.onRoot().performTouchInput {
            down(Offset(width * 0.85f, height * 0.55f))
            moveSmoothlyTo(Offset(width * 0.15f, height * 0.55f), durationMillis = 400)
        }
        composeRule.waitForIdle()
        // Offscreen pages clear their semantics, so this proves the indicated page changed.
        composeRule.onNodeWithText(COLLECTION_NAME).assertExists()
        composeRule.onRoot().performTouchInput {
            moveSmoothlyTo(Offset(width * 0.85f, height * 0.55f), durationMillis = 400)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(checkbox).assertIsDisplayed().assertIsOn()
    }

    @Test
    fun viewportRestoresAcrossRecreationAndResetsForDifferentLibrary() {
        val host = ViewportHost()
        val state = mutableStateOf(libraryState(LibraryLayout.List))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)),
            ) {
                JLModPlusTheme { LibraryScreen(state = state.value, actions = host) }
            }
        }
        scrollAwayFromTop(APP_PREFIX)
        val apps = visibleRow(APP_PREFIX)
        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        assertViewport(apps, APP_PREFIX)

        // Database-local ids are deliberately reused; a different workdir needs a fresh viewport.
        composeRule.runOnIdle { state.value = state.value.copy(libraryScope = "/test/library-b") }
        composeRule.waitForIdle()
        assertEquals(rowTitle(APP_PREFIX, 0), visibleRow(APP_PREFIX).title)
        composeRule.onNodeWithContentDescription(uiString(R.string.app_name)).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    private fun scrollAwayFromTop(prefix: String) {
        activeList(prefix).performScrollToNode(hasText(rowTitle(prefix, 25)))
        composeRule.waitForIdle()
        activeList(prefix).performTouchInput {
            down(Offset(width * 0.5f, height * 0.55f))
            moveSmoothlyTo(Offset(width * 0.5f, height * 0.15f), durationMillis = 250)
            advanceEventTime(150)
            up()
        }
        composeRule.waitForIdle()
        assertTrue(visibleRow(prefix).title != rowTitle(prefix, 0))
    }

    private fun TouchInjectionScope.moveSmoothlyTo(end: Offset, durationMillis: Long) {
        val start = checkNotNull(currentPosition())
        // Multiple timed MOVE events cross touch slop and produce a continuous drag.
        for (step in 1..25) {
            moveTo(start + (end - start) * (step / 25f), delayMillis = durationMillis / 25)
        }
    }

    private fun navigationMatcher(label: String) =
        hasClickAction() and (hasText(label) or hasContentDescription(label))

    private fun rowMatcher(prefix: String) = SemanticsMatcher("row title starts with $prefix") { node ->
        SemanticsProperties.Text in node.config &&
            node.config[SemanticsProperties.Text].any { it.text.startsWith(prefix) }
    }

    private fun activeList(prefix: String): SemanticsNodeInteraction =
        // Pager and Lazy content can both expose ScrollBy; the innermost matching node is last.
        composeRule.onAllNodes(
            hasScrollAction() and hasAnyDescendant(rowMatcher(prefix)),
            useUnmergedTree = true,
        ).onLast()

    private data class VisibleRow(val title: String, val top: Float)

    private fun visibleRow(prefix: String): VisibleRow {
        composeRule.waitForIdle()
        val viewport = activeList(prefix).fetchSemanticsNode().boundsInRoot
        val candidates = composeRule.onAllNodes(rowMatcher(prefix), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val nodes = candidates.filter { node ->
            val bounds = node.boundsInRoot
            // Clipped nodes touch a viewport edge; only interior rendered title bounds qualify.
            bounds.width > 0 && bounds.height > 0 &&
                bounds.top > viewport.top && bounds.bottom < viewport.bottom &&
                composeRule.onNode(
                    SemanticsMatcher("placed row ${node.id}") { it.id == node.id },
                    useUnmergedTree = true,
                ).isDisplayed()
        }
        val first = checkNotNull(nodes.minWithOrNull(
            compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }),
        )) {
            "No fully visible $prefix row in active viewport $viewport"
        }
        val title = first.config[SemanticsProperties.Text].first { it.text.startsWith(prefix) }.text
        val visible = VisibleRow(title, first.boundsInRoot.top)
        return visible
    }

    private fun assertViewport(expected: VisibleRow, prefix: String) {
        val actual = visibleRow(prefix)
        assertEquals("First visible row changed after returning to $prefix", expected.title, actual.title)
        assertEquals("Displayed row moved after returning to $prefix", expected.top, actual.top, 1f)
    }

    private fun swipeToNextPage() {
        composeRule.onRoot().performTouchInput { swipeLeft(durationMillis = 500) }
        composeRule.waitForIdle()
    }

    private fun swipeToPreviousPage() {
        composeRule.onRoot().performTouchInput { swipeRight(durationMillis = 500) }
        composeRule.waitForIdle()
    }

    private class ViewportHost : LibraryCollectionsHost {
        private val members = rows(MEMBER_PREFIX, 1)
        var onQuickViewChanged: (LibraryQuickView) -> Unit = {}
        override fun onQuickView(quickView: LibraryQuickView) = onQuickViewChanged(quickView)
        private val store = LibraryCollectionsUiStore().apply {
            publishCollections(List(80) { index ->
                LibraryCollectionRow(
                    id = COLLECTION_ID + index,
                    name = rowTitle(FOLDER_PREFIX, index),
                    sortOrder = index,
                    createdAt = 1L,
                    appCount = members.size,
                )
            })
        }
        override fun collectionsStore() = store
        override fun onOpenCollection(collectionId: Long) = store.showMembers(collectionId, members)
        fun reassignMemberHostIds() {
            val inserted = LibraryAppUiItem(
                id = 9_999,
                title = "Collection app 0",
                author = "Vendor",
                version = "1.0",
                iconPath = null,
                canReinstall = true,
                databaseId = 9_999L,
            )
            // The new first row shifts every retained item by one index. Correct restoration
            // needs the stable database key, not the old list index or transient host ID.
            store.showMembers(
                COLLECTION_ID,
                listOf(inserted) + members.mapIndexed { index, app ->
                    app.copy(id = 1_001 + index)
                },
            )
        }
        override fun onDismissCollectionMembers() = store.dismissMembers()
        override fun onPrepareCollectionAppPicker() = store.publishAllApps(members)
        override fun onCreateCollection(name: String) = Unit
        override fun onRenameCollection(collectionId: Long, name: String) = Unit
        override fun onDeleteCollection(collectionId: Long) = Unit
        override fun onRequestAddToCollection(appId: Int) = Unit
        override fun onDismissAddToCollection() = Unit
        override fun onAddAppToCollection(appId: Int, collectionId: Long) = Unit
        override fun onSetCollectionMembership(
            appId: Int,
            collectionId: Long,
            included: Boolean,
            callback: CollectionMembershipResultCallback,
        ) = callback.onResult(true)
        override fun onAddAppsToCollection(appIds: Set<Long>, collectionId: Long) = Unit
        override fun onRemoveAppsFromCollection(appIds: Set<Long>, collectionId: Long) = Unit
        override fun onRemoveAppFromCollection(appId: Int, collectionId: Long) = Unit
        override fun onSearch(query: String) = Unit
        override fun onLayoutChange(layout: LibraryLayout) = Unit
        override fun onSort(sortIndex: Int) = Unit
        override fun onInstall() = Unit
        override fun onOpenApp(appId: Int) = Unit
        override fun onAddShortcut(appId: Int) = Unit
        override fun onRename(appId: Int, title: String) = Unit
        override fun onOpenAppSettings(appId: Int) = Unit
        override fun onReinstall(appId: Int) = Unit
        override fun onDelete(appId: Int) = Unit
        override fun onOpenSettings() = Unit
        override fun onOpenProfiles() = Unit
        override fun onOpenCrashReports() = Unit
        override fun onSaveLog() = Unit
        override fun onRetryLibrary() = Unit
    }

    private companion object {
        const val APP_PREFIX = "Library app "
        const val MEMBER_PREFIX = "Collection app "
        const val COLLECTION_NAME = "Viewport collection"
        const val FOLDER_PREFIX = COLLECTION_NAME
        const val COLLECTION_ID = 7L

        fun rowTitle(prefix: String, index: Int): String = when {
            prefix == FOLDER_PREFIX && index == 0 -> COLLECTION_NAME
            prefix == FOLDER_PREFIX -> "$prefix ${index.toString().padStart(2, '0')}"
            else -> prefix + index.toString().padStart(2, '0')
        }
        fun rows(prefix: String, startId: Int) = List(80) { index ->
            LibraryAppUiItem(startId + index, rowTitle(prefix, index), "Vendor", "1.0", null, true)
        }
        fun libraryState(layout: LibraryLayout) = LibraryUiState(
            loading = false,
            databaseControlsReady = true,
            layout = layout,
            apps = rows(APP_PREFIX, 1),
            generation = 1L,
            libraryScope = "/test/library-a",
        )
        fun uiString(id: Int, vararg args: Any) =
            InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)
    }
}
