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

package ru.playsoftware.j2meloader.memory

import android.provider.Settings
import android.view.View

/**
 * MIDlet-process proxy for the Memory Editor overlay.
 *
 * No Memory Editor Compose content, state polling, engine IPC, or custom keypad is hosted here.
 * Those allocations live in [MemoryEditorOverlayService]'s dedicated :memory_editor process so
 * opening/refining the editor cannot itself create ART GC pressure in the MIDlet process.
 *
 * The two legacy host views remain constructor arguments temporarily so MicroActivity does not
 * need compatibility-sensitive structural changes. RuntimeHostView now supplies unattached plain
 * Views rather than ComposeViews for them.
 */
class MemoryEditorComposeController(
    composeView: View,
    @Suppress("UNUSED_PARAMETER") bubbleView: View,
) {
    private val context = composeView.context.applicationContext

    fun toggleBubble(): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            MemoryEditorOverlayPermissionActivity.launch(context)
            return false
        }
        val enable = !MemoryEditorOverlayState.isActive(context)
        MemoryEditorOverlayService.setEnabled(context, enable)
        return enable
    }

    fun open() {
        if (Settings.canDrawOverlays(context)) {
            MemoryEditorOverlayService.open(context)
        } else {
            MemoryEditorOverlayPermissionActivity.launch(context)
        }
    }

    fun close() {
        MemoryEditorOverlayService.hide(context)
    }

    fun isVisible(): Boolean = MemoryEditorOverlayState.isVisible(context)

    fun isBubbleEnabled(): Boolean = MemoryEditorOverlayState.isActive(context)

    fun destroy() = Unit

    internal companion object {
        // Retained for the legacy screenshot/test composables while the production overlay uses
        // MemoryEditorOverlayController.PAGE_SIZE.
        const val PAGE_SIZE = 100
    }
}
