/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import java.util.Locale

/** Compact, locale-stable duration text for Library play-stat summaries. */
object LibraryPlayStatsFormatter {
    fun duration(totalPlayTimeMs: Long): String {
        val totalSeconds = (totalPlayTimeMs.coerceAtLeast(0L) / 1000L)
        val seconds = totalSeconds % 60L
        val totalMinutes = totalSeconds / 60L
        if (totalMinutes < 60L) {
            return String.format(Locale.ROOT, "%d:%02d", totalMinutes, seconds)
        }
        val minutes = totalMinutes % 60L
        val hours = totalMinutes / 60L
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    }
}
