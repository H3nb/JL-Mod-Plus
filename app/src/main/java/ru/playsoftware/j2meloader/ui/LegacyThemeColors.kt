/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import androidx.preference.PreferenceManager
import ru.playsoftware.j2meloader.util.Constants

/** Resolves app-owned colors for the legacy MIDlet canvas overlays. */
object LegacyThemeColors {
    @JvmStatic
    fun accent(context: Context): Int {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val key = preferences.getString(Constants.PREF_ACCENT, AccentPalette.DefaultBlue.key)
        return AccentPalette.fromKey(key).previewColor(isDark(context)).toArgb()
    }

    private fun isDark(context: Context): Boolean {
        return context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }
}
