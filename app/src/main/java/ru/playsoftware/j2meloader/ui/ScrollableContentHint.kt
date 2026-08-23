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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import ru.playsoftware.j2meloader.R

/**
 * Small, theme-aware affordance shown only when a bounded popup has content below its viewport.
 * A small trailing arrow keeps the cue visually attached to the popup without introducing a
 * hard, floating label across the content.
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
        contentAlignment = Alignment.BottomEnd,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_downward),
            contentDescription = stringResource(R.string.dialog_scroll_hint),
            modifier = Modifier
                .padding(end = 12.dp, bottom = 6.dp)
                .size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.68f),
        )
    }
}

/**
 * Bridges LazyListState's layout-backed scrollability to a stable Compose value. Some bounded
 * dialogs are captured immediately after their first layout, so observing the state through a
 * snapshot flow avoids a stale one-frame false result for the scroll affordance.
 */
@Composable
internal fun rememberLazyListCanScrollForward(state: LazyListState): Boolean {
    var canScrollForward by remember(state) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.canScrollForward }.collect { canScrollForward = it }
    }
    return canScrollForward
}
