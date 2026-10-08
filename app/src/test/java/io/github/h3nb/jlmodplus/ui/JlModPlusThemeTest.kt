/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JlModPlusThemeTest {
    @Test
    fun transparentSystemBarUsesTheComposeSurfaceContrast() {
        assertTrue(shouldUseDarkSystemBarIcons(Color.Transparent, Color.White))
        assertFalse(shouldUseDarkSystemBarIcons(Color.Transparent, Color(0xFF101418)))
    }

    @Test
    fun opaqueLegacySystemBarKeepsReadableIcons() {
        assertFalse(shouldUseDarkSystemBarIcons(Color(0xFF212121), Color.White))
        assertTrue(shouldUseDarkSystemBarIcons(Color(0xFFF5F5F5), Color.Black))
    }

    @Test
    fun midToneChoosesTheHigherContrastIconColor() {
        assertTrue(shouldUseDarkSystemBarIcons(Color(0xFF808080), Color.Black))
        assertFalse(shouldUseDarkSystemBarIcons(Color(0xFF606060), Color.White))
    }
    @Test
    fun unsupportedAccentPreferencesResolveToTheNewDefault() {
        assertEquals(8, AccentPalette.entries.size)
        for (removed in listOf(null, "indigo", "cyan", "orange", "pink", "invalid")) {
            assertEquals(AccentPalette.Sapphire, AccentPalette.fromKey(removed))
        }
        assertEquals(AccentPalette.DefaultBlue, AccentPalette.fromKey("blue"))
        assertEquals(AccentPalette.Teal, AccentPalette.fromKey("teal"))
        assertEquals(AccentPalette.Violet, AccentPalette.fromKey("violet"))
        assertEquals(AccentPalette.Rose, AccentPalette.fromKey("rose"))
        assertEquals(AccentPalette.Green, AccentPalette.fromKey("green"))
        for (palette in AccentPalette.entries) {
            assertEquals(palette, AccentPalette.fromKey(palette.key))
        }
    }

    @Test
    fun accentAndNeutralRolesHaveReadablePairsInBothThemes() {
        for (palette in AccentPalette.entries) {
            val light = palette.colorScheme(false)
            val dark = palette.colorScheme(true)
            assertEquals(Color(0xFFF7F9FA), light.background)
            assertEquals(Color(0xFF111518), dark.background)
            assertEquals(palette.previewColor(false), light.primary)
            assertEquals(palette.previewColor(true), dark.primary)
            assertEquals(light.primaryFixed, dark.primaryFixed)
            assertEquals(light.primaryFixedDim, dark.primaryFixedDim)
            assertEquals(light.secondaryFixed, dark.secondaryFixed)
            assertEquals(light.secondaryFixedDim, dark.secondaryFixedDim)
            assertEquals(light.tertiaryFixed, dark.tertiaryFixed)
            assertEquals(light.tertiaryFixedDim, dark.tertiaryFixedDim)
            assertEquals(light.onPrimaryFixedVariant, dark.onPrimaryFixedVariant)
            assertEquals(light.onSecondaryFixedVariant, dark.onSecondaryFixedVariant)
            assertEquals(light.onTertiaryFixedVariant, dark.onTertiaryFixedVariant)
            for (scheme in listOf(light, dark)) {
                for ((foreground, background) in listOf(
                    scheme.onBackground to scheme.background,
                    scheme.onSurface to scheme.surface,
                    scheme.onSurfaceVariant to scheme.surface,
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSecondary to scheme.secondary,
                    scheme.onSecondaryContainer to scheme.secondaryContainer,
                    scheme.onTertiary to scheme.tertiary,
                    scheme.onTertiaryContainer to scheme.tertiaryContainer,
                    scheme.onPrimaryFixed to scheme.primaryFixed,
                    scheme.onPrimaryFixed to scheme.primaryFixedDim,
                    scheme.onPrimaryFixedVariant to scheme.primaryFixed,
                    scheme.onPrimaryFixedVariant to scheme.primaryFixedDim,
                    scheme.onSecondaryFixed to scheme.secondaryFixed,
                    scheme.onSecondaryFixed to scheme.secondaryFixedDim,
                    scheme.onSecondaryFixedVariant to scheme.secondaryFixed,
                    scheme.onSecondaryFixedVariant to scheme.secondaryFixedDim,
                    scheme.onTertiaryFixed to scheme.tertiaryFixed,
                    scheme.onTertiaryFixed to scheme.tertiaryFixedDim,
                    scheme.onTertiaryFixedVariant to scheme.tertiaryFixed,
                    scheme.onTertiaryFixedVariant to scheme.tertiaryFixedDim,
                    scheme.onError to scheme.error,
                    scheme.onErrorContainer to scheme.errorContainer,
                )) {
                    assertTrue(
                        "Insufficient contrast for ${palette.key}: ${foreground} on ${background}",
                        contrastRatio(foreground, background) >= 4.5f,
                    )
                }
                assertTrue(contrastRatio(scheme.outline, scheme.surfaceContainerHigh) >= 3f)
            }
        }
    }


    @Test
    fun neutralSurfaceLevelsStayOrderedAndReadableForEveryAccent() {
        for (palette in AccentPalette.entries) {
            for (dark in listOf(false, true)) {
                val scheme = palette.colorScheme(dark)
                val surfaces = listOf(
                    scheme.surfaceContainerLowest,
                    scheme.background,
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                    scheme.surfaceVariant,
                )
                for (surface in surfaces) {
                    assertTrue(
                        "Unreadable main text on ${palette.key} ${if (dark) "dark" else "light"}",
                        contrastRatio(scheme.onSurface, surface) >= 4.5f,
                    )
                    assertTrue(
                        "Unreadable supporting text on ${palette.key} ${if (dark) "dark" else "light"}",
                        contrastRatio(scheme.onSurfaceVariant, surface) >= 4.5f,
                    )
                }
                val containers = listOf(
                    scheme.surfaceContainerLow,
                    scheme.surfaceContainer,
                    scheme.surfaceContainerHigh,
                    scheme.surfaceContainerHighest,
                )
                for ((previous, next) in containers.zipWithNext()) {
                    assertTrue(
                        "Surface hierarchy reversed for ${palette.key}",
                        if (dark) next.luminance() > previous.luminance()
                        else next.luminance() < previous.luminance(),
                    )
                }
                assertTrue(contrastRatio(scheme.outline, scheme.surfaceContainerHighest) >= 3f)
                assertTrue(contrastRatio(scheme.inverseOnSurface, scheme.inverseSurface) >= 4.5f)
            }
        }
    }

    private fun contrastRatio(a: Color, b: Color): Float {
        val lighter = maxOf(a.luminance(), b.luminance())
        val darker = minOf(a.luminance(), b.luminance())
        return (lighter + 0.05f) / (darker + 0.05f)
    }

}
