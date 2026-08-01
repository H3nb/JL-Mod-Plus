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

package io.github.h3nb.jlmodplus.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.ColorRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.h3nb.jlmodplus.R

private data class AppThemeColors(
    val primary: Color,
    val accent: Color,
    val background: Color,
    val configCard: Color,
    val textPrimary: Color,
    val textSecondary: Color,
)

// The XML styles do not define an outline token. These are the Material 3
// outline values already used by the pre-migration Compose baseline.
private val LightComposeOutline = Color(0xFF79747E)
private val DarkComposeOutline = Color(0xFF938F99)
private val LightComposeSecondaryContainer = Color(0xFFE8DEF8)
private val DarkComposeSecondaryContainer = Color(0xFF4A4458)
private val LightComposePrimaryContainer = Color(0xFFEADDFF)
private val DarkComposePrimaryContainer = Color(0xFF4F378B)
private val LightComposeOnPrimaryContainer = Color(0xFF21005D)
private val DarkComposeOnPrimaryContainer = Color(0xFFEADDFF)
private val LightComposeOutlineVariant = Color(0xFFCAC4D0)
private val DarkComposeOutlineVariant = Color(0xFF49454F)

@Composable
internal fun AppComposeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = remember(context, darkTheme) {
        AppThemeColors(
            primary = context.resolveThemeColor(R.color.primary, darkTheme),
            accent = context.resolveThemeColor(R.color.accent, darkTheme),
            background = context.resolveThemeColor(R.color.background, darkTheme),
            configCard = context.resolveThemeColor(R.color.config_card, darkTheme),
            textPrimary = context.resolveThemeColor(R.color.text_primary, darkTheme),
            textSecondary = context.resolveThemeColor(R.color.text_secondary, darkTheme),
        )
    }
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            primaryContainer = DarkComposePrimaryContainer,
            onPrimaryContainer = DarkComposeOnPrimaryContainer,
            secondary = colors.primary,
            onSecondary = Color.White,
            secondaryContainer = DarkComposeSecondaryContainer,
            onSecondaryContainer = Color(0xFFE8DEF8),
            tertiary = colors.primary,
            onTertiary = Color.White,
            tertiaryContainer = colors.configCard,
            onTertiaryContainer = colors.textPrimary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.configCard,
            onSurfaceVariant = colors.textSecondary,
            outline = DarkComposeOutline,
            outlineVariant = DarkComposeOutlineVariant,
            inverseSurface = colors.textPrimary,
            inverseOnSurface = colors.background,
            inversePrimary = colors.accent,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = Color.White,
            primaryContainer = LightComposePrimaryContainer,
            onPrimaryContainer = LightComposeOnPrimaryContainer,
            secondary = colors.primary,
            onSecondary = Color.White,
            secondaryContainer = LightComposeSecondaryContainer,
            onSecondaryContainer = Color(0xFF1D192B),
            tertiary = colors.primary,
            onTertiary = Color.White,
            tertiaryContainer = colors.configCard,
            onTertiaryContainer = colors.textPrimary,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.background,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.configCard,
            onSurfaceVariant = colors.textSecondary,
            outline = LightComposeOutline,
            outlineVariant = LightComposeOutlineVariant,
            inverseSurface = colors.textPrimary,
            inverseOnSurface = colors.background,
            inversePrimary = colors.accent,
            scrim = Color.Black,
        )
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

private fun Context.resolveThemeColor(@ColorRes colorRes: Int, darkTheme: Boolean): Color {
    val configuration = Configuration(resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (darkTheme) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    val themedContext = createConfigurationContext(configuration)
    return Color(ContextCompat.getColor(themedContext, colorRes))
}
