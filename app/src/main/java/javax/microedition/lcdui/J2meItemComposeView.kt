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
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

/** Compose label/container for one J2ME Item with temporary content interop. */
class J2meItemComposeView(context: Context) : FrameLayout(context) {
    private var labelState by mutableStateOf<String?>(null)
    private var contentState by mutableStateOf<View?>(null)
    private var layoutModeState by mutableStateOf(Item.LAYOUT_DEFAULT)
    private var imageItemState by mutableStateOf(false)

    init {
        addView(
            ComposeView(context).apply {
                id = generateViewId()
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
                )
                setContent {
                    AppComposeTheme {
                        J2meItemContent(
                            label = labelState,
                            contentView = contentState,
                            layoutMode = layoutModeState,
                            imageItem = imageItemState,
                        )
                    }
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun setItemContent(label: String?, contentView: View, layoutMode: Int, imageItem: Boolean) {
        labelState = label
        this.contentState = contentView
        layoutModeState = layoutMode
        imageItemState = imageItem
    }

    fun setLabel(label: String?) {
        labelState = label
    }
}

@Composable
private fun J2meItemContent(
    label: String?,
    contentView: View?,
    layoutMode: Int,
    imageItem: Boolean,
) {
    val horizontalAlignment = when (layoutMode and 3) {
        Item.LAYOUT_CENTER -> Alignment.CenterHorizontally
        Item.LAYOUT_RIGHT -> Alignment.End
        else -> Alignment.Start
    }
    val contentModifier = if (
        imageItem ||
        (layoutMode and Item.LAYOUT_SHRINK) != 0 ||
        (layoutMode and 3) != Item.LAYOUT_DEFAULT
    ) {
        Modifier.wrapContentWidth()
    } else {
        Modifier.fillMaxWidth()
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
    ) {
        label?.let {
            Text(
                text = it,
                modifier = Modifier.padding(bottom = 2.dp),
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        contentView?.let { view ->
            Box(modifier = Modifier.fillMaxWidth()) {
                AndroidView(
                    factory = { view },
                    modifier = contentModifier,
                )
            }
        }
    }
}

@Composable
private fun J2meItemPreviewContent() {
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text("Name", style = MaterialTheme.typography.bodyLarge)
            Text("Demo MIDlet", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Preview(name = "J2ME item", showBackground = true, widthDp = 420, heightDp = 120)
@Composable
internal fun J2meItemPreview() {
    AppComposeTheme {
        J2meItemPreviewContent()
    }
}

@Preview(
    name = "J2ME item dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 120,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun J2meItemDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        J2meItemPreviewContent()
    }
}
