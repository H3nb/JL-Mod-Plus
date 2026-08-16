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
import androidx.compose.ui.text.input.KeyboardType
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.config.model.Size
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val NoOpConfigEvents = object : ConfigFormEvents {
    override fun onFormChanged(state: ConfigFormState) = Unit
    override fun onAddResolutionPreset(size: Size) = Unit
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
        ConfigScreen(PreviewConfigState, NoOpConfigEvents, initialDestination = ConfigDestination.Graphics)
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
        ConfigScreen(PreviewConfigState, NoOpConfigEvents, initialDestination = ConfigDestination.Graphics)
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
        ConfigScreen(PreviewConfigState, NoOpConfigEvents, initialDestination = ConfigDestination.Graphics)
    }
}

@PreviewTest
@Preview(name = "Config quick light", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun ConfigQuickScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        ConfigScreen(PreviewConfigState, NoOpConfigEvents)
    }
}

@PreviewTest
@Preview(
    name = "Config quick dark",
    widthDp = 360,
    heightDp = 800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun ConfigQuickDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        ConfigScreen(PreviewConfigState, NoOpConfigEvents)
    }
}

@PreviewTest
@Preview(name = "Config media empty", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun ConfigMediaEmptyScreenshot() {
    JLModPlusTheme {
        ConfigScreen(PreviewConfigState, NoOpConfigEvents, initialDestination = ConfigDestination.Media)
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
@Preview(
    name = "Config color picker dark",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun ConfigColorPickerDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
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
        ConfigScreen(PreviewConfigState, NoOpConfigEvents, initialDestination = ConfigDestination.Graphics)
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

@PreviewTest
@Preview(name = "Config number dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ConfigNumberDialogScreenshot() {
    JLModPlusTheme {
        ConfigNumberDialog(
            title = "Width",
            initialValue = "240",
            label = "Width",
            keyboardType = KeyboardType.Number,
            onDismissRequest = {},
            onConfirm = {},
        )
    }
}

@PreviewTest
@Preview(name = "Config slider dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ConfigSliderDialogScreenshot() {
    JLModPlusTheme {
        ConfigSliderDialog(
            title = "Opacity",
            initialValue = 64,
            valueRange = 0..255,
            onDismissRequest = {},
            onConfirm = {},
        )
    }
}

@PreviewTest
@Preview(name = "Config choice dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ConfigChoiceDialogScreenshot() {
    JLModPlusTheme {
        ConfigChoiceDialog(
            title = "Screen orientation",
            selected = "Automatic",
            options = listOf(
                "Automatic",
                "Landscape",
                "Reverse landscape",
                "Portrait",
                "Reverse portrait",
            ),
            onDismissRequest = {},
            onSelected = {},
        )
    }
}

@PreviewTest
@Preview(name = "Config screen presets", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ConfigScreenPresetDialogScreenshot() {
    JLModPlusTheme {
        ScreenPresetDialog(
            presets = listOf(
                Size(128, 128),
                Size(128, 160),
                Size(176, 220),
                Size(240, 320),
                Size(352, 416),
                Size(360, 640),
                Size(480, 800),
                Size(640, 360),
                Size(800, 480),
                Size(1080, 1920),
            ),
            removablePresets = listOf(Size(360, 640)),
            selectedPreset = Size(240, 320),
            onDismissRequest = {},
            onSelected = {},
            onAdd = { _ -> },
            onRemove = {},
            useModalBottomSheet = false,
        )
    }
}

@PreviewTest
@Preview(name = "Config custom resolution", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ConfigCustomResolutionScreenshot() {
    JLModPlusTheme {
        CustomResolutionDialog(
            initialSize = Size(240, 320),
            onDismissRequest = {},
            onSave = {},
        )
    }
}

@PreviewTest
@Preview(name = "Config system properties", widthDp = 360, heightDp = 800, showBackground = true)
@Composable
fun ConfigSystemPropertiesScreenshot() {
    JLModPlusTheme {
        ConfigScreen(
            PreviewConfigState,
            NoOpConfigEvents,
            initialDestination = ConfigDestination.System,
        )
    }
}
