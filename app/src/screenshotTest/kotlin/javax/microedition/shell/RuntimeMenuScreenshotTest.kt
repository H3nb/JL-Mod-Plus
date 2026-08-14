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

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private object NoOpRuntimeMenuActions : RuntimeMenuActions {
    override fun onExit() = Unit
    override fun onSaveLog() = Unit
    override fun onToggleOrientationLock() = Unit
    override fun onOpenImeKeyboard() = Unit
    override fun onTakeScreenshot() = Unit
    override fun onLimitFps() = Unit
    override fun onSetFpsLimit(value: Int) = Unit
    override fun onResetFpsLimit() = Unit
    override fun onEditVirtualKeyboardLayout() = Unit
    override fun onResizeVirtualKeyboardLayout() = Unit
    override fun onFinishVirtualKeyboardLayout() = Unit
    override fun onSwitchVirtualKeyboardLayout() = Unit
    override fun onHideVirtualKeyboardButtons() = Unit
}

private val CanvasMenuState = RuntimeMenuUiState(
    title = "Demo MIDlet",
    isCanvas = true,
    toolbarVisible = true,
    imeAvailable = true,
    virtualKeyboardAvailable = true,
    virtualKeyboardEditing = true,
    orientationLocked = true,
)

@PreviewTest
@Preview(name = "Runtime toolbar", widthDp = 360, heightDp = 56, showBackground = true)
@Composable
fun RuntimeToolbarScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        RuntimeMenuPreview(state = CanvasMenuState)
    }
}

@PreviewTest
@Preview(name = "Runtime overflow menu", widthDp = 360, heightDp = 480, showBackground = true)
@Composable
fun RuntimeOverflowMenuScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        RuntimeMenuPreview(state = CanvasMenuState, menuVisible = true)
    }
}

@PreviewTest
@Preview(name = "Runtime compact toolbar", widthDp = 360, heightDp = 38, showBackground = true)
@Composable
fun RuntimeCompactToolbarScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        RuntimeMenuPreview(state = CanvasMenuState)
    }
}

@PreviewTest
@Preview(
    name = "Runtime fullscreen menu dark landscape",
    widthDp = 640,
    heightDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun RuntimeFullscreenMenuDarkLandscapeScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        RuntimeMenuPreview(
            state = CanvasMenuState.copy(toolbarVisible = false),
            menuVisible = true,
        )
    }
}

@PreviewTest
@Preview(
    name = "Runtime FPS dialog landscape",
    widthDp = 640,
    heightDp = 360,
    showBackground = true,
)
@Composable
fun RuntimeFpsDialogLandscapeScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        RuntimeLimitFpsDialog(
            onDismiss = {},
            onConfirm = {},
            onReset = {},
        )
    }
}

@Composable
private fun RuntimeMenuPreview(
    state: RuntimeMenuUiState,
    menuVisible: Boolean = false,
    modifier: Modifier = Modifier,
) {
    RuntimeMenuHost(
        state = state,
        menuVisible = menuVisible,
        actions = NoOpRuntimeMenuActions,
        onOpenMenu = {},
        onDismissMenu = {},
        modifier = modifier,
    )
}
