/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Separately gate touch, keyboard and accessibility for a header action group when its top
 * edge enters the system-bar area. The transform does not change Compose layout coordinates;
 * subtract it from the rendered position so only inset/geometry changes update the baseline.
 * Derived state invalidates the controls only when their eligibility actually changes.
 */
internal class LibraryHeaderActionGate(
    val positionModifier: Modifier,
    val enabled: State<Boolean>,
)

@Composable
internal fun rememberLibraryHeaderActionGate(
    headerOffsetPx: MutableFloatState,
): LibraryHeaderActionGate {
    val safeTopPx = WindowInsets.safeDrawing.getTop(LocalDensity.current).toFloat()
    val topWithoutTranslation = remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
    val enabled = remember(headerOffsetPx, safeTopPx) {
        derivedStateOf {
            topWithoutTranslation.floatValue + headerOffsetPx.floatValue >= safeTopPx - 0.5f
        }
    }
    val positionModifier = remember(headerOffsetPx) {
        Modifier.onGloballyPositioned { coordinates ->
            val top = coordinates.positionInRoot().y - headerOffsetPx.floatValue
            if (abs(top - topWithoutTranslation.floatValue) >= 1f) {
                topWithoutTranslation.floatValue = top
            }
        }
    }
    return remember(positionModifier, enabled) { LibraryHeaderActionGate(positionModifier, enabled) }
}

/**
 * The Lazy header placeholder follows the *visible* chrome height, not its full measured
 * height. Read offset during measurement (not composition) to avoid recomposing thousands
 * of MIDlets each frame. This keeps short filtered lists flush with the still-visible chips.
 *
 * The nested-scroll connection must consume matching header motion in onPreScroll, so that
 * content and placeholder cannot each move independently for the same gesture delta.
 */
@Composable
internal fun LibraryChromeSpacer(
    headerHeightPx: MutableIntState,
    headerOffsetPx: MutableFloatState,
) {
    Layout(modifier = Modifier.fillMaxWidth(), content = {}) { _, constraints ->
        val remaining = (headerHeightPx.intValue + headerOffsetPx.floatValue)
            .roundToInt().coerceAtLeast(0)
        layout(constraints.maxWidth, remaining) {}
    }
}

private class LibraryScrollIntent {
    var userInitiated = false
}

/** One scroll policy for Apps, collection members and the collection overview. */
@Composable
internal fun rememberLibraryScrollChrome(
    viewport: LibraryViewportState,
    layout: LibraryLayout,
    headerHeightPx: MutableIntState,
    enabled: Boolean = true,
    onVisibilityChanged: (Boolean) -> Unit,
): NestedScrollConnection {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentVisibilityChanged by rememberUpdatedState(onVisibilityChanged)
    val density = LocalDensity.current
    val hideDistance = with(density) { LIBRARY_CHROME_HIDE_DISTANCE_DP.dp.toPx() }
    val revealDistance = with(density) { 18.dp.toPx() }
    val minimumRoom = with(density) { LIBRARY_CHROME_MIN_SCROLL_ROOM_DP.dp.toPx() }
    val hysteresis = remember(viewport, hideDistance, revealDistance) {
        LibraryChromeScrollHysteresis(hideDistance, revealDistance, viewport.chromeVisible)
    }
    val intent = remember(viewport, layout) { LibraryScrollIntent() }
    // A settled tab return reveals navigation without changing the content or header position.
    LaunchedEffect(viewport.chromeVisible) {
        if (viewport.chromeVisible) hysteresis.reset()
    }
    // Only observe the end of a scroll gesture. Chrome is moved by consumed user scroll
    // and top-edge pull-down, not by a data projection changing the Lazy viewport's index.
    // Explicit search actions own their own reveal and scroll-to-start behavior.
    LaunchedEffect(viewport, layout, enabled) {
        if (!enabled) intent.userInitiated = false
        snapshotFlow {
            if (layout == LibraryLayout.List) viewport.listState.isScrollInProgress
            else viewport.gridState.isScrollInProgress
        }.collectLatest { scrolling ->
            if (!scrolling) intent.userInitiated = false
        }
    }
    return remember(viewport, layout, hysteresis, minimumRoom, intent) {
        object : NestedScrollConnection {
            private fun moveHeader(delta: Float): Float {
                val height = headerHeightPx.intValue.toFloat()
                if (height <= 0f) return 0f
                val previous = viewport.headerOffsetPx.floatValue
                val next = (previous + delta).coerceIn(-height, 0f)
                val headerDelta = next - previous
                if (headerDelta == 0f) return 0f
                viewport.headerOffsetPx.floatValue = next
                val visibilityChange = hysteresis.onScrollDelta(headerDelta)
                when {
                    next <= -height + 0.5f -> currentVisibilityChanged(false)
                    visibilityChange == true || next >= -0.5f -> currentVisibilityChanged(true)
                }
                return headerDelta
            }

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!currentEnabled) return Offset.Zero
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    intent.userInitiated = true
                }
                // Programmatic Lazy remeasurement and filter changes must never move chrome.
                if (!intent.userInitiated || available.y == 0f) return Offset.Zero
                if (available.y > 0f) {
                    // While a row precedes the viewport, let Lazy scroll it downward first;
                    // onPostScroll mirrors that movement in the header. Consuming it here
                    // moves only the header and leaves the MIDlets stuck in place.
                    //
                    // Once the spacer itself is visible, grow it here instead: its changing
                    // size moves the rows alongside the header without a second Lazy scroll.
                    val spacerVisible = if (layout == LibraryLayout.List) {
                        viewport.listState.firstVisibleItemIndex == 0
                    } else {
                        viewport.gridState.firstVisibleItemIndex == 0
                    }
                    if (!spacerVisible) return Offset.Zero
                }
                val height = headerHeightPx.intValue.toFloat()
                if (height <= 0f) return Offset.Zero
                if (available.y < 0f && viewport.headerOffsetPx.floatValue >= -0.5f) {
                    // Preserve the short-content contract on an untouched expanded header.
                    val room = maxOf(height, minimumRoom)
                    val canCollapse = if (layout == LibraryLayout.List) {
                        viewport.listState.hasLibraryChromeScrollRoom(room) ||
                            viewport.listState.firstVisibleItemIndex > 1
                    } else {
                        viewport.gridState.hasLibraryChromeScrollRoom(room) ||
                            viewport.gridState.firstVisibleItemIndex > 1
                    }
                    if (!canCollapse) return Offset.Zero
                }
                // Collapse at the leading edge; a visible spacer grows at the leading edge
                // on reverse scroll. Both move the MIDlets exactly with the chrome.
                return Offset(0f, moveHeader(available.y))
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!currentEnabled || !intent.userInitiated) return Offset.Zero
                val downward = consumed.y + available.y
                if (downward <= 0f) return Offset.Zero
                // Rows scrolled by Lazy already account for their own consumed distance.
                // Only consume the remainder that actually expands the header.
                val expanded = moveHeader(downward)
                return Offset(
                    0f,
                    (expanded - consumed.y.coerceAtLeast(0f))
                        .coerceIn(0f, available.y.coerceAtLeast(0f)),
                )
            }
        }
    }
}
