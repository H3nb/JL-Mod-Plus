/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.ui

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.config.ConfigActivity
import io.github.h3nb.jlmodplus.config.ControlSkinManagerActivity
import io.github.h3nb.jlmodplus.config.ProfilesActivity
import io.github.h3nb.jlmodplus.crashes.CrashReportDetailsActivity
import io.github.h3nb.jlmodplus.crashes.CrashReportsActivity
import io.github.h3nb.jlmodplus.filepicker.FilteredFilePickerActivity
import io.github.h3nb.jlmodplus.memory.MemoryEditorActivity
import io.github.h3nb.jlmodplus.settings.KeyMapperActivity
import io.github.h3nb.jlmodplus.settings.SettingsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPageMotionContractTest {
    @Test fun internalFullScreenPagesShareWindowMotionWithoutChangingRuntimePresentation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = listOf(
            SettingsActivity::class.java, ProfilesActivity::class.java,
            ConfigActivity::class.java, ControlSkinManagerActivity::class.java,
            KeyMapperActivity::class.java, CrashReportsActivity::class.java,
            CrashReportDetailsActivity::class.java, FilteredFilePickerActivity::class.java,
        )
        val attrs = intArrayOf(android.R.attr.windowAnimationStyle)
        fun styleOf(clazz: Class<*>): Int {
            val info = context.packageManager.getActivityInfo(
                ComponentName(context, clazz), PackageManager.GET_META_DATA,
            )
            val theme = context.resources.newTheme().apply {
                applyStyle(info.themeResource, true)
            }
            val attributes = theme.obtainStyledAttributes(attrs)
            return try {
                attributes.getResourceId(0, 0)
            } finally {
                attributes.recycle()
            }
        }
        val expected = styleOf(pages.first())
        assertEquals(R.style.AppPageWindowAnimations, expected)
        pages.drop(1).forEach { assertEquals(it.simpleName, expected, styleOf(it)) }
        assertNotEquals(expected, styleOf(MemoryEditorActivity::class.java))
    }
}
