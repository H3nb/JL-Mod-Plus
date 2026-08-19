/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.playsoftware.j2meloader.librarydb

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPlayStatsFormatterTest {
    @Test fun durationFormatsShortAndLongSessionsWithoutOverflowingMinutes() {
        assertEquals("0:00", LibraryPlayStatsFormatter.duration(0L))
        assertEquals("0:42", LibraryPlayStatsFormatter.duration(42_999L))
        assertEquals("12:05", LibraryPlayStatsFormatter.duration((12 * 60 + 5) * 1000L))
        assertEquals("1:02:03", LibraryPlayStatsFormatter.duration((60 * 60 + 2 * 60 + 3) * 1000L))
        assertEquals("27:05:09", LibraryPlayStatsFormatter.duration((27 * 60 * 60 + 5 * 60 + 9) * 1000L))
    }

    @Test fun durationClampsNegativeValuesToZero() {
        assertEquals("0:00", LibraryPlayStatsFormatter.duration(-1L))
    }
}
