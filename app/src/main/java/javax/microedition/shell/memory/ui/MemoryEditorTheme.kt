/*
 * Copyright 2026 H3NB
 *
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

package javax.microedition.shell.memory.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.foundation.isSystemInDarkTheme
import io.github.h3nb.jlmodplus.R

@Composable
internal fun MemoryEditorTheme(content: @Composable () -> Unit) {
    val primary = colorResource(R.color.accent)
    val background = colorResource(R.color.background)
    val surface = colorResource(R.color.config_card)
    val onSurface = colorResource(R.color.text_primary)
    val secondaryText = colorResource(R.color.text_secondary)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = primary,
            onPrimary = Color.White,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = colorResource(R.color.primary),
            onSurfaceVariant = secondaryText,
            outline = secondaryText.copy(alpha = 0.7f),
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            background = background,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = colorResource(R.color.primary).copy(alpha = 0.08f),
            onSurfaceVariant = secondaryText,
            outline = secondaryText.copy(alpha = 0.7f),
        )
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
