/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.ui

import androidx.compose.ui.graphics.Color
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
}
