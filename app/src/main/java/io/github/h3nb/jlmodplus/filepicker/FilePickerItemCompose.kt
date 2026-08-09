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

package io.github.h3nb.jlmodplus.filepicker

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FilePickerItemContent(
    title: String,
    isDirectory: Boolean,
    checkable: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Boolean,
    onCheckboxClick: (Boolean) -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (enabled) 1f else 0.38f,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 46.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = onClick,
                    onLongClick = {
                        onLongClick()
                    },
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isDirectory) {
                FolderGlyph(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.38f,
                    ),
                    modifier = Modifier
                        .size(40.dp)
                        .padding(end = 10.dp),
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = contentColor,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (checkable) {
                Checkbox(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckboxClick,
                )
            }
        }
    }
}

@Composable
private fun FolderGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.5.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.05f, size.height * 0.28f)
            lineTo(size.width * 0.38f, size.height * 0.28f)
            lineTo(size.width * 0.48f, size.height * 0.42f)
            lineTo(size.width * 0.95f, size.height * 0.42f)
            lineTo(size.width * 0.95f, size.height * 0.88f)
            lineTo(size.width * 0.05f, size.height * 0.88f)
            close()
        }
        drawPath(path, color = color.copy(alpha = 0.18f))
        drawPath(path, color = color, style = Stroke(width = strokeWidth))
        drawLine(
            color = color,
            start = Offset(size.width * 0.05f, size.height * 0.42f),
            end = Offset(size.width * 0.48f, size.height * 0.42f),
            strokeWidth = strokeWidth,
        )
    }
}

@Composable
private fun FilePickerRowsPreviewContent(darkTheme: Boolean) {
    AppComposeTheme(darkTheme = darkTheme) {
        Column {
            FilePickerItemContent(
                title = "..",
                isDirectory = true,
                checkable = false,
                checked = false,
                enabled = true,
                onClick = {},
                onLongClick = { false },
                onCheckboxClick = {},
            )
            FilePickerItemContent(
                title = "Download",
                isDirectory = true,
                checkable = true,
                checked = false,
                enabled = true,
                onClick = {},
                onLongClick = { false },
                onCheckboxClick = {},
            )
            FilePickerItemContent(
                title = "Asphalt - Urban GT 3D.jar",
                isDirectory = false,
                checkable = true,
                checked = true,
                enabled = true,
                onClick = {},
                onLongClick = { false },
                onCheckboxClick = {},
            )
        }
    }
}

@Preview(name = "File picker rows", showBackground = true, widthDp = 420, heightDp = 160)
@Composable
internal fun FilePickerRowsPreview() {
    FilePickerRowsPreviewContent(darkTheme = false)
}

@Preview(
    name = "File picker rows dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 160,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun FilePickerRowsDarkPreview() {
    FilePickerRowsPreviewContent(darkTheme = true)
}
