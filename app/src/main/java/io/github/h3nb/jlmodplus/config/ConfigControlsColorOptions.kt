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
package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R

private data class ControlColorOption(
    val labelRes: Int,
    val accessibilityRes: Int,
    val value: String,
    val field: ConfigFormEvents.ColorField,
    val testTag: String,
)

/** An inline palette; configuration and picker state remain owned by ConfigActivity. */
@Composable
internal fun ConfigControlsColorOptions(
    form: ConfigFormState,
    onColorPicker: (ConfigFormEvents.ColorField) -> Unit,
) {
    val options = listOf(
        ControlColorOption(
            R.string.config_controls_color_button_normal,
            R.string.PREF_VK_BACK,
            form.vkBackground,
            ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_BACKGROUND,
            "color_normal_button",
        ),
        ControlColorOption(
            R.string.config_controls_color_button_pressed,
            R.string.PREF_VK_SEL_BACK,
            form.vkSelectedBackground,
            ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_BACKGROUND,
            "color_pressed_button",
        ),
        ControlColorOption(
            R.string.config_controls_color_label_normal,
            R.string.PREF_VK_FORE,
            form.vkForeground,
            ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_FOREGROUND,
            "color_normal_labels",
        ),
        ControlColorOption(
            R.string.config_controls_color_label_pressed,
            R.string.PREF_VK_SEL_FORE,
            form.vkSelectedForeground,
            ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_SELECTED_FOREGROUND,
            "color_pressed_labels",
        ),
        ControlColorOption(
            R.string.config_controls_color_outline_short,
            R.string.PREF_VK_OUTLINE,
            form.vkOutline,
            ConfigFormEvents.ColorField.VIRTUAL_KEYBOARD_OUTLINE,
            "color_outline",
        ),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("controls_colors_section")
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.config_controls_virtual_keyboard_colors),
            style = MaterialTheme.typography.bodyLarge,
        )
        VirtualControlsColorPreview(form)
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Five swatches at ordinary text size; reflow rather than shrink fonts on narrow windows.
            val fiveColumns = maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.3f
            if (fiveColumns) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    options.forEach { option ->
                        ControlColorSwatch(option, Modifier.weight(1f), onColorPicker)
                    }
                }
            } else {
                Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        options.take(3).forEach { option ->
                            ControlColorSwatch(option, Modifier.weight(1f), onColorPicker)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(0.5f))
                        options.drop(3).forEach { option ->
                            ControlColorSwatch(option, Modifier.weight(1f), onColorPicker)
                        }
                        Spacer(Modifier.weight(0.5f))
                    }
                }
            }
        }
        if (form.controlSkinSetId != null) {
            Text(
                text = stringResource(R.string.config_controls_color_skin_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun ControlColorSwatch(
    option: ControlColorOption,
    modifier: Modifier = Modifier,
    onColorPicker: (ConfigFormEvents.ColorField) -> Unit,
) {
    val accessibleName = stringResource(option.accessibilityRes)
    Column(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clickable(role = Role.Button) { onColorPicker(option.field) }
            .testTag(option.testTag)
            .semantics { contentDescription = "$accessibleName, #${option.value}" }
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.heightIn(min = 38.dp),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(option.color(), MaterialTheme.shapes.extraSmall)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
        )
    }
}

private fun ControlColorOption.color(): Color {
    val rgb = value.removePrefix("#").toLongOrNull(16)?.and(0xFFFFFF) ?: 0
    return Color((0xFF000000L or rgb).toInt())
}
