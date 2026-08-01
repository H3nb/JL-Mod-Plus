/*
 * Copyright 2026 H3NB
 *
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

package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import java.text.DecimalFormat
import kotlin.math.roundToInt

private val DialogHorizontalPadding = 24.dp

private data class ShaderSlider(
    val setting: ShaderInfo.Setting,
    val step: Float,
    val maxProgress: Int,
)

private fun createShaderSliders(shader: ShaderInfo): List<ShaderSlider?> =
    shader.settings.map { setting ->
        setting?.let {
            val step = if (it.step > 0f) it.step else (it.max - it.min) / 100f
            ShaderSlider(it, step, ((it.max - it.min) / step).toInt())
        }
    }

@Composable
private fun ShaderTuneContent(
    settings: List<ShaderSlider?>,
    initialValues: FloatArray,
    defaultValues: FloatArray,
    resetSignal: Int,
    onValuesChanged: (FloatArray) -> Unit,
) {
    var sliderValues by remember(resetSignal) {
        mutableStateOf(
            if (resetSignal == 0) initialValues.copyOf() else defaultValues.copyOf(),
        )
    }
    val format = remember { DecimalFormat("#.######") }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        ) {
            settings.forEachIndexed { index, slider ->
                if (slider != null) {
                    val progress = ((sliderValues[index] - slider.setting.min) / slider.step)
                        .roundToInt()
                        .coerceIn(0, slider.maxProgress)
                    Text(
                        text = stringResource(
                            R.string.shader_setting,
                            slider.setting.name,
                            format.format(slider.setting.min + progress * slider.step),
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 10.dp),
                    )
                    Slider(
                        value = progress.toFloat(),
                        onValueChange = { rawProgress ->
                            val newProgress = rawProgress.roundToInt().coerceIn(0, slider.maxProgress)
                            val copy = sliderValues.copyOf()
                            copy[index] = slider.setting.min + newProgress * slider.step
                            sliderValues = copy
                            onValuesChanged(copy)
                        },
                        valueRange = 0f..slider.maxProgress.toFloat(),
                        steps = (slider.maxProgress - 1).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileNameContent(
    value: String,
    hintRes: Int,
    focusSignal: Int,
    onValueChanged: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(focusSignal) {
        if (focusSignal > 0) focusRequester.requestFocus()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DialogHorizontalPadding)
                .focusRequester(focusRequester),
            singleLine = true,
            label = { Text(stringResource(hintRes)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        )
    }
}

@Composable
private fun SaveProfileContent(
    profileName: String,
    configChecked: Boolean,
    keyboardChecked: Boolean,
    defaultChecked: Boolean,
    focusSignal: Int,
    onNameChanged: (String) -> Unit,
    onConfigChanged: (Boolean) -> Unit,
    onKeyboardChanged: (Boolean) -> Unit,
    onDefaultChanged: (Boolean) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(focusSignal) {
        if (focusSignal > 0) focusRequester.requestFocus()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DialogHorizontalPadding),
        ) {
            OutlinedTextField(
                value = profileName,
                onValueChange = onNameChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                label = { Text(stringResource(R.string.enter_name)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
            CheckboxRow(true, stringResource(R.string.action_settings), onConfigChanged)
            CheckboxRow(true, stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS), onKeyboardChanged)
            CheckboxRow(defaultChecked, stringResource(R.string.set_as_default), onDefaultChanged)
        }
    }
}

@Composable
private fun LoadProfileContent(
    profiles: List<Profile>,
    selectedIndex: Int,
    configChecked: Boolean,
    keyboardChecked: Boolean,
    configEnabled: Boolean,
    keyboardEnabled: Boolean,
    onProfileSelected: (Int) -> Unit,
    onConfigChanged: (Boolean) -> Unit,
    onKeyboardChanged: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp),
        ) {
            LazyColumn(modifier = Modifier.heightIn(min = 48.dp, max = 320.dp)) {
                itemsIndexed(profiles) { index, profile ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProfileSelected(index) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selectedIndex == index,
                            onClick = { onProfileSelected(index) },
                        )
                        Text(profile.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            HorizontalDivider()
            CheckboxRow(configChecked, stringResource(R.string.action_settings), onConfigChanged, configEnabled)
            CheckboxRow(
                keyboardChecked,
                stringResource(R.string.PREF_VIRTUAL_KEYBOARD_OPTIONS),
                onKeyboardChanged,
                keyboardEnabled,
            )
        }
    }
}

@Composable
private fun CheckboxRow(
    checked: Boolean,
    text: String,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Checkbox(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        Text(
            text = text,
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Preview(name = "Shader tuning", showBackground = true, widthDp = 420)
@Composable
internal fun ShaderTunePreview() {
    ShaderTunePreviewContent(darkTheme = false)
}

@Composable
private fun ShaderTunePreviewContent(darkTheme: Boolean) {
    val shader = remember {
        ShaderInfo().apply {
            set("SettingName1 = Curvature")
            set("SettingDefaultValue1 = 0.25")
            set("SettingMinValue1 = 0")
            set("SettingMaxValue1 = 1")
            set("SettingStep1 = 0.05")
            set("SettingName2 = Scanline strength")
            set("SettingDefaultValue2 = 0.6")
            set("SettingMinValue2 = 0")
            set("SettingMaxValue2 = 1")
            set("SettingStep2 = 0.05")
        }
    }
    val settings = remember(shader) { createShaderSliders(shader) }
    val defaults = remember(settings) {
        FloatArray(4) { index -> settings[index]?.setting?.def ?: 0f }
    }
    AppComposeTheme(darkTheme = darkTheme) {
        ShaderTuneContent(settings, defaults, defaults, 0, {})
    }
}

@Preview(name = "Shader tuning dark", showBackground = true, widthDp = 420, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ShaderTuneDarkPreview() {
    ShaderTunePreviewContent(darkTheme = true)
}

@Preview(name = "Profile name", showBackground = true, widthDp = 420)
@Composable
internal fun ProfileNamePreview() {
    AppComposeTheme {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ProfileNameContent("Example", R.string.enter_name, 0) {}
        }
    }
}

@Preview(name = "Profile name dark", showBackground = true, widthDp = 420, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun ProfileNameDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            ProfileNameContent("Example", R.string.enter_name, 0) {}
        }
    }
}

@Preview(name = "Save profile", showBackground = true, widthDp = 420)
@Composable
internal fun SaveProfilePreview() {
    AppComposeTheme {
        SaveProfileContent("Adventure", true, true, false, 0, {}, {}, {}, {})
    }
}

@Preview(name = "Save profile dark", showBackground = true, widthDp = 420, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun SaveProfileDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        SaveProfileContent("Adventure", true, true, false, 0, {}, {}, {}, {})
    }
}

@Preview(name = "Load profile", showBackground = true, widthDp = 420)
@Composable
internal fun LoadProfilePreview() {
    AppComposeTheme {
        LoadProfileContent(
            profiles = listOf(Profile("Default"), Profile("Adventure"), Profile("Touch controls")),
            selectedIndex = 1,
            configChecked = true,
            keyboardChecked = true,
            configEnabled = true,
            keyboardEnabled = true,
            onProfileSelected = {},
            onConfigChanged = {},
            onKeyboardChanged = {},
        )
    }
}

@Preview(name = "Load profile dark", showBackground = true, widthDp = 420, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
internal fun LoadProfileDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        LoadProfileContent(
            profiles = listOf(Profile("Default"), Profile("Adventure"), Profile("Touch controls")),
            selectedIndex = 1,
            configChecked = true,
            keyboardChecked = true,
            configEnabled = true,
            keyboardEnabled = true,
            onProfileSelected = {},
            onConfigChanged = {},
            onKeyboardChanged = {},
        )
    }
}
