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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.zIndex

private const val SYSTEM_BAR_SCRIM_ALPHA = 0.30f

/**
 * Theme-aware translucent system-bar protection while a scrolling header is off-screen.
 *
 * Keep enough of the surface tint to preserve status-bar legibility, but leave the content
 * underneath perceptible while the header is being scrolled away. The flat translucent layer
 * avoids the hard edge produced by an opaque replacement bar and adapts to light/dark surfaces.
 */
@Composable
internal fun GlassSystemBarScrim(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    // Read the effective Compose surface rather than the platform setting so an explicitly
    // selected in-app dark/light theme receives the matching scrim as well.
    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scrimBase = if (darkTheme) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            // Keep the app content perceptible while providing a predictable 30% protection
            // layer for status-bar icons in both themes. A flat scrim also avoids a visible
            // gradient edge when the header is revealed again.
            .background(scrimBase.copy(alpha = SYSTEM_BAR_SCRIM_ALPHA))
            .zIndex(2f),
    )
}
