/* Licensed under the Apache License, Version 2.0. */
package io.github.h3nb.jlmodplus.memory

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/** Deliver system Back after composition is idle, including when a dialog owns focus. */
internal fun ComposeTestRule.pressMemoryEditorBack() {
    waitForIdle()
    InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    waitForIdle()
}
