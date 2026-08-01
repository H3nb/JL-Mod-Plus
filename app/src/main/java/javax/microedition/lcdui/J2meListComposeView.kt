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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import javax.microedition.lcdui.list.CompoundItem

/** Compose-backed rendering for one J2ME List displayable. */
class J2meListComposeView(
    context: android.content.Context,
    private val listType: Int,
    private val onItemClick: ItemClickCallback,
    private val onItemFocused: ItemFocusCallback,
    private val onItemLongClick: ItemLongClickCallback,
) : FrameLayout(context) {
    fun interface ItemClickCallback {
        fun onItemClick(position: Int)
    }

    fun interface ItemFocusCallback {
        fun onItemFocused(position: Int)
    }

    fun interface ItemLongClickCallback {
        fun onItemLongClick(position: Int): Boolean
    }

    private val composeView = ComposeView(context)
    private var itemState by mutableStateOf<kotlin.collections.List<J2meListItemState>>(emptyList())
    private var selectionRequest by mutableIntStateOf(-1)

    init {
        composeView.id = generateViewId()
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        composeView.setContent {
            AppComposeTheme {
                J2meListContent(
                    listType = listType,
                    items = itemState,
                    selectionRequest = selectionRequest,
                    onItemClick = onItemClick::onItemClick,
                    onItemFocused = onItemFocused::onItemFocused,
                    onItemLongClick = onItemLongClick::onItemLongClick,
                )
            }
        }
        addView(
            composeView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setItems(items: java.util.List<CompoundItem>) {
        itemState = items.mapIndexed { index, item ->
            J2meListItemState(
                id = (System.identityHashCode(item).toLong() shl 32) xor index.toLong(),
                text = item.string,
                image = item.image?.bitmap,
                selected = item.isSelected,
            )
        }
    }

    fun requestSelection(index: Int) {
        selectionRequest = index
    }
}

private data class J2meListItemState(
    val id: Long,
    val text: String,
    val image: Bitmap?,
    val selected: Boolean,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun J2meListContent(
    listType: Int,
    items: kotlin.collections.List<J2meListItemState>,
    selectionRequest: Int,
    onItemClick: (Int) -> Unit,
    onItemFocused: (Int) -> Unit,
    onItemLongClick: (Int) -> Boolean,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectionRequest, items.size) {
        if (selectionRequest in items.indices) {
            listState.scrollToItem(selectionRequest)
        }
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> item.id },
            ) { index, item ->
                J2meListRow(
                    item = item,
                    listType = listType,
                    onClick = { onItemClick(index) },
                    onFocus = if (listType == Choice.IMPLICIT) {
                        { onItemFocused(index) }
                    } else {
                        null
                    },
                    onLongClick = if (listType == Choice.IMPLICIT) {
                        { onItemLongClick(index); Unit }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun J2meListRow(
    item: J2meListItemState,
    listType: Int,
    onClick: () -> Unit,
    onFocus: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    val selectedBackground = if (item.selected && listType == Choice.IMPLICIT) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.background
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(selectedBackground)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    onFocus?.invoke()
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (listType) {
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

@Preview(name = "J2ME list", showBackground = true, widthDp = 420, heightDp = 420)
@Composable
internal fun J2meListPreview() {
    AppComposeTheme {
        J2meListPreviewSurface()
    }
}

@Preview(
    name = "J2ME list dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 420,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun J2meListDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        J2meListPreviewSurface()
    }
}

@Composable
private fun J2meListPreviewSurface() {
    val items = listOf(
        J2meListItemState(1, "First MIDlet", null, true),
        J2meListItemState(2, "Second MIDlet", null, false),
        J2meListItemState(3, "Third MIDlet", null, false),
    )
    J2meListContent(
        listType = Choice.IMPLICIT,
        items = items,
        selectionRequest = -1,
        onItemClick = {},
        onItemFocused = {},
        onItemLongClick = { false },
    )
}
