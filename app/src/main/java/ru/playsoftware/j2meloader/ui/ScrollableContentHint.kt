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

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import ru.playsoftware.j2meloader.R

/**
 * Small, theme-aware affordance shown only when a bounded popup has content below its viewport.
 * The fade and compact tonal pill keep the affordance visually attached to the popup instead of
 * introducing a hard, opaque label across the content.
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
            .height(60.dp)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f),
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.99f),
                    ),
                ),
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            // Keep the compact affordance opaque enough to avoid ghosting the last body line;
            // the surrounding gradient still makes it read as part of the dialog surface.
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            tonalElevation = 2.dp,
            shadowElevation = 1.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_downward),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.dialog_scroll_hint),
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
