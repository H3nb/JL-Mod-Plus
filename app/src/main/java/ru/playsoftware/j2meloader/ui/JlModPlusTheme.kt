/*
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

package ru.playsoftware.j2meloader.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF34536B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E7F5),
    onPrimaryContainer = Color(0xFF0E2738),
    inversePrimary = Color(0xFFAEC9DE),
    secondary = Color(0xFF4F616E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E5EF),
    onSecondaryContainer = Color(0xFF0C202B),
    tertiary = Color(0xFF64597A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE9DDF5),
    onTertiaryContainer = Color(0xFF211733),
    background = Color(0xFFFAFBFC),
    onBackground = Color(0xFF151A1D),
    surface = Color(0xFFFAFBFC),
    onSurface = Color(0xFF151A1D),
    surfaceVariant = Color(0xFFE0E6EA),
    onSurfaceVariant = Color(0xFF414D55),
    surfaceTint = Color(0xFF34536B),
    inverseSurface = Color(0xFF293136),
    inverseOnSurface = Color(0xFFEEF2F4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF707C84),
    outlineVariant = Color(0xFFC1CBD1),
    scrim = Color.Black,
    surfaceDim = Color(0xFFD9DEE1),
    surfaceBright = Color(0xFFFAFBFC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF4F7F8),
    surfaceContainer = Color(0xFFEEF2F4),
    surfaceContainerHigh = Color(0xFFE6EBEE),
    surfaceContainerHighest = Color(0xFFDDE4E8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C8E5),
    onPrimary = Color(0xFF103246),
    primaryContainer = Color(0xFF234B63),
    onPrimaryContainer = Color(0xFFD5EBFA),
    inversePrimary = Color(0xFF3C6079),
    secondary = Color(0xFFB7CCD8),
    onSecondary = Color(0xFF1A303B),
    secondaryContainer = Color(0xFF334B58),
    onSecondaryContainer = Color(0xFFD3EAF5),
    tertiary = Color(0xFFD2BDE7),
    onTertiary = Color(0xFF382747),
    tertiaryContainer = Color(0xFF4F3E5E),
    onTertiaryContainer = Color(0xFFEEDFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE7EDF0),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE7EDF0),
    surfaceVariant = Color(0xFF2D363D),
    onSurfaceVariant = Color(0xFFBBC6CD),
    surfaceTint = Color(0xFFA9C8E5),
    inverseSurface = Color(0xFFE7EDF0),
    inverseOnSurface = Color(0xFF22292D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF89959D),
    outlineVariant = Color(0xFF3E484F),
    scrim = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF252B2F),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080B0D),
    surfaceContainer = Color(0xFF0F1418),
    surfaceContainerHigh = Color(0xFF171D21),
    surfaceContainerHighest = Color(0xFF20272C),
)

/** Shared shape scale keeps fields, cards, menus, and action controls visually related. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Material 3 theme for app-owned Compose surfaces; dynamic color stays off for parity. */
@Composable
fun JLModPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val colorScheme = if (darkTheme) DarkColors else LightColors
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
            // Edge-to-edge keeps system bars transparent. The host content is intentionally
            // inset below them, so the window background must follow the Compose surface.
            window.decorView.setBackgroundColor(colorScheme.background.toArgb())
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content,
    )
}
