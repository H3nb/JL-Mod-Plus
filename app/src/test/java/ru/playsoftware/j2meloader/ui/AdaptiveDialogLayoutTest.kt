/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveDialogLayoutTest {
    @Test
    fun compactPhoneKeepsSafeMargins() {
        val layout = adaptiveDialogLayout(availableWidth = 320.dp, availableHeight = 600.dp)

        assertEquals(288.dp, layout.width)
        assertEquals(568.dp, layout.maxHeight)
    }

    @Test
    fun shortLandscapeUsesMoreOfTheViewport() {
        val layout = adaptiveDialogLayout(availableWidth = 640.dp, availableHeight = 360.dp)

        assertEquals(608.dp, layout.width)
        assertEquals(328.dp, layout.maxHeight)
    }

    @Test
    fun expandedWindowCapsReadableModalWidth() {
        val layout = adaptiveDialogLayout(availableWidth = 1200.dp, availableHeight = 900.dp)

        assertEquals(720.dp, layout.width)
        assertEquals(852.dp, layout.maxHeight)
    }

    @Test
    fun tinyWindowBoundsNeverBecomeNegative() {
        val layout = adaptiveDialogLayout(availableWidth = 20.dp, availableHeight = 20.dp)

        assertEquals(0.dp, layout.width)
        assertEquals(0.dp, layout.maxHeight)
    }
}
