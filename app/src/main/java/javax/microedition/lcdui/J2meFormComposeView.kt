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

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

/** Compose-backed scroll shell for a J2ME Form. */
class J2meFormComposeView(context: Context) : FrameLayout(context) {
    private var itemState by mutableStateOf<kotlin.collections.List<J2meFormItemState>>(emptyList())

    init {
        addView(
            ComposeView(context).apply {
                id = generateViewId()
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
                )
                setContent {
                    AppComposeTheme {
                        J2meFormContent(itemState)
                    }
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun setItems(items: kotlin.collections.List<Item>) {
        itemState = items.map { item ->
            J2meFormItemState(
                id = System.identityHashCode(item).toLong(),
                item = item,
            )
        }
    }
}

private data class J2meFormItemState(
    val id: Long,
    val item: Item,
)

@Composable
private fun J2meFormContent(items: kotlin.collections.List<J2meFormItemState>) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(7.dp),
        ) {
            items(
                items = items,
                key = { it.id },
                contentType = { "j2me-form-item" },
            ) { state ->
                AndroidView(
                    factory = { state.item.getItemView() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Preview(name = "J2ME form", showBackground = true, widthDp = 420, heightDp = 520)
@Composable
internal fun J2meFormPreview() {
    AppComposeTheme {
        J2meFormPreviewContent()
    }
}

@Preview(
    name = "J2ME form dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 520,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun J2meFormDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        J2meFormPreviewContent()
    }
}

@Composable
private fun J2meFormPreviewContent() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(7.dp),
        ) {
            item {
                Text(
                    text = "Name\nDemo MIDlet",
                    modifier = Modifier.padding(12.dp),
                )
            }
            item {
                Text(
                    text = "Version\n1.0",
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
