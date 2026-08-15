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

package ru.playsoftware.j2meloader.settings

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val NoOpKeyMapperActions = object : KeyMapperActions {
    override fun onVirtualKey(canvasKey: Int) = Unit
    override fun onDismissMapping() = Unit
}

@PreviewTest
@Preview(name = "Key mapper light phone", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun KeyMapperLightPhoneScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        KeyMapperScreen(KeyMapperUiState(), NoOpKeyMapperActions)
    }
}

@PreviewTest
@Preview(
    name = "Key mapper dark phone",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun KeyMapperDarkPhoneScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        KeyMapperScreen(KeyMapperUiState(), NoOpKeyMapperActions)
    }
}

@PreviewTest
@Preview(
    name = "Key mapper mapping prompt",
    widthDp = 360,
    heightDp = 640,
    showBackground = true,
)
@Composable
fun KeyMapperMappingPromptScreenshot() {
    JLModPlusTheme {
        KeyMapperScreen(
            state = KeyMapperUiState(
                mappingDialog = KeyMapperMappingDialog(
                    canvasKey = 0,
                    currentKeyName = "KEYCODE_BACK",
                ),
            ),
            actions = NoOpKeyMapperActions,
        )
    }
}

@PreviewTest
@Preview(
    name = "Key mapper mapping prompt dark",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun KeyMapperMappingPromptDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        KeyMapperScreen(
            state = KeyMapperUiState(
                mappingDialog = KeyMapperMappingDialog(
                    canvasKey = 0,
                    currentKeyName = "KEYCODE_BACK",
                ),
            ),
            actions = NoOpKeyMapperActions,
        )
    }
}

@PreviewTest
@Preview(
    name = "Key mapper large font",
    widthDp = 360,
    heightDp = 640,
    fontScale = 1.5f,
    showBackground = true,
)
@Composable
fun KeyMapperLargeFontScreenshot() {
    JLModPlusTheme {
        KeyMapperScreen(KeyMapperUiState(), NoOpKeyMapperActions)
    }
}
