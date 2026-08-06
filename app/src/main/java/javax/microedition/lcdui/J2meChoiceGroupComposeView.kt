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

package javax.microedition.lcdui

import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import javax.microedition.lcdui.list.CompoundItem

/** Compose-backed renderer for a J2ME ChoiceGroup item. */
class J2meChoiceGroupComposeView(
    context: android.content.Context,
    private val choiceType: Int,
    private val onItemClick: ItemCallback,
) : FrameLayout(context) {
    fun interface ItemCallback {
        fun onItemClick(itemId: Long)
    }

    private val composeView = ComposeView(context)
    private var itemState by mutableStateOf<kotlin.collections.List<J2meChoiceItemState>>(emptyList())

    init {
        composeView.id = generateViewId()
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        composeView.setContent {
            AppComposeTheme {
                J2meChoiceGroupContent(
                    choiceType = choiceType,
                    items = itemState,
                    onItemClick = onItemClick::onItemClick,
                )
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun setItems(items: java.util.List<CompoundItem>) {
        itemState = items.map { item ->
            J2meChoiceItemState(
                id = item.getUiId(),
                text = item.string,
                image = item.image?.bitmap,
                selected = item.isSelected,
            )
        }
    }

    fun requestSelection(@Suppress("UNUSED_PARAMETER") index: Int) {
        // Selection is derived from the J2ME model in setItems().
    }
}

private data class J2meChoiceItemState(
    val id: Long,
    val text: String,
    val image: Bitmap?,
    val selected: Boolean,
)

@Composable
private fun J2meChoiceGroupContent(
    choiceType: Int,
    items: kotlin.collections.List<J2meChoiceItemState>,
    onItemClick: (Long) -> Unit,
) {
    if (choiceType == Choice.POPUP) {
        J2mePopupChoice(items, onItemClick)
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
            ) {
                itemsIndexed(
                    items = items,
                    key = { _, item -> item.id },
                ) { index, item ->
                    J2meChoiceRow(
                        item = item,
                        choiceType = choiceType,
                        onClick = { onItemClick(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun J2meChoiceRow(
    item: J2meChoiceItemState,
    choiceType: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (choiceType) {
            Choice.EXCLUSIVE -> RadioButton(selected = item.selected, onClick = null)
            Choice.MULTIPLE -> Checkbox(checked = item.selected, onCheckedChange = null)
        }
        item.image?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.sizeIn(maxWidth = 48.dp, maxHeight = 48.dp),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            text = item.text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun J2mePopupChoice(
    items: kotlin.collections.List<J2meChoiceItemState>,
    onItemClick: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedIndex = items.indexOfFirst { it.selected }
    val selected = items.getOrNull(selectedIndex)
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true },
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                selected?.image?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.sizeIn(maxWidth = 48.dp, maxHeight = 48.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                Text(
                    text = selected?.text.orEmpty(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                val arrowColor = MaterialTheme.colorScheme.onSurfaceVariant
                androidx.compose.foundation.Canvas(
                    modifier = Modifier.sizeIn(minWidth = 24.dp, minHeight = 24.dp),
                ) {
                    val arrow = Path().apply {
                        moveTo(size.width * 0.2f, size.height * 0.35f)
                        lineTo(size.width * 0.8f, size.height * 0.35f)
                        lineTo(size.width * 0.5f, size.height * 0.7f)
                        close()
                    }
                    drawPath(arrow, arrowColor)
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                if (item.image == null) {
                    DropdownMenuItem(
                        text = { Text(item.text) },
                        onClick = {
                            expanded = false
                            onItemClick(item.id)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(item.text) },
                        leadingIcon = {
                            Image(
                                bitmap = item.image.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.sizeIn(maxWidth = 48.dp, maxHeight = 48.dp),
                                contentScale = ContentScale.Fit,
                            )
                        },
                        onClick = {
                            expanded = false
                            onItemClick(item.id)
                        },
                    )
                }
            }
        }
    }
}

private fun previewChoiceItems(): kotlin.collections.List<J2meChoiceItemState> = listOf(
    J2meChoiceItemState(1, "Low", null, true),
    J2meChoiceItemState(2, "Medium", null, false),
    J2meChoiceItemState(3, "High", null, false),
)

@Preview(name = "J2ME choice group", showBackground = true, widthDp = 420, heightDp = 300)
@Composable
internal fun J2meChoiceGroupPreview() {
    AppComposeTheme {
        J2meChoiceGroupContent(Choice.EXCLUSIVE, previewChoiceItems(), {})
    }
}

@Preview(
    name = "J2ME choice group dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 300,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun J2meChoiceGroupDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        J2meChoiceGroupContent(Choice.EXCLUSIVE, previewChoiceItems(), {})
    }
}

@Preview(name = "J2ME popup choice", showBackground = true, widthDp = 420, heightDp = 120)
@Composable
internal fun J2mePopupChoicePreview() {
    AppComposeTheme {
        J2meChoiceGroupContent(Choice.POPUP, previewChoiceItems(), {})
    }
}

@Preview(
    name = "J2ME popup choice dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 120,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun J2mePopupChoiceDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        J2meChoiceGroupContent(Choice.POPUP, previewChoiceItems(), {})
    }
}
