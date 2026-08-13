/*
 * Modified for JL-Mod Plus.
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF212121),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E1E1),
    onPrimaryContainer = Color(0xFF1B1B1B),
    secondary = Color(0xFF5F5E62),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE5E2E2),
    onSurfaceVariant = Color(0xFF353535),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBFC7D2),
    onPrimary = Color(0xFF262E37),
    primaryContainer = Color(0xFF3C4652),
    onPrimaryContainer = Color(0xFFE0E8F2),
    secondary = Color(0xFFC4C6CA),
    onSecondary = Color(0xFF292A2E),
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF000000),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF45474B),
    onSurfaceVariant = Color(0xFFC7C6CA),
    error = Color(0xFFFFB4AB),
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
        content = content,
    )
}
