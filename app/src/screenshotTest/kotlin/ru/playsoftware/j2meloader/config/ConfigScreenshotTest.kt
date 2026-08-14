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

package ru.playsoftware.j2meloader.config

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.config.model.Size
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val NoOpConfigEvents = object : ConfigFormEvents {
    override fun onFormChanged(state: ConfigFormState) = Unit
    override fun onAddResolutionPreset() = Unit
    override fun onRemoveResolutionPreset(size: Size) = Unit
    override fun onColorPicker(field: ConfigFormEvents.ColorField) = Unit
    override fun onColorPicked(field: ConfigFormEvents.ColorField, value: String) = Unit
    override fun onKeyMappings() = Unit
    override fun onEncodingPicker() = Unit
    override fun onShaderTuning() = Unit
}

private val PreviewConfigState = ConfigUiState(
    ConfigFormState.builder()
        .screenWidth("240")
        .screenHeight("320")
        .screenBackground("D0D0D0")
        .screenScaleRatio("100")
        .screenPadding("0")
        .fpsLimit("60")
        .fontSizeSmall("18")
        .fontSizeMedium("22")
        .fontSizeLarge("26")
        .vkHideDelay("250")
        .vkBackground("D0D0D0")
        .vkForeground("000080")
        .vkSelectedBackground("000080")
        .vkSelectedForeground("FFFFFF")
        .vkOutline("FFFFFF")
        .systemProperties("microedition.platform: Sony Ericsson C510i\nmicroedition.profiles: MIDP2.0\n")
        .showKeyboard(true)
        .touchInput(true)
        .vkFeedback(true)
        .vkAlpha(64)
        .graphicsMode(1)
        .build(),
    listOf(Size(240, 320), Size(360, 640)),
    listOf(ConfigUiState.FontPreset("240 x 320", 18, 22, 26)),
    listOf("Not set", "Default skin"),
    listOf("Android (default)", "custom.sf2"),
    emptyList(),
)

@PreviewTest
@Preview(name = "Config light phone", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun ConfigLightPhoneScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        ConfigScreen(PreviewConfigState, NoOpConfigEvents)
    }
}

@PreviewTest
@Preview(
    name = "Config dark phone",
    widthDp = 360,
    heightDp = 800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun ConfigDarkPhoneScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        ConfigScreen(PreviewConfigState, NoOpConfigEvents)
    }
}

@PreviewTest
@Preview(
    name = "Config large font form",
    widthDp = 360,
    heightDp = 800,
    fontScale = 1.5f,
    showBackground = true,
)
@Composable
fun ConfigLargeFontFormScreenshot() {
    JLModPlusTheme {
        ConfigScreen(PreviewConfigState, NoOpConfigEvents)
    }
}

@PreviewTest
@Preview(name = "Config color picker", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ConfigColorPickerScreenshot() {
    JLModPlusTheme {
        ConfigColorPickerDialog(
            initialHex = "D0D0D0",
            onDismissRequest = {},
            onConfirm = {},
        )
    }
}

@PreviewTest
@Preview(name = "Config landscape", widthDp = 640, heightDp = 360, showBackground = true)
@Composable
fun ConfigLandscapeScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        ConfigScreen(PreviewConfigState, NoOpConfigEvents)
    }
}

@PreviewTest
@Preview(name = "Config color picker landscape", widthDp = 640, heightDp = 360, showBackground = true)
@Composable
fun ConfigColorPickerLandscapeScreenshot() {
    JLModPlusTheme {
        ConfigColorPickerDialog(
            initialHex = "D0D0D0",
            onDismissRequest = {},
            onConfirm = {},
        )
    }
}
