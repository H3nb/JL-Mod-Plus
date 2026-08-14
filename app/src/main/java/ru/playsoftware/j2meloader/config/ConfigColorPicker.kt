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

import android.graphics.Color as AndroidColor
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import ru.playsoftware.j2meloader.R

/** A dependency-free HSV picker used only by the host configuration presentation. */
@Composable
internal fun ConfigColorPickerDialog(
    initialHex: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var hsv by remember(initialHex) { mutableStateOf(parseHsv(initialHex)) }
    var hexText by remember(initialHex) { mutableStateOf(formatHsv(hsv)) }
    val color = Color.hsv(hsv[0], hsv[1], hsv[2])
    val hexIsValid = hexText.length == 6
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun updateHsv(hue: Float = hsv[0], saturation: Float = hsv[1], value: Float = hsv[2]) {
        hsv = floatArrayOf(
            hue.coerceIn(0f, 360f),
            saturation.coerceIn(0f, 1f),
            value.coerceIn(0f, 1f),
        )
        hexText = formatHsv(hsv)
    }

    AlertDialog(
        modifier = if (landscape) {
            Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 760.dp)
        } else {
            Modifier.widthIn(max = 560.dp)
        },
        properties = DialogProperties(usePlatformDefaultWidth = !landscape),
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.config_color_picker_title)) },
        text = {
            BoxWithConstraints {
                val landscape = maxWidth > maxHeight && maxHeight != Dp.Infinity
                val pickerHeight = if (maxHeight == Dp.Infinity) {
                    180.dp
                } else {
                    (maxHeight * if (landscape) 0.68f else 0.38f)
                        .coerceIn(96.dp, 180.dp)
                }
                if (landscape) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SaturationValuePicker(
                                modifier = Modifier.fillMaxWidth(),
                                hue = hsv[0],
                                saturation = hsv[1],
                                value = hsv[2],
                                height = pickerHeight,
                                onChanged = { saturation, value ->
                                    updateHsv(saturation = saturation, value = value)
                                },
                            )
                            HuePicker(
                                hue = hsv[0],
                                onChanged = { hue -> updateHsv(hue = hue) },
                            )
                        }
                        Column(
                            modifier = Modifier.weight(0.85f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ColorValueInput(
                                hexText = hexText,
                                color = color,
                                hexIsValid = hexIsValid,
                                showPreview = true,
                                onHexChanged = { value ->
                                    val normalized = normalizePickerHex(value)
                                    hexText = normalized
                                    if (normalized.length == 6) {
                                        hsv = parseHsv(normalized)
                                    }
                                },
                            )
                            ColorPickerHint()
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SaturationValuePicker(
                            hue = hsv[0],
                            saturation = hsv[1],
                            value = hsv[2],
                            height = pickerHeight,
                            onChanged = { saturation, value ->
                                updateHsv(saturation = saturation, value = value)
                            },
                        )
                        HuePicker(
                            hue = hsv[0],
                            onChanged = { hue -> updateHsv(hue = hue) },
                        )
                        ColorValueInput(
                            hexText = hexText,
                            color = color,
                            hexIsValid = hexIsValid,
                            showPreview = true,
                            onHexChanged = { value ->
                                val normalized = normalizePickerHex(value)
                                hexText = normalized
                                if (normalized.length == 6) {
                                    hsv = parseHsv(normalized)
                                }
                            },
                        )
                        ColorPickerHint()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(formatHsv(hsv)) },
                enabled = hexIsValid,
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun ColorValueInput(
    hexText: String,
    color: Color,
    hexIsValid: Boolean,
    showPreview: Boolean,
    onHexChanged: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showPreview) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color),
            )
        }
        OutlinedTextField(
            value = hexText,
            onValueChange = onHexChanged,
            modifier = Modifier.weight(1f),
            label = { Text(stringResource(R.string.config_color_picker_hex)) },
            supportingText = if (!hexIsValid) {
                { Text(stringResource(R.string.config_color_picker_invalid)) }
            } else {
                null
            },
            isError = !hexIsValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii,
            ),
        )
    }
}

@Composable
private fun ColorPickerHint() {
    Text(
        text = stringResource(R.string.config_color_picker_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SaturationValuePicker(
    modifier: Modifier = Modifier,
    hue: Float,
    saturation: Float,
    value: Float,
    height: Dp,
    onChanged: (Float, Float) -> Unit,
) {
    val hueColor = Color.hsv(hue, 1f, 1f)
    val pickerDescription = stringResource(R.string.config_color_picker_saturation_value)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = pickerDescription
            }
            .pointerInput(hue) {
                detectTapGestures { position ->
                    onChanged(
                        normalizeCoordinate(position.x, size.width),
                        1f - normalizeCoordinate(position.y, size.height),
                    )
                }
            }
            .pointerInput(hue) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChanged(
                        normalizeCoordinate(change.position.x, size.width),
                        1f - normalizeCoordinate(change.position.y, size.height),
                    )
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val radius = 9.dp.toPx()
        drawCircle(
            color = Color.White,
            radius = radius,
            center = Offset(
                clampMarkerCenter(
                    saturation.coerceIn(0f, 1f) * size.width,
                    size.width,
                    radius,
                ),
                clampMarkerCenter(
                    (1f - value.coerceIn(0f, 1f)) * size.height,
                    size.height,
                    radius,
                ),
            ),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun HuePicker(
    hue: Float,
    onChanged: (Float) -> Unit,
) {
    val colors = remember {
        (0..6).map { index -> Color.hsv(index * 60f, 1f, 1f) }
    }
    val pickerDescription = stringResource(R.string.config_color_picker_hue)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = pickerDescription }
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    onChanged(normalizeCoordinate(position.x, size.width) * 360f)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChanged(normalizeCoordinate(change.position.x, size.width) * 360f)
                }
            },
    ) {
        drawRect(Brush.horizontalGradient(colors))
        val radius = 8.dp.toPx()
        drawCircle(
            color = Color.White,
            radius = radius,
            center = Offset(
                clampMarkerCenter(
                    hue.coerceIn(0f, 360f) / 360f * size.width,
                    size.width,
                    radius,
                ),
                size.height / 2f,
            ),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

private fun normalizeCoordinate(value: Float, size: Int): Float =
    if (size <= 0) 0f else (value / size.toFloat()).coerceIn(0f, 1f)

private fun clampMarkerCenter(value: Float, size: Float, radius: Float): Float {
    if (size <= radius * 2f) return size / 2f
    return value.coerceIn(radius, size - radius)
}

private fun normalizePickerHex(value: String): String = value
    .filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    .uppercase(Locale.ROOT)
    .take(6)

private fun parseHsv(value: String): FloatArray {
    val rgb = (value.toLongOrNull(16)?.and(0xFFFFFFL) ?: 0L).toInt()
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(
        AndroidColor.rgb(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF),
        hsv,
    )
    return hsv
}

private fun formatHsv(hsv: FloatArray): String = String.format(
    Locale.ROOT,
    "%06X",
    AndroidColor.HSVToColor(hsv) and 0xFFFFFF,
)
