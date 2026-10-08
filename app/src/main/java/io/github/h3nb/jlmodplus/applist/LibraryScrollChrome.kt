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
import androidx.compose.foundation.layout.WindowInsets
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
    // Observe only lifecycle/top transitions; partial headers are legitimate resting positions.
    // Their offset follows consumed content scroll instead of running a competing settle animation.
    LaunchedEffect(viewport, layout, enabled) {
        if (!enabled) intent.userInitiated = false
        snapshotFlow {
            val (atTop, scrolling) = if (layout == LibraryLayout.List) {
                (viewport.listState.firstVisibleItemIndex == 0 &&
                    viewport.listState.firstVisibleItemScrollOffset == 0) to
                    viewport.listState.isScrollInProgress
            } else {
                (viewport.gridState.firstVisibleItemIndex == 0 &&
                    viewport.gridState.firstVisibleItemScrollOffset == 0) to
                    viewport.gridState.isScrollInProgress
            }
            atTop to scrolling
        }.collectLatest { (atTop, scrolling) ->
            if (!scrolling) intent.userInitiated = false
            if (!enabled) return@collectLatest
            if (atTop) {
                viewport.headerOffsetPx.floatValue = 0f
                hysteresis.reset()
                currentVisibilityChanged(true)
            }
        }
    }
    return remember(viewport, layout, hysteresis, minimumRoom, intent) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (currentEnabled && source == NestedScrollSource.UserInput && available.y != 0f) {
                    intent.userInitiated = true
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!currentEnabled) return Offset.Zero
                if (source == NestedScrollSource.UserInput && consumed.y != 0f) {
                    intent.userInitiated = true
                }
                // SideEffect includes both flings and programmatic/semantics scrolling. Only
                // continue user input (including accessibility) and its subsequent fling.
                if (!intent.userInitiated) return Offset.Zero
                val height = headerHeightPx.intValue.toFloat()
                if (height <= 0f) return Offset.Zero
                // Only content motion moves the header: unconsumed overscroll at either edge
                // must not detach it from the spacer or collapse a short list.
                val delta = consumed.y
                if (delta == 0f) return Offset.Zero
                if (delta < 0f && viewport.headerOffsetPx.floatValue == 0f) {
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
                viewport.headerOffsetPx.floatValue =
                    (viewport.headerOffsetPx.floatValue + delta).coerceIn(-height, 0f)
                val visibilityChange = hysteresis.onScrollDelta(delta)
                when {
                    viewport.headerOffsetPx.floatValue <= -height + 0.5f ->
                        currentVisibilityChanged(false)
                    visibilityChange == true || viewport.headerOffsetPx.floatValue >= -0.5f ->
                        currentVisibilityChanged(true)
                }
                return Offset.Zero
            }
        }
    }
}
