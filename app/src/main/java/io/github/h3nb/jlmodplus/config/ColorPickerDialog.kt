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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
)

/** Pure RGB/HSV math used by the color picker and its JVM tests. */
internal object ColorPickerMath {
    fun fromArgb(color: Int): HsvColor {
        val red = ((color shr 16) and 0xFF) / 255f
        val green = ((color shr 8) and 0xFF) / 255f
        val blue = (color and 0xFF) / 255f
        val maximum = maxOf(red, green, blue)
        val minimum = minOf(red, green, blue)
        val delta = maximum - minimum
        val hue = when {
            delta == 0f -> 0f
            maximum == red -> (60f * ((green - blue) / delta) + 360f) % 360f
            maximum == green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }
        return HsvColor(
            hue = hue.coerceIn(0f, 360f),
            saturation = if (maximum == 0f) 0f else (delta / maximum).coerceIn(0f, 1f),
            value = maximum.coerceIn(0f, 1f),
        )
    }

    fun toArgb(color: HsvColor): Int {
        val hue = ((color.hue % 360f) + 360f) % 360f
        val saturation = color.saturation.coerceIn(0f, 1f)
        val value = color.value.coerceIn(0f, 1f)
        val chroma = value * saturation
        val section = hue / 60f
        val x = chroma * (1f - abs(section % 2f - 1f))
        val (red, green, blue) = when {
            section < 1f -> Triple(chroma, x, 0f)
            section < 2f -> Triple(x, chroma, 0f)
            section < 3f -> Triple(0f, chroma, x)
            section < 4f -> Triple(0f, x, chroma)
            section < 5f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
        val match = value - chroma
        val redByte = ((red + match) * 255f).roundToInt().coerceIn(0, 255)
        val greenByte = ((green + match) * 255f).roundToInt().coerceIn(0, 255)
        val blueByte = ((blue + match) * 255f).roundToInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (redByte shl 16) or (greenByte shl 8) or blueByte
    }

    fun hueFromPosition(y: Float, height: Float): Float {
        if (height <= 0f) return 0f
        val fraction = (y / height).coerceIn(0f, 1f)
        val hue = (1f - fraction) * 360f
        return if (hue >= 360f) 0f else hue
    }

    fun saturationFromPosition(x: Float, width: Float): Float =
        if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    fun valueFromPosition(y: Float, height: Float): Float =
        if (height <= 0f) 1f else (1f - y / height).coerceIn(0f, 1f)

    fun hueCursorPosition(hue: Float, height: Float): Float {
        if (height <= 0f || hue <= 0f) return 0f
        return (1f - hue.coerceIn(0f, 360f) / 360f) * height
    }
}

@Composable
internal fun ColorPickerDialogContent(
    initialColor: Int,
    positiveLabel: String,
    negativeLabel: String,
    onConfirmed: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember(initialColor) { ColorPickerMath.fromArgb(initialColor) }
    var hue by rememberSaveable(initialColor) { mutableFloatStateOf(initialHsv.hue) }
    var saturation by rememberSaveable(initialColor) { mutableFloatStateOf(initialHsv.saturation) }
    var value by rememberSaveable(initialColor) { mutableFloatStateOf(initialHsv.value) }
    val selectedColor = ColorPickerMath.toArgb(HsvColor(hue, saturation, value))
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (landscape) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                ColorPreviewState(
                    initialColor = initialColor,
                    selectedColor = selectedColor,
                    landscape = true,
                )
                Spacer(Modifier.width(8.dp))
                PickerControls(
                    modifier = Modifier.weight(1f),
                    landscape = true,
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onHsvPositionChanged = { position, widthPx, heightPx ->
                        saturation = ColorPickerMath.saturationFromPosition(position.x, widthPx)
                        value = ColorPickerMath.valueFromPosition(position.y, heightPx)
                    },
                    onHuePositionChanged = { position, heightPx ->
                        hue = ColorPickerMath.hueFromPosition(position.y, heightPx)
                    },
                )
            }
        } else {
            PickerControls(
                landscape = false,
                hue = hue,
                saturation = saturation,
                value = value,
                onHsvPositionChanged = { position, widthPx, heightPx ->
                    saturation = ColorPickerMath.saturationFromPosition(position.x, widthPx)
                    value = ColorPickerMath.valueFromPosition(position.y, heightPx)
                },
                onHuePositionChanged = { position, heightPx ->
                    hue = ColorPickerMath.hueFromPosition(position.y, heightPx)
                },
            )
            Spacer(Modifier.height(8.dp))
            ColorPreviewState(
                initialColor = initialColor,
                selectedColor = selectedColor,
                landscape = false,
            )
        }

        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onDismiss) { Text(negativeLabel) }
            TextButton(onClick = { onConfirmed(selectedColor) }) { Text(positiveLabel) }
        }
    }
}

@Composable
private fun PickerControls(
    modifier: Modifier = Modifier,
    landscape: Boolean,
    hue: Float,
    saturation: Float,
    value: Float,
    onHsvPositionChanged: (Offset, Float, Float) -> Unit,
    onHuePositionChanged: (Offset, Float) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthReserve = if (landscape) 100.dp else 40.dp
        val pickerWidth = maxOf(160.dp, minOf(240.dp, maxWidth - widthReserve))
        val pickerHeight = if (landscape && !isLargeLandscape()) 120.dp else pickerWidth
        val hueWidth = maxOf(24.dp, pickerWidth * (30f / 240f))
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            HsvSquare(
                width = pickerWidth,
                height = pickerHeight,
                hue = hue,
                saturation = saturation,
                value = value,
                onPositionChanged = onHsvPositionChanged,
            )
            Spacer(Modifier.width(8.dp))
            HueStrip(
                height = pickerHeight,
                width = hueWidth,
                hue = hue,
                onPositionChanged = onHuePositionChanged,
            )
        }
    }
}

@Composable
private fun isLargeLandscape(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return with(density) {
        containerSize.width.toDp() >= 600.dp && containerSize.height.toDp() >= 600.dp
    }
}

@Composable
private fun ColorPreviewState(
    initialColor: Int,
    selectedColor: Int,
    landscape: Boolean,
) {
    val currentDescription = stringResource(R.string.color_picker_current_color)
    val selectedDescription = stringResource(R.string.color_picker_selected_color)
    if (landscape) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ColorPreview(color = initialColor or 0xFF000000.toInt(), contentDescription = currentDescription)
            ArrowPreview(vertical = true)
            ColorPreview(color = selectedColor, contentDescription = selectedDescription)
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ColorPreview(color = initialColor or 0xFF000000.toInt(), contentDescription = currentDescription)
            ArrowPreview()
            ColorPreview(color = selectedColor, contentDescription = selectedDescription)
        }
    }
}

@Composable
private fun HsvSquare(
    width: Dp,
    height: Dp,
    hue: Float,
    saturation: Float,
    value: Float,
    onPositionChanged: (Offset, Float, Float) -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { width.toPx() }
    val heightPx = with(density) { height.toPx() }
    val hueColor = Color.hsv(hue, 1f, 1f)
    val saturationValueDescription = stringResource(R.string.color_picker_saturation_value)
    Canvas(
        modifier = Modifier
            .size(width = width, height = height)
            .semantics { contentDescription = saturationValueDescription }
            .pointerInput(widthPx, heightPx) {
                detectTapGestures { position -> onPositionChanged(position, widthPx, heightPx) }
            }
            .pointerInput(widthPx, heightPx) {
                detectDragGestures(
                    onDragStart = { position -> onPositionChanged(position, widthPx, heightPx) },
                    onDrag = { change, _ ->
                        onPositionChanged(change.position, widthPx, heightPx)
                    },
                )
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

        val target = Offset(
            x = saturation.coerceIn(0f, 1f) * size.width,
            y = (1f - value.coerceIn(0f, 1f)) * size.height,
        )
        drawCircle(Color.White, radius = 8.dp.toPx(), center = target, style = Stroke(width = 2.dp.toPx()))
        drawCircle(Color.Black, radius = 6.dp.toPx(), center = target, style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun HueStrip(
    height: Dp,
    width: Dp,
    hue: Float,
    onPositionChanged: (Offset, Float) -> Unit,
) {
    val density = LocalDensity.current
    val heightPx = with(density) { height.toPx() }
    val hueDescription = stringResource(R.string.color_picker_hue)
    val colors = listOf(Color.Red, Color.Magenta, Color.Blue, Color.Cyan, Color.Green, Color.Yellow, Color.Red)
    Canvas(
        modifier = Modifier
            .width(width)
            .height(height)
            .semantics { contentDescription = hueDescription }
            .pointerInput(heightPx) {
                detectTapGestures { position -> onPositionChanged(position, heightPx) }
            }
            .pointerInput(heightPx) {
                detectDragGestures(
                    onDragStart = { position -> onPositionChanged(position, heightPx) },
                    onDrag = { change, _ ->
                        onPositionChanged(change.position, heightPx)
                    },
                )
            },
    ) {
        drawRect(Brush.verticalGradient(colors))
        val y = ColorPickerMath.hueCursorPosition(hue, size.height)
        drawLine(Color.Black, Offset(0f, y), Offset(size.width, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Square)
        drawLine(Color.White, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Square)
    }
}

@Composable
private fun ColorPreview(color: Int, contentDescription: String) {
    Box(
        modifier = Modifier
            .size(width = 60.dp, height = 30.dp)
            .background(Color(color))
            .semantics { this.contentDescription = contentDescription },
    )
}

@Composable
private fun ArrowPreview(vertical: Boolean = false) {
    val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = Modifier.size(28.dp)) {
        if (vertical) {
            val centerX = size.width / 2f
            val start = Offset(centerX, 4.dp.toPx())
            val end = Offset(centerX, size.height - 4.dp.toPx())
            drawLine(arrowColor, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(arrowColor, end, Offset(end.x - 5.dp.toPx(), end.y - 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(arrowColor, end, Offset(end.x + 5.dp.toPx(), end.y - 6.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        } else {
            val centerY = size.height / 2f
            val start = Offset(4.dp.toPx(), centerY)
            val end = Offset(size.width - 4.dp.toPx(), centerY)
            drawLine(arrowColor, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(arrowColor, end, Offset(end.x - 6.dp.toPx(), end.y - 5.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(arrowColor, end, Offset(end.x - 6.dp.toPx(), end.y + 5.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Preview(name = "Color Picker", showBackground = true, widthDp = 420, heightDp = 480)
@Composable
private fun ColorPickerPreview() {
    AppComposeTheme {
        ColorPickerDialogContent(
            initialColor = 0xFF336699.toInt(),
            positiveLabel = "OK",
            negativeLabel = "Cancel",
            onConfirmed = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Color Picker Dark", showBackground = true, widthDp = 420, heightDp = 480, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ColorPickerDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        ColorPickerDialogContent(
            initialColor = 0xFFCC6600.toInt(),
            positiveLabel = "OK",
            negativeLabel = "Cancel",
            onConfirmed = {},
            onDismiss = {},
        )
    }
}
