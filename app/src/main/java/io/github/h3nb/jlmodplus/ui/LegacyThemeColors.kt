/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.preference.PreferenceManager

/** Bridges the app's Material color scheme to native host UI, not guest pixels. */
object LegacyThemeColors {
    @JvmStatic
    fun accent(context: Context): Int = scheme(context).primary.toArgb()

    @JvmStatic
    fun snapshot(context: Context): LegacyUiColors = LegacyUiColors(scheme(context))

    private fun scheme(context: Context): ColorScheme {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        return AccentPalette.readPreference(preferences).colorScheme(isDark(context), context)
    }

    private fun isDark(context: Context): Boolean {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
}

/** Immutable ARGB roles for View and Canvas chrome; resolve outside paint loops. */
class LegacyUiColors internal constructor(scheme: ColorScheme) {
    val primary = scheme.primary.toArgb()
    val onPrimary = scheme.onPrimary.toArgb()
    val primaryContainer = scheme.primaryContainer.toArgb()
    val onPrimaryContainer = scheme.onPrimaryContainer.toArgb()
    val surfaceContainer = scheme.surfaceContainer.toArgb()
    val surfaceContainerLow = scheme.surfaceContainerLow.toArgb()
    val onSurface = scheme.onSurface.toArgb()
    val onSurfaceVariant = scheme.onSurfaceVariant.toArgb()
    val outline = scheme.outline.toArgb()
    val outlineVariant = scheme.outlineVariant.toArgb()
}
