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
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import ru.playsoftware.j2meloader.util.Constants

internal fun shouldUseDarkSystemBarIcons(barColor: Color, backgroundColor: Color): Boolean =
    // 0.179 is the luminance where black and white have equal WCAG contrast.
    barColor.compositeOver(backgroundColor).luminance() > 0.179f

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

enum class AccentPalette(val key: String) {
    DefaultBlue("blue"),
    Teal("teal"),
    Green("green"),
    Amber("amber"),
    Rose("rose"),
    Violet("violet"),
    Indigo("indigo"),
    Cyan("cyan"),
    Orange("orange"),
    Pink("pink");

    companion object {
        fun fromKey(key: String?): AccentPalette = entries.firstOrNull { it.key == key } ?: DefaultBlue
    }

    fun previewColor(dark: Boolean): Color = colors(dark).primary
}

private data class AccentColors(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val inversePrimary: Color,
)

private fun AccentPalette.colors(dark: Boolean): AccentColors = when (this) {
    AccentPalette.DefaultBlue -> if (dark) {
        AccentColors(
            Color(0xFFA9C8E5), Color(0xFF103246), Color(0xFF234B63),
            Color(0xFFD5EBFA), Color(0xFF3C6079),
        )
    } else {
        AccentColors(
            Color(0xFF34536B), Color.White, Color(0xFFD5E7F5),
            Color(0xFF0E2738), Color(0xFFAEC9DE),
        )
    }
    AccentPalette.Teal -> if (dark) {
        AccentColors(
            Color(0xFF70D5CB), Color(0xFF003735), Color(0xFF00504C),
            Color(0xFF8CF2E8), Color(0xFF1E8F88),
        )
    } else {
        AccentColors(
            Color(0xFF006A64), Color.White, Color(0xFF9CF2E8),
            Color(0xFF00201E), Color(0xFF006A64),
        )
    }
    AccentPalette.Green -> if (dark) {
        AccentColors(
            Color(0xFF9CD67A), Color(0xFF17370F), Color(0xFF2A511F),
            Color(0xFFB8F397), Color(0xFF4D8A39),
        )
    } else {
        AccentColors(
            Color(0xFF376A26), Color.White, Color(0xFFB8F397),
            Color(0xFF0C2006), Color(0xFF376A26),
        )
    }
    AccentPalette.Amber -> if (dark) {
        AccentColors(
            Color(0xFFFFC55E), Color(0xFF432B00), Color(0xFF604000),
            Color(0xFFFFDFA6), Color(0xFF9A6800),
        )
    } else {
        AccentColors(
            Color(0xFF7A5100), Color.White, Color(0xFFFFDFA6),
            Color(0xFF271900), Color(0xFF7A5100),
        )
    }
    AccentPalette.Rose -> if (dark) {
        AccentColors(
            Color(0xFFFFB0C4), Color(0xFF570022), Color(0xFF780033),
            Color(0xFFFFD9E1), Color(0xFFAA2D57),
        )
    } else {
        AccentColors(
            Color(0xFF9B234D), Color.White, Color(0xFFFFD9E1),
            Color(0xFF3E0018), Color(0xFF9B234D),
        )
    }
    AccentPalette.Violet -> if (dark) {
        AccentColors(
            Color(0xFFD0BCFF), Color(0xFF381E72), Color(0xFF4F378B),
            Color(0xFFEADDFF), Color(0xFF6750A4),
        )
    } else {
        AccentColors(
            Color(0xFF6750A4), Color.White, Color(0xFFEADDFF),
            Color(0xFF21005D), Color(0xFF6750A4),
        )
    }
    AccentPalette.Indigo -> if (dark) {
        AccentColors(
            Color(0xFFBAC3FF), Color(0xFF1E2A67), Color(0xFF283777),
            Color(0xFFDEE2FF), Color(0xFF5368C4),
        )
    } else {
        AccentColors(
            Color(0xFF3F51B5), Color.White, Color(0xFFDDE2FF),
            Color(0xFF111A4B), Color(0xFF3F51B5),
        )
    }
    AccentPalette.Cyan -> if (dark) {
        AccentColors(
            Color(0xFF4FD8E8), Color(0xFF00363D), Color(0xFF004F58),
            Color(0xFF97F0FF), Color(0xFF006874),
        )
    } else {
        AccentColors(
            Color(0xFF006874), Color.White, Color(0xFF97F0FF),
            Color(0xFF001F24), Color(0xFF006874),
        )
    }
    AccentPalette.Orange -> if (dark) {
        AccentColors(
            Color(0xFFFFB599), Color(0xFF5B1A00), Color(0xFF7D2900),
            Color(0xFFFFDCC8), Color(0xFFA13B00),
        )
    } else {
        AccentColors(
            Color(0xFFA13B00), Color.White, Color(0xFFFFDCC8),
            Color(0xFF3A0B00), Color(0xFFA13B00),
        )
    }
    AccentPalette.Pink -> if (dark) {
        AccentColors(
            Color(0xFFFFB0CB), Color(0xFF680035), Color(0xFF870047),
            Color(0xFFFFD9E5), Color(0xFFD64E7D),
        )
    } else {
        AccentColors(
            Color(0xFFA9005A), Color.White, Color(0xFFFFD9E5),
            Color(0xFF3F0020), Color(0xFFA9005A),
        )
    }
}

private fun AccentPalette.colorScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    val base = if (dark) DarkColors else LightColors
    val colors = colors(dark)
    return base.copy(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        primaryContainer = colors.primaryContainer,
        onPrimaryContainer = colors.onPrimaryContainer,
        inversePrimary = colors.inversePrimary,
        surfaceTint = colors.primary,
    )
}

/**
 * Accent-aware colors for navigation destinations.
 *
 * Material 3 uses a secondary container for the selected indicator by default. That
 * container is intentionally neutral in our base scheme, so expose one shared mapping for
 * every app-owned navigation surface instead of repeating ad-hoc colors at each call site.
 */
@Composable
internal fun jlModPlusNavigationBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
internal fun jlModPlusNavigationRailItemColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** Shared selected/unselected treatment for quick filters and library option chips. */
@Composable
internal fun jlModPlusFilterChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
    selectedTrailingIconColor = MaterialTheme.colorScheme.primary,
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
    accent: AccentPalette? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    var preferenceAccentKey by remember(preferences) {
        mutableStateOf(preferences.getString(Constants.PREF_ACCENT, AccentPalette.DefaultBlue.key))
    }
    DisposableEffect(preferences, accent) {
        if (accent == null) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
                if (key == Constants.PREF_ACCENT) {
                    preferenceAccentKey = shared.getString(
                        Constants.PREF_ACCENT,
                        AccentPalette.DefaultBlue.key,
                    )
                }
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        } else {
            onDispose { }
        }
    }
    val view = LocalView.current
    val selectedAccent = accent ?: AccentPalette.fromKey(preferenceAccentKey)
    val colorScheme = selectedAccent.colorScheme(darkTheme)
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            @Suppress("DEPRECATION")
            val statusBarColor = Color(window.statusBarColor)
            @Suppress("DEPRECATION")
            val navigationBarColor = Color(window.navigationBarColor)
            controller.isAppearanceLightStatusBars = shouldUseDarkSystemBarIcons(
                statusBarColor,
                colorScheme.background,
            )
            controller.isAppearanceLightNavigationBars = shouldUseDarkSystemBarIcons(
                navigationBarColor,
                colorScheme.background,
            )
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
