/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package javax.microedition.shell

import io.github.h3nb.jlmodplus.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeMenuLayoutTest {
    private val canvas = RuntimeMenuUiState(
        isCanvas = true,
        toolbarVisible = true,
        imeAvailable = true,
        emulationSpeedAvailable = true,
        virtualKeyboardAvailable = true,
    )

    @Test
    fun shortcutsMoveBackToMenuWhenHiddenOrConstrained() {
        val fullToolbar = runtimeToolbarShortcuts(canvas, 360f)
        assertEquals(RuntimeToolbarShortcut.entries, fullToolbar)
        val fullMenu = menu(canvas, fullToolbar)
        assertFalse(fullMenu.any { it.label == R.string.action_keyboard_ime })
        assertFalse(fullMenu.any { it.label == R.string.take_screenshot })
        assertFalse(fullMenu.any { it.label == R.string.PREF_EMULATION_SPEED })
        assertEquals(false, fullMenu.single { it.label == R.string.pref_runtime_fullscreen_title }.checked)

        // A cutout or round corner reduces actual usable width without shrinking touch targets.
        val narrow = canvas.copy(toolbarInsetLeftPx = 280)
        val narrowToolbar = runtimeToolbarShortcuts(narrow, 360f)
        assertEquals(listOf(RuntimeToolbarShortcut.Speed), narrowToolbar)
        val narrowMenu = menu(narrow, narrowToolbar)
        assertTrue(narrowMenu.any { it.label == R.string.action_keyboard_ime })
        assertTrue(narrowMenu.any { it.label == R.string.take_screenshot })
        assertFalse(narrowMenu.any { it.label == R.string.PREF_EMULATION_SPEED })

        val fullscreen = canvas.copy(toolbarVisible = false)
        assertTrue(runtimeToolbarShortcuts(fullscreen, 360f).isEmpty())
        val fullscreenMenu = menu(fullscreen)
        assertTrue(fullscreenMenu.any { it.label == R.string.action_keyboard_ime })
        assertTrue(fullscreenMenu.any { it.label == R.string.take_screenshot })
        assertTrue(fullscreenMenu.any { it.label == R.string.PREF_EMULATION_SPEED })
        assertEquals(true, fullscreenMenu.single { it.label == R.string.pref_runtime_fullscreen_title }.checked)
    }

    @Test
    fun orderedEntriesDispatchTheSameActionAfterDismissal() {
        val events = mutableListOf<String>()
        val actions = object : NoOpMenuActions() {
            override fun onToggleFullscreen() { events += "fullscreen" }
            override fun onOpenImeKeyboard() { events += "ime" }
            override fun onEmulationSpeed() { events += "speed" }
        }
        val entries = runtimeMenuEntries(
            canvas.copy(toolbarVisible = false), emptyList(), false, actions,
            { events += "dismiss" }, { events += "virtualControls" },
        )
        assertEquals(
            listOf(
                R.string.exit, R.string.save_log, R.string.action_lock_orientation,
                R.string.memory_editor_bubble, R.string.pref_runtime_fullscreen_title,
                R.string.action_keyboard_ime, R.string.take_screenshot,
                R.string.PREF_LIMIT_FPS, R.string.PREF_EMULATION_SPEED,
                R.string.runtime_virtual_controls_title,
            ),
            entries.map { it.label },
        )
        // Controller activation uses this same ordered list as the rendered rows.
        entries[4].activate()
        entries[5].activate()
        entries[8].activate()
        entries[9].activate()
        assertEquals(listOf("dismiss", "fullscreen", "dismiss", "ime", "dismiss", "speed", "virtualControls"), events)
    }

    @Test
    fun unavailableCanvasActionsStayAbsentAndLargeTextConsumesRealSpace() {
        val form = canvas.copy(isCanvas = false)
        assertTrue(runtimeToolbarShortcuts(form, 640f).isEmpty())
        assertEquals(
            listOf(R.string.exit, R.string.save_log, R.string.action_lock_orientation, R.string.memory_editor_bubble),
            menu(form).map { it.label },
        )
        val incompatible = canvas.copy(emulationSpeedAvailable = false, imeAvailable = false)
        assertEquals(listOf(RuntimeToolbarShortcut.Screenshot), runtimeToolbarShortcuts(incompatible, 360f))
        assertFalse(menu(incompatible).any { it.label == R.string.PREF_EMULATION_SPEED })
        assertFalse(menu(incompatible).any { it.label == R.string.action_keyboard_ime })
        val largeText = canvas.copy(toolbarFontScale = 2f, toolbarSpeedWidthDp = 100f)
        assertEquals(listOf(RuntimeToolbarShortcut.Speed, RuntimeToolbarShortcut.Ime), runtimeToolbarShortcuts(largeText, 196f))
    }

    @Test
    fun cameraSwitchIsContextualForCanvasAndFormAndNamesTheDestination() {
        for (isCanvas in listOf(false, true)) {
            val inactive = canvas.copy(isCanvas = isCanvas)
            assertFalse(menu(inactive).any { it.label == R.string.runtime_camera_use_front })
            assertFalse(menu(inactive).any { it.label == R.string.runtime_camera_use_rear })
            val rear = inactive.copy(cameraSwitchAvailable = true)
            assertTrue(menu(rear).single { it.label == R.string.runtime_camera_use_front }.enabled)
            assertFalse(menu(rear).any { it.label == R.string.runtime_camera_use_rear })
            val front = rear.copy(cameraFrontFacing = true)
            assertTrue(menu(front).single { it.label == R.string.runtime_camera_use_rear }.enabled)
            assertFalse(menu(front).any { it.label == R.string.runtime_camera_use_front })
            assertEquals(runtimeToolbarShortcuts(inactive, 360f), runtimeToolbarShortcuts(front, 360f))
        }
    }

    @Test
    fun busyCameraSwitchCannotDispatchOrDismissMenuFromAnyInputPath() {
        val events = mutableListOf<String>()
        val actions = object : NoOpMenuActions() {
            override fun onSwitchCamera() { events += "switch" }
        }
        fun switchEntry(switching: Boolean) = runtimeMenuEntries(
            canvas.copy(cameraSwitchAvailable = true, cameraSwitching = switching),
            emptyList(), false, actions, { events += "dismiss" }, {},
        ).single { it.label == R.string.runtime_camera_use_front }
        val busy = switchEntry(true)
        assertFalse(busy.enabled)
        busy.activate()
        assertTrue(events.isEmpty())
        switchEntry(false).activate()
        assertEquals(listOf("dismiss", "switch"), events)
    }

    @Test
    fun centralCutoutSplitsTitleAndActionsWithoutReservingABlankTopBand() {
        val notch = canvas.copy(toolbarCutouts = listOf(RuntimeToolbarOcclusion(154, 206)))
        val layout = runtimeToolbarLayout(notch, 360f)
        assertEquals(RuntimeToolbarSpan(8f, 150f), layout.title)
        assertEquals(RuntimeToolbarShortcut.entries, layout.actions.map { it.shortcut })
        assertTrue(layout.actions.all { it.span.left >= 210f && it.span.right <= 356f && it.span.width >= 48f })
        assertFalse(menu(notch, layout.actions.map { it.shortcut }).any { it.label == R.string.take_screenshot })
        assertEquals(0, notch.toolbarInsetTopPx)
    }

    @Test
    fun roundedMarginsAndCutoutPlacementMirrorForRtl() {
        val state = canvas.copy(
            toolbarInsetLeftPx = 32,
            toolbarInsetRightPx = 24,
            toolbarCutouts = listOf(RuntimeToolbarOcclusion(160, 200)),
        )
        val ltr = runtimeToolbarLayout(state, 360f)
        val rtl = runtimeToolbarLayout(state.copy(toolbarRtl = true), 360f)
        assertEquals(RuntimeToolbarSpan(32f, 108f), ltr.title)
        assertEquals(RuntimeToolbarSpan(252f, 336f), rtl.title)
        assertEquals(RuntimeToolbarShortcut.entries, ltr.actions.map { it.shortcut })
        assertTrue(ltr.actions.all { it.span.left >= 32f && it.span.right <= 336f &&
            (it.span.right <= 156f || it.span.left >= 204f) })
        assertEquals(RuntimeToolbarShortcut.entries, rtl.actions.map { it.shortcut })
        assertTrue(rtl.actions.all { it.span.left >= 32f && it.span.right <= 336f &&
            (it.span.right <= 156f || it.span.left >= 204f) })
        val obscured = canvas.copy(toolbarCutouts = listOf(RuntimeToolbarOcclusion(0, 360)))
        assertTrue(runtimeToolbarLayout(obscured, 360f).actions.isEmpty())
        assertEquals(null, runtimeToolbarLayout(obscured, 360f).title)
    }

    @Test
    fun actionsTakePriorityOverTitleAndCanUseBothSidesOfCutout() {
        val narrow = runtimeToolbarLayout(canvas, 152f)
        assertEquals(RuntimeToolbarShortcut.entries, narrow.actions.map { it.shortcut })
        assertEquals(null, narrow.title)
        val fragmented = canvas.copy(
            toolbarSpeedWidthDp = 80f,
            toolbarCutouts = listOf(RuntimeToolbarOcclusion(104, 112)),
        )
        val layout = runtimeToolbarLayout(fragmented, 200f)
        assertEquals(RuntimeToolbarShortcut.entries, layout.actions.map { it.shortcut })
        assertEquals(null, layout.title)
        assertTrue(layout.actions.all { it.span.right <= 100f || it.span.left >= 116f })
    }

    @Test
    fun roundedVisualClearanceUsesInternalPaddingWithoutWeakeningStrictInsets() {
        val rounded = canvas.copy(toolbarRoundedLeftPx = 40, toolbarRoundedRightPx = 40)
        val layout = runtimeToolbarLayout(rounded, 240f)
        assertEquals(RuntimeToolbarShortcut.entries, layout.actions.map { it.shortcut })
        assertEquals(RuntimeToolbarSpan(40f, 64f), layout.title)
        assertTrue(layout.actions.all { it.span.left >= 32f && it.span.right <= 208f })
        // The speed label has the smallest internal padding: 8dp, matching measured text +16dp.
        assertTrue(layout.actions.first().span.left + 8f >= 40f)
        assertTrue(layout.actions.last().span.right - 8f <= 200f)
        val strict = runtimeToolbarLayout(rounded.copy(toolbarInsetRightPx = 48), 240f)
        assertTrue(strict.actions.all { it.span.right <= 192f })
    }

    private fun menu(state: RuntimeMenuUiState, shortcuts: List<RuntimeToolbarShortcut> = emptyList()) =
        runtimeMenuEntries(state, shortcuts, false, NoOpMenuActions(), {}, {})

    private open class NoOpMenuActions : RuntimeMenuActions {
        override fun onExit() = Unit
        override fun onSaveLog() = Unit
        override fun onToggleOrientationLock() = Unit
        override fun onOpenImeKeyboard() = Unit
        override fun onTakeScreenshot() = Unit
        override fun onLimitFps() = Unit
        override fun onSetFpsLimit(value: Int) = Unit
        override fun onResetFpsLimit() = Unit
        override fun onEmulationSpeed() = Unit
        override fun onSetEmulationSpeed(value: Int) = Unit
        override fun onResetEmulationSpeed() = Unit
        override fun onMemoryEditor() = Unit
        override fun onEditVirtualKeyboardLayout() = Unit
        override fun onFinishVirtualKeyboardLayout() = Unit
        override fun onSwitchVirtualKeyboardLayout() = Unit
        override fun onShowControls() = Unit
    }
}
