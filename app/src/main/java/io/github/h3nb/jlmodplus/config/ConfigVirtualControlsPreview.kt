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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import javax.microedition.lcdui.keyboard.VirtualControlAppearance
import io.github.h3nb.jlmodplus.R

@Composable
internal fun VirtualControlsColorPreview(form: ConfigFormState) {
    var pressed by rememberSaveable { mutableStateOf(false) }
    val background = parseColor(form.vkBackground)
    val foreground = parseColor(form.vkForeground)
    val selectedBackground = parseColor(form.vkSelectedBackground)
    val selectedForeground = parseColor(form.vkSelectedForeground)
    val outline = parseColor(form.vkOutline)
    val previewOpacity = form.vkAlpha.coerceIn(0, 255) / 255f
    val keyShape = when (form.vkButtonShape) {
        0 -> CircleShape
        1 -> RectangleShape
        else -> MaterialTheme.shapes.small
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("virtual_controls_color_preview")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.config_virtual_controls_color_preview),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilterChip(
                selected = !pressed,
                onClick = { pressed = false },
                label = { Text(stringResource(R.string.config_virtual_controls_preview_normal)) },
            )
            FilterChip(
                selected = pressed,
                onClick = { pressed = true },
                label = { Text(stringResource(R.string.config_virtual_controls_preview_pressed)) },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(previewOpacity),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(width = 52.dp, height = 40.dp),
                shape = keyShape,
                color = if (pressed) selectedBackground else background,
                border = BorderStroke(0.5.dp, outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "L",
                        color = if (pressed) selectedForeground else foreground,
                    )
                }
            }
            DpadColorSample(
                background, foreground, selectedBackground, selectedForeground,
                outline, pressed,
            )
            AnalogColorSample(
                background, selectedBackground, outline, pressed,
            )
        }
    }
}

@Composable
private fun DpadColorSample(
    background: Color,
    foreground: Color,
    selectedBackground: Color,
    selectedForeground: Color,
    outline: Color,
    pressed: Boolean,
) {
    Canvas(modifier = Modifier.size(72.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = minOf(cx, cy)
        val a = r * 0.34f
        val corner = r * 0.16f
        val inset = r * 0.09f
        val cross = Path().apply {
            moveTo(cx - a + corner, cy - r)
            lineTo(cx + a - corner, cy - r)
            quadraticBezierTo(cx + a, cy - r, cx + a, cy - r + corner)
            lineTo(cx + a, cy - a - inset)
            quadraticBezierTo(cx + a, cy - a, cx + a + inset, cy - a)
            lineTo(cx + r - corner, cy - a)
            quadraticBezierTo(cx + r, cy - a, cx + r, cy - a + corner)
            lineTo(cx + r, cy + a - corner)
            quadraticBezierTo(cx + r, cy + a, cx + r - corner, cy + a)
            lineTo(cx + a + inset, cy + a)
            quadraticBezierTo(cx + a, cy + a, cx + a, cy + a + inset)
            lineTo(cx + a, cy + r - corner)
            quadraticBezierTo(cx + a, cy + r, cx + a - corner, cy + r)
            lineTo(cx - a + corner, cy + r)
            quadraticBezierTo(cx - a, cy + r, cx - a, cy + r - corner)
            lineTo(cx - a, cy + a + inset)
            quadraticBezierTo(cx - a, cy + a, cx - a - inset, cy + a)
            lineTo(cx - r + corner, cy + a)
            quadraticBezierTo(cx - r, cy + a, cx - r, cy + a - corner)
            lineTo(cx - r, cy - a + corner)
            quadraticBezierTo(cx - r, cy - a, cx - r + corner, cy - a)
            lineTo(cx - a - inset, cy - a)
            quadraticBezierTo(cx - a, cy - a, cx - a, cy - a - inset)
            lineTo(cx - a, cy - r + corner)
            quadraticBezierTo(cx - a, cy - r, cx - a + corner, cy - r)
            close()
        }
        drawPath(cross, background)
        if (pressed) {
            val inset = r * 0.045f
            drawRoundRect(
                selectedBackground,
                topLeft = Offset(cx - a + inset, cy - r + inset),
                size = ComposeSize(2f * (a - inset), r - inset - r * 0.235f),
                cornerRadius = CornerRadius(r * 0.095f),
            )
        }
        drawPath(cross, outline, style = Stroke(0.5.dp.toPx()))
        drawCircle(background, r * 0.175f, Offset(cx, cy))
        drawCircle(
            lerp(background, outline, 0.45f),
            r * 0.175f, Offset(cx, cy),
            style = Stroke(0.5.dp.toPx()),
        )

        val arrow = r * 0.11f
        for (direction in 0..3) {
            val x = when (direction) {
                2 -> cx - r * 0.61f
                3 -> cx + r * 0.61f
                else -> cx
            }
            val y = when (direction) {
                0 -> cy - r * 0.61f
                1 -> cy + r * 0.61f
                else -> cy
            }
            val triangle = Path().apply {
                when (direction) {
                    0 -> {
                        moveTo(x, y - arrow)
                        lineTo(x - arrow, y + arrow)
                        lineTo(x + arrow, y + arrow)
                    }
                    1 -> {
                        moveTo(x, y + arrow)
                        lineTo(x - arrow, y - arrow)
                        lineTo(x + arrow, y - arrow)
                    }
                    2 -> {
                        moveTo(x - arrow, y)
                        lineTo(x + arrow, y - arrow)
                        lineTo(x + arrow, y + arrow)
                    }
                    else -> {
                        moveTo(x + arrow, y)
                        lineTo(x - arrow, y - arrow)
                        lineTo(x - arrow, y + arrow)
                    }
                }
                close()
            }
            drawPath(triangle, if (pressed && direction == 0) selectedForeground else foreground)
        }
    }
}

@Composable
private fun AnalogColorSample(
    background: Color,
    selectedBackground: Color,
    outline: Color,
    pressed: Boolean,
) {
    val backgroundRgb = background.toArgb() and 0xFFFFFF
    val thumbRgb = VirtualControlAppearance.analogThumbColor(
        backgroundRgb, selectedBackground.toArgb() and 0xFFFFFF, pressed,
    )
    fun opaque(rgb: Int) = Color(0xFF000000.toInt() or rgb)

    Canvas(modifier = Modifier.size(72.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        val shellRadius = radius * 0.94f
        val thumbRadius = radius * 0.45f
        val outlineStroke = Stroke(0.5.dp.toPx())

        // The palette and circle proportions mirror the runtime renderer.
        drawCircle(
            brush = Brush.linearGradient(
                listOf(
                    opaque(VirtualControlAppearance.analogShellTop(backgroundRgb)),
                    opaque(VirtualControlAppearance.analogShell(backgroundRgb)),
                    opaque(VirtualControlAppearance.analogShellBottom(backgroundRgb)),
                ),
                start = Offset(center.x - shellRadius, center.y - shellRadius),
                end = Offset(center.x + shellRadius, center.y + shellRadius),
            ),
            radius = shellRadius,
            center = center,
        )
        drawCircle(outline, shellRadius, center, style = outlineStroke)
        drawCircle(
            opaque(VirtualControlAppearance.analogShellRim(backgroundRgb)).copy(alpha = 0.2f),
            radius * 0.915f, center, style = outlineStroke,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    opaque(VirtualControlAppearance.analogBowlCenter(backgroundRgb)),
                    opaque(VirtualControlAppearance.analogBowlEdge(backgroundRgb)),
                ),
                center = center,
                radius = radius * 0.70f,
            ),
            radius = radius * 0.70f,
            center = center,
        )
        drawCircle(
            opaque(VirtualControlAppearance.analogThumbShadow(backgroundRgb)).copy(alpha = 0x5A / 255f),
            thumbRadius * 1.055f,
            Offset(center.x, center.y + thumbRadius * 0.045f),
        )
        drawCircle(opaque(VirtualControlAppearance.analogThumbRim(thumbRgb)),
            thumbRadius * 1.035f, center)
        drawCircle(
            brush = Brush.linearGradient(
                listOf(
                    opaque(VirtualControlAppearance.analogThumbTop(thumbRgb)),
                    opaque(thumbRgb),
                    opaque(VirtualControlAppearance.analogThumbBottom(thumbRgb)),
                ),
                start = Offset(center.x - thumbRadius, center.y - thumbRadius),
                end = Offset(center.x + thumbRadius, center.y + thumbRadius),
            ),
            radius = thumbRadius,
            center = center,
        )
        drawCircle(
            opaque(VirtualControlAppearance.analogThumbRim(thumbRgb)).copy(alpha = 0.33f),
            thumbRadius * 0.96f, center, style = outlineStroke,
        )
    }
}

private fun parseColor(value: String): Color {
    val parsed = value.toLongOrNull(16)?.and(0xFFFFFF) ?: 0
    return Color((0xFF000000L or parsed).toInt())
}
