/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

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
    // A settled tab return reveals navigation without changing the content or header position.
    LaunchedEffect(viewport.chromeVisible) {
        if (viewport.chromeVisible) hysteresis.reset()
    }
    LaunchedEffect(viewport, layout) {
        snapshotFlow {
            if (layout == LibraryLayout.List) {
                viewport.listState.firstVisibleItemIndex == 0 &&
                    viewport.listState.firstVisibleItemScrollOffset == 0
            } else {
                viewport.gridState.firstVisibleItemIndex == 0 &&
                    viewport.gridState.firstVisibleItemScrollOffset == 0
            }
        }.collectLatest { atTop ->
            if (atTop) {
                viewport.headerOffsetPx.floatValue = 0f
                hysteresis.reset()
                currentVisibilityChanged(true)
            }
        }
    }
    return remember(viewport, layout, hysteresis, minimumRoom) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (!currentEnabled) return Offset.Zero
                val height = headerHeightPx.intValue.toFloat()
                if (height <= 0f) return Offset.Zero
                // Ignore unconsumed forward fling distance: short content must stay expanded.
                val delta = if (consumed.y != 0f) consumed.y else available.y.coerceAtLeast(0f)
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

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!currentEnabled) return Velocity.Zero
                val height = headerHeightPx.intValue.toFloat()
                val offset = viewport.headerOffsetPx.floatValue
                // A partial header is never a resting state. Expand it rather than hiding more
                // than the content actually scrolled, which would leave an empty header spacer.
                if (offset < -0.5f && offset > -height + 0.5f) {
                    currentVisibilityChanged(true)
                    hysteresis.reset()
                    animate(offset, 0f, animationSpec = tween(180)) { value, _ ->
                        viewport.headerOffsetPx.floatValue = value
                    }
                }
                return Velocity.Zero
            }
        }
    }
}
