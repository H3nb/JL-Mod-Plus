/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package javax.microedition.shell

import android.graphics.drawable.GradientDrawable
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.AppCompatImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AccentPalette
import io.github.h3nb.jlmodplus.ui.LegacyUiColors
import io.github.h3nb.jlmodplus.ui.colorScheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeHostViewThemeTest {
    @Test
    fun refreshingThemeRecolorsNativeChromeAndRetainsToolState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val host = RuntimeHostView(
                ContextThemeWrapper(instrumentation.targetContext, R.style.AppTheme_NoActionBar),
            )
            for ((accent, dark) in listOf(AccentPalette.Sapphire to false, AccentPalette.Rose to true)) {
                val colors = LegacyUiColors(accent.colorScheme(dark))
                host.refreshTheme(colors, false, true)
                assertEquals(colors.surfaceContainer, (host.layoutEditPanel.background as GradientDrawable).color!!.defaultColor)
                assertEquals(colors.primary, (host.memoryEditorBubble.background as GradientDrawable).color!!.defaultColor)
                assertEquals(colors.onPrimary, host.memoryEditorBubbleProgress.currentTextColor)
                assertEquals(colors.onPrimary, (host.memoryEditorBubbleIcon as AppCompatImageView).imageTintList!!.defaultColor)
                assertEquals(colors.primary, host.layoutEditDone.currentTextColor)
                assertEquals(colors.onSurfaceVariant, host.layoutEditSnap.imageTintList!!.defaultColor)
                assertEquals(colors.primary, host.layoutEditGroupResize.imageTintList!!.defaultColor)
                assertEquals(host.root.context.getString(R.string.layout_editor_snap_off), host.layoutEditSnap.contentDescription.toString())
                assertEquals(host.root.context.getString(R.string.layout_editor_group_on), host.layoutEditGroupResize.contentDescription.toString())
            }
        }
    }
}
