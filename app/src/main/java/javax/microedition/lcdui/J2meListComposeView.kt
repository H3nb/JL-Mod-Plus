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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import javax.microedition.lcdui.list.CompoundItem
import kotlinx.coroutines.flow.first

/** Compose-backed rendering for one J2ME List displayable. */
class J2meListComposeView(
    context: android.content.Context,
    private val listType: Int,
    private val onItemClick: ItemClickCallback,
    private val onItemFocused: ItemFocusCallback,
    private val onItemLongClick: ItemLongClickCallback,
) : FrameLayout(context) {
    fun interface ItemClickCallback {
        fun onItemClick(itemId: Long)
    }

    fun interface ItemFocusCallback {
        fun onItemFocused(itemId: Long)
    }

    fun interface ItemLongClickCallback {
        fun onItemLongClick(itemId: Long): Boolean
    }

    private val composeView = ComposeView(context)
    private var itemState by mutableStateOf<kotlin.collections.List<J2meListItemState>>(emptyList())
    private var selectionRequest by mutableStateOf(SelectionRequest(-1, 0))

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
        itemState = items.map { item ->
            J2meListItemState(
                id = item.getUiId(),
                text = item.string,
                image = item.image?.bitmap,
                selected = item.isSelected,
            )
        }
    }

    fun requestSelection(index: Int) {
        val target = itemState.getOrNull(index)?.id
        selectionRequest = SelectionRequest(
            target = target,
            generation = selectionRequest.generation + 1,
        )
    }
}

/**
 * A selection request carrying the stable identity of the item and a generation
 * token. Each generation is consumed exactly once by the renderer; ordinary
 * structural list changes must not re-run an already processed request.
 */
private data class SelectionRequest(
    val target: Long?,
    val generation: Long,
)

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
    selectionRequest: SelectionRequest,
    onItemClick: (Long) -> Unit,
    onItemFocused: (Long) -> Unit,
    onItemLongClick: (Long) -> Boolean,
) {
    val listState = rememberLazyListState()
    val itemKeys = items.map { it.id }
    val focusRequesters = remember(itemKeys) {
        itemKeys.associateWith { FocusRequester() }
    }
    var consumedSelectionGeneration by remember { mutableStateOf(0L) }
    LaunchedEffect(selectionRequest.generation) {
        if (selectionRequest.generation <= consumedSelectionGeneration) {
            return@LaunchedEffect
        }
        consumedSelectionGeneration = selectionRequest.generation
        val target = selectionRequest.target ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.id == target }
        if (index < 0) {
            return@LaunchedEffect
        }
        listState.scrollToItem(index)
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.any { it.key == target }
        }.first { visible ->
            visible || items.none { it.id == target }
        }
        if (items.any { it.id == target }) {
            focusRequesters[target]?.requestFocus()
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
                    focusRequester = focusRequesters.getValue(item.id),
                    onClick = { onItemClick(item.id) },
                    onFocus = if (listType == Choice.IMPLICIT) {
                        { onItemFocused(item.id) }
                    } else {
                        null
                    },
                    onLongClick = if (listType == Choice.IMPLICIT) {
                        { onItemLongClick(item.id); Unit }
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
    focusRequester: FocusRequester,
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
            .focusRequester(focusRequester)
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
        selectionRequest = SelectionRequest(null, 0),
        onItemClick = {},
        onItemFocused = {},
        onItemLongClick = { false },
    )
}
