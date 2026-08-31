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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import kotlin.math.max

private const val SYSTEM_BAR_SCRIM_ALPHA = 0.94f

/**
 * Theme-aware status-bar protection for custom scrolling headers.
 *
 * The protection is slightly taller than the physical status bar and fades into the content,
 * avoiding the hard seam that a flat, status-bar-only fill creates as the header scrolls.
 */
@Composable
internal fun GlassSystemBarScrim(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha = animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        label = "system bar scrim",
    ).value
    if (alpha <= 0f && !visible) return
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scrimBase = if (darkTheme) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val density = LocalDensity.current
    val statusBarHeight = with(density) {
        max(WindowInsets.statusBars.getTop(this), 1).toDp()
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarHeight * 1.28f)
            .alpha(alpha)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        scrimBase.copy(alpha = SYSTEM_BAR_SCRIM_ALPHA),
                        scrimBase.copy(alpha = SYSTEM_BAR_SCRIM_ALPHA * 0.72f),
                        Color.Transparent,
                    ),
                ),
            )
            .zIndex(2f),
    )
}
