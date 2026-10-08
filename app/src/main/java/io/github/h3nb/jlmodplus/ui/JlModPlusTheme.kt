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

package io.github.h3nb.jlmodplus.ui

import android.app.Activity
import android.content.SharedPreferences
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.util.Constants

internal fun shouldUseDarkSystemBarIcons(barColor: Color, backgroundColor: Color): Boolean =
    // 0.179 is the luminance where black and white have equal WCAG contrast.
    barColor.compositeOver(backgroundColor).luminance() > 0.179f

private val LightColors = lightColorScheme(
    background = Color(AppBackgroundColors.argb(false)),
    onBackground = Color(0xFF1B252B),
    surface = Color(AppBackgroundColors.argb(false)),
    onSurface = Color(0xFF1B252B),
    surfaceVariant = Color(0xFFDCE5EA),
    onSurfaceVariant = Color(0xFF51616B),
    inverseSurface = Color(0xFF29373D),
    inverseOnSurface = Color(0xFFF1F4F6),
    outline = Color(0xFF71818A),
    outlineVariant = Color(0xFFCBD5DB),
    surfaceDim = Color(0xFFD9E2E7),
    surfaceBright = Color(0xFFFCFDFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4F6),
    surfaceContainer = Color(0xFFEBF0F3),
    surfaceContainerHigh = Color(0xFFE5EBEF),
    surfaceContainerHighest = Color(0xFFDCE5EA),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

private val DarkColors = darkColorScheme(
    background = Color(AppBackgroundColors.argb(true)),
    onBackground = Color(0xFFE6EBEF),
    surface = Color(AppBackgroundColors.argb(true)),
    onSurface = Color(0xFFE6EBEF),
    surfaceVariant = Color(0xFF303B42),
    onSurfaceVariant = Color(0xFFB6C2CA),
    inverseSurface = Color(0xFFE6EBEF),
    inverseOnSurface = Color(0xFF293339),
    outline = Color(0xFF80919A),
    outlineVariant = Color(0xFF3B474E),
    surfaceDim = Color(0xFF111518),
    surfaceBright = Color(0xFF344149),
    surfaceContainerLowest = Color(0xFF0D1114),
    surfaceContainerLow = Color(0xFF1A2024),
    surfaceContainer = Color(0xFF20282D),
    surfaceContainerHigh = Color(0xFF263037),
    surfaceContainerHighest = Color(0xFF303B42),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

enum class AccentPalette(val key: String) {
    Sapphire("sapphire"),
    Violet("violet"),
    Teal("teal"),
    Coral("coral"),
    Rose("rose"),
    Green("green"),
    Amber("amber"),
    DefaultBlue("blue");

    companion object {
        val Default: AccentPalette = Sapphire

        fun fromKey(key: String?): AccentPalette = entries.firstOrNull { it.key == key } ?: Default

        /** Persistently reset obsolete/unknown saved options without writing an absent key. */
        @JvmStatic
        fun readPreference(preferences: SharedPreferences): AccentPalette {
            val stored = preferences.getString(Constants.PREF_ACCENT, null)
            val selected = fromKey(stored)
            if (stored != null && stored != selected.key) {
                preferences.edit().putString(Constants.PREF_ACCENT, selected.key).apply()
            }
            return selected
        }
    }

    fun previewColor(dark: Boolean): Color = if (dark) tones().dark.primary else tones().light.primary
}

private data class AccentRoles(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

private data class AccentTones(
    val light: AccentRoles,
    val dark: AccentRoles,
    val onPrimaryFixedVariant: Color,
    val onSecondaryFixedVariant: Color,
    val onTertiaryFixedVariant: Color,
)

/** Static accent families keep surfaces neutral and require no per-frame tonal calculations. */
private val AccentToneSets = mapOf(
    AccentPalette.Sapphire to AccentTones(
        light = AccentRoles(
            primary = Color(0xFF285BC2),
            primaryContainer = Color(0xFFCDDFFE),
            onPrimaryContainer = Color(0xFF111D36),
            secondary = Color(0xFF505867),
            secondaryContainer = Color(0xFFD8E2F3),
            onSecondaryContainer = Color(0xFF171E2B),
            tertiary = Color(0xFF614D68),
            tertiaryContainer = Color(0xFFEBD5F3),
            onTertiaryContainer = Color(0xFF25162B),
        ),
        dark = AccentRoles(
            primary = Color(0xFF9BBEFF),
            primaryContainer = Color(0xFF25375A),
            onPrimaryContainer = Color(0xFFCDDFFE),
            secondary = Color(0xFFAFB8C8),
            secondaryContainer = Color(0xFF313845),
            onSecondaryContainer = Color(0xFFD8E2F3),
            tertiary = Color(0xFFC5AECE),
            tertiaryContainer = Color(0xFF433049),
            onTertiaryContainer = Color(0xFFEDD4F5),
        ),
        onPrimaryFixedVariant = Color(0xFF394D72),
        onSecondaryFixedVariant = Color(0xFF373E4D),
        onTertiaryFixedVariant = Color(0xFF47354D),
    ),
    AccentPalette.Violet to AccentTones(
        light = AccentRoles(
            primary = Color(0xFF7142A9),
            primaryContainer = Color(0xFFE4D6FE),
            onPrimaryContainer = Color(0xFF221832),
            secondary = Color(0xFF5A5564),
            secondaryContainer = Color(0xFFE4DEF0),
            onSecondaryContainer = Color(0xFF201B29),
            tertiary = Color(0xFF6C4A56),
            tertiaryContainer = Color(0xFFFAD1DE),
            onTertiaryContainer = Color(0xFF2D131D),
        ),
        dark = AccentRoles(
            primary = Color(0xFFD0B4F1),
            primaryContainer = Color(0xFF3E2F54),
            onPrimaryContainer = Color(0xFFE4D7FA),
            secondary = Color(0xFFBAB4C5),
            secondaryContainer = Color(0xFF3A3543),
            onSecondaryContainer = Color(0xFFE4DEF0),
            tertiary = Color(0xFFD4AAB8),
            tertiaryContainer = Color(0xFF4D2D38),
            onTertiaryContainer = Color(0xFFFCD0DF),
        ),
        onPrimaryFixedVariant = Color(0xFF54446C),
        onSecondaryFixedVariant = Color(0xFF403B4A),
        onTertiaryFixedVariant = Color(0xFF50323D),
    ),
    AccentPalette.Teal to AccentTones(
        light = AccentRoles(
            primary = Color(0xFF006C70),
            primaryContainer = Color(0xFFA6EDF0),
            onPrimaryContainer = Color(0xFF012425),
            secondary = Color(0xFF475D5E),
            secondaryContainer = Color(0xFFCFE7E8),
            onSecondaryContainer = Color(0xFF0D2223),
            tertiary = Color(0xFF425771),
            tertiaryContainer = Color(0xFFC9E0FE),
            onTertiaryContainer = Color(0xFF0D1E32),
        ),
        dark = AccentRoles(
            primary = Color(0xFF82D9CF),
            primaryContainer = Color(0xFF014143),
            onPrimaryContainer = Color(0xFFB8E9EB),
            secondary = Color(0xFFA6BDBD),
            secondaryContainer = Color(0xFF283C3D),
            onSecondaryContainer = Color(0xFFCEE7E8),
            tertiary = Color(0xFFA1BAD9),
            tertiaryContainer = Color(0xFF253951),
            onTertiaryContainer = Color(0xFFC8E0FF),
        ),
        onPrimaryFixedVariant = Color(0xFF0B585B),
        onSecondaryFixedVariant = Color(0xFF2D4344),
        onTertiaryFixedVariant = Color(0xFF2B3E55),
    ),
    AccentPalette.Coral to AccentTones(
        light = AccentRoles(
            primary = Color(0xFFA04F31),
            primaryContainer = Color(0xFFFED3C4),
            onPrimaryContainer = Color(0xFF31150A),
            secondary = Color(0xFF65534D),
            secondaryContainer = Color(0xFFF1DCD5),
            onSecondaryContainer = Color(0xFF291A14),
            tertiary = Color(0xFF5E5534),
            tertiaryContainer = Color(0xFFE7DEBB),
            onTertiaryContainer = Color(0xFF231C01),
        ),
        dark = AccentRoles(
            primary = Color(0xFFF5B69D),
            primaryContainer = Color(0xFF542B1D),
            onPrimaryContainer = Color(0xFFFCD4C5),
            secondary = Color(0xFFC6B2AB),
            secondaryContainer = Color(0xFF44342E),
            onSecondaryContainer = Color(0xFFF2DCD4),
            tertiary = Color(0xFFC1B892),
            tertiaryContainer = Color(0xFF403817),
            onTertiaryContainer = Color(0xFFE8DEB8),
        ),
        onPrimaryFixedVariant = Color(0xFF6C4030),
        onSecondaryFixedVariant = Color(0xFF4B3A34),
        onTertiaryFixedVariant = Color(0xFF443C1E),
    ),
    AccentPalette.Rose to AccentTones(
        light = AccentRoles(
            primary = Color(0xFFA63265),
            primaryContainer = Color(0xFFFFCFDE),
            onPrimaryContainer = Color(0xFF30131E),
            secondary = Color(0xFF655258),
            secondaryContainer = Color(0xFFF0DBE1),
            onSecondaryContainer = Color(0xFF29191E),
            tertiary = Color(0xFF6C4E3B),
            tertiaryContainer = Color(0xFFF9D6C1),
            onTertiaryContainer = Color(0xFF2D1607),
        ),
        dark = AccentRoles(
            primary = Color(0xFFF2A8C6),
            primaryContainer = Color(0xFF522938),
            onPrimaryContainer = Color(0xFFFBD1DE),
            secondary = Color(0xFFC6B1B7),
            secondaryContainer = Color(0xFF433338),
            onSecondaryContainer = Color(0xFFF1DBE1),
            tertiary = Color(0xFFD3AF99),
            tertiaryContainer = Color(0xFF4C301F),
            onTertiaryContainer = Color(0xFFFBD5BF),
        ),
        onPrimaryFixedVariant = Color(0xFF6B3D4D),
        onSecondaryFixedVariant = Color(0xFF4B393E),
        onTertiaryFixedVariant = Color(0xFF503524),
    ),
    AccentPalette.Green to AccentTones(
        light = AccentRoles(
            primary = Color(0xFF327446),
            primaryContainer = Color(0xFFBDECC7),
            onPrimaryContainer = Color(0xFF082511),
            secondary = Color(0xFF4E5C51),
            secondaryContainer = Color(0xFFD6E6D9),
            onSecondaryContainer = Color(0xFF142218),
            tertiary = Color(0xFF315D63),
            tertiaryContainer = Color(0xFFBAE8EE),
            onTertiaryContainer = Color(0xFF012226),
        ),
        dark = AccentRoles(
            primary = Color(0xFFA4DEA7),
            primaryContainer = Color(0xFF1B4126),
            onPrimaryContainer = Color(0xFFC7E8CE),
            secondary = Color(0xFFACBCAF),
            secondaryContainer = Color(0xFF2F3C31),
            onSecondaryContainer = Color(0xFFD5E7D9),
            tertiary = Color(0xFF91C1C8),
            tertiaryContainer = Color(0xFF103F45),
            onTertiaryContainer = Color(0xFFB7E9EF),
        ),
        onPrimaryFixedVariant = Color(0xFF2F583A),
        onSecondaryFixedVariant = Color(0xFF344238),
        onTertiaryFixedVariant = Color(0xFF1C4348),
    ),
    AccentPalette.Amber to AccentTones(
        light = AccentRoles(
            primary = Color(0xFF86600F),
            primaryContainer = Color(0xFFF7DAAA),
            onPrimaryContainer = Color(0xFF2A1B00),
            secondary = Color(0xFF605748),
            secondaryContainer = Color(0xFFEAE0CF),
            onSecondaryContainer = Color(0xFF251D0F),
            tertiary = Color(0xFF495C3F),
            tertiaryContainer = Color(0xFFD1E6C6),
            onTertiaryContainer = Color(0xFF13210B),
        ),
        dark = AccentRoles(
            primary = Color(0xFFEFCB7E),
            primaryContainer = Color(0xFF493407),
            onPrimaryContainer = Color(0xFFF0DBB9),
            secondary = Color(0xFFC0B6A6),
            secondaryContainer = Color(0xFF3F3729),
            onSecondaryContainer = Color(0xFFEBE0CE),
            tertiary = Color(0xFFA9BF9E),
            tertiaryContainer = Color(0xFF2D3E23),
            onTertiaryContainer = Color(0xFFD0E6C4),
        ),
        onPrimaryFixedVariant = Color(0xFF60491D),
        onSecondaryFixedVariant = Color(0xFF463D2F),
        onTertiaryFixedVariant = Color(0xFF314228),
    ),
    AccentPalette.DefaultBlue to AccentTones(
        light = AccentRoles(
            primary = Color(0xFF34536B),
            primaryContainer = Color(0xFFC1E3FE),
            onPrimaryContainer = Color(0xFF042034),
            secondary = Color(0xFF4C5A66),
            secondaryContainer = Color(0xFFD4E4F1),
            onSecondaryContainer = Color(0xFF13202A),
            tertiary = Color(0xFF584F6E),
            tertiaryContainer = Color(0xFFE1D8FB),
            onTertiaryContainer = Color(0xFF1F182F),
        ),
        dark = AccentRoles(
            primary = Color(0xFFA9C8E5),
            primaryContainer = Color(0xFF143B57),
            onPrimaryContainer = Color(0xFFC2E3FD),
            secondary = Color(0xFFAABAC6),
            secondaryContainer = Color(0xFF2D3A44),
            onSecondaryContainer = Color(0xFFD3E4F2),
            tertiary = Color(0xFFBBB1D5),
            tertiaryContainer = Color(0xFF3B324F),
            onTertiaryContainer = Color(0xFFE2D7FD),
        ),
        onPrimaryFixedVariant = Color(0xFF29516F),
        onSecondaryFixedVariant = Color(0xFF33404C),
        onTertiaryFixedVariant = Color(0xFF3F3752),
    ),
)

private fun AccentPalette.tones(): AccentTones = checkNotNull(AccentToneSets[this])

/** Complete accent-specific Material 3 roles, including invariant fixed colors. */
internal fun AccentPalette.colorScheme(dark: Boolean): androidx.compose.material3.ColorScheme {
    val base = if (dark) DarkColors else LightColors
    val tones = tones()
    val roles = if (dark) tones.dark else tones.light
    return base.copy(
        primary = roles.primary,
        onPrimary = if (dark) tones.light.onPrimaryContainer else Color.White,
        primaryContainer = roles.primaryContainer,
        onPrimaryContainer = roles.onPrimaryContainer,
        inversePrimary = if (dark) tones.light.primary else tones.dark.primary,
        secondary = roles.secondary,
        onSecondary = if (dark) tones.light.onSecondaryContainer else Color.White,
        secondaryContainer = roles.secondaryContainer,
        onSecondaryContainer = roles.onSecondaryContainer,
        tertiary = roles.tertiary,
        onTertiary = if (dark) tones.light.onTertiaryContainer else Color.White,
        tertiaryContainer = roles.tertiaryContainer,
        onTertiaryContainer = roles.onTertiaryContainer,
        surfaceTint = roles.primary,
        primaryFixed = tones.light.primaryContainer,
        primaryFixedDim = tones.dark.primary,
        onPrimaryFixed = tones.light.onPrimaryContainer,
        onPrimaryFixedVariant = tones.onPrimaryFixedVariant,
        secondaryFixed = tones.light.secondaryContainer,
        secondaryFixedDim = tones.dark.secondary,
        onSecondaryFixed = tones.light.onSecondaryContainer,
        onSecondaryFixedVariant = tones.onSecondaryFixedVariant,
        tertiaryFixed = tones.light.tertiaryContainer,
        tertiaryFixedDim = tones.dark.tertiary,
        onTertiaryFixed = tones.light.onTertiaryContainer,
        onTertiaryFixedVariant = tones.onTertiaryFixedVariant,
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
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
internal fun jlModPlusNavigationRailItemColors() = NavigationRailItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
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

private val MaterialTypography = Typography()

/**
 * Keep Material 3's semantic type scale and metrics while asking Android for its default
 * platform typeface. OEM handling of user-selected system fonts remains platform-dependent.
 */
private val AppTypography = Typography(
    displayLarge = MaterialTypography.displayLarge.copy(fontFamily = FontFamily.Default),
    displayMedium = MaterialTypography.displayMedium.copy(fontFamily = FontFamily.Default),
    displaySmall = MaterialTypography.displaySmall.copy(fontFamily = FontFamily.Default),
    headlineLarge = MaterialTypography.headlineLarge.copy(fontFamily = FontFamily.Default),
    headlineMedium = MaterialTypography.headlineMedium.copy(fontFamily = FontFamily.Default),
    headlineSmall = MaterialTypography.headlineSmall.copy(fontFamily = FontFamily.Default),
    titleLarge = MaterialTypography.titleLarge.copy(fontFamily = FontFamily.Default),
    titleMedium = MaterialTypography.titleMedium.copy(fontFamily = FontFamily.Default),
    titleSmall = MaterialTypography.titleSmall.copy(fontFamily = FontFamily.Default),
    bodyLarge = MaterialTypography.bodyLarge.copy(fontFamily = FontFamily.Default),
    bodyMedium = MaterialTypography.bodyMedium.copy(fontFamily = FontFamily.Default),
    bodySmall = MaterialTypography.bodySmall.copy(fontFamily = FontFamily.Default),
    labelLarge = MaterialTypography.labelLarge.copy(fontFamily = FontFamily.Default),
    labelMedium = MaterialTypography.labelMedium.copy(fontFamily = FontFamily.Default),
    labelSmall = MaterialTypography.labelSmall.copy(fontFamily = FontFamily.Default),
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
    paintWindowBackground: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        PreferenceManager.getDefaultSharedPreferences(context)
    }
    var preferenceAccentKey by remember(preferences) {
        mutableStateOf(AccentPalette.readPreference(preferences).key)
    }
    DisposableEffect(preferences, accent) {
        if (accent == null) {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
                if (key == Constants.PREF_ACCENT) {
                    preferenceAccentKey = AccentPalette.readPreference(shared).key
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
    val colorScheme = remember(selectedAccent, darkTheme) { selectedAccent.colorScheme(darkTheme) }
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
            // Most app screens paint the decor behind transparent system bars. Translucent
            // hosts such as Memory Editor deliberately keep the underlying game visible.
            if (paintWindowBackground) {
                window.decorView.setBackgroundColor(colorScheme.background.toArgb())
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
