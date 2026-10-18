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
        val narrow = canvas.copy(toolbarInsetLeftPx = 120)
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
        val largeText = canvas.copy(toolbarFontScale = 2f)
        assertEquals(listOf(RuntimeToolbarShortcut.Speed, RuntimeToolbarShortcut.Ime), runtimeToolbarShortcuts(largeText, 360f))
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
