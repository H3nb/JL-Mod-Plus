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

package ru.playsoftware.j2meloader.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R

/**
 * Small, theme-aware affordance shown only when a bounded popup has content below its viewport.
 * A small centered arrow keeps the cue visually attached to the popup without looking like a
 * trailing control for the last field or row.
 */
@Composable
internal fun ScrollableContentHint(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.padding(bottom = 4.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_downward),
                contentDescription = stringResource(R.string.dialog_scroll_hint),
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Bridges LazyListState's layout-backed scrollability to a stable Compose value. Some bounded
 * dialogs are captured before layout-backed state settles. Runtime interaction tests must also
 * verify the hint after layout; an initial preview frame alone cannot establish scrollability.
 */
@Composable
internal fun rememberLazyListCanScrollForward(state: LazyListState): Boolean {
    val canScrollForward by remember(state) {
        derivedStateOf {
            val layoutInfo = state.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            state.canScrollForward ||
                layoutInfo.totalItemsCount > 0 && lastVisibleIndex < layoutInfo.totalItemsCount - 1
        }
    }
    return canScrollForward
}

/** Avoids displaying a scroll cue while [ScrollState] still has its pre-layout maximum value. */
@Composable
internal fun rememberScrollCanScrollForward(
    state: ScrollState,
    minimumOverflowPx: Int = 0,
): Boolean {
    val canScrollForward by remember(state, minimumOverflowPx) {
        derivedStateOf {
            state.maxValue != Int.MAX_VALUE &&
                state.maxValue - state.value > minimumOverflowPx
        }
    }
    return canScrollForward
}
