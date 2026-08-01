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

package io.github.h3nb.jlmodplus.settings

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.keyboard.KeyMapper

class KeyMapperComposeView(
    context: Context,
    private val callback: Callback,
) : FrameLayout(context) {
    interface Callback {
        fun onBack()
        fun onResetMapping()
        fun onKeyClick(canvasKey: Int)
    }

    private var mappingVisible by mutableStateOf(false)
    private var mappingMessage by mutableStateOf("")

    init {
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    AppComposeTheme {
                        KeyMapperContent(
                            mappingVisible = mappingVisible,
                            mappingMessage = mappingMessage,
                            onBack = callback::onBack,
                            onResetMapping = callback::onResetMapping,
                            onKeyClick = callback::onKeyClick,
                            onDismissMapping = ::hideMappingDialog,
                        )
                    }
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun showMappingDialog(message: String) {
        mappingMessage = message
        mappingVisible = true
    }

    fun hideMappingDialog() {
        mappingVisible = false
    }

    fun isMappingDialogVisible(): Boolean = mappingVisible
}

private data class MappingButton(val label: Int, val key: Int)

private val mappingRows = listOf(
    listOf(
        MappingButton(R.string.virtual_key_a, KeyMapper.SE_KEY_SPECIAL_GAMING_A),
        MappingButton(R.string.virtual_key_menu, KeyMapper.KEY_OPTIONS_MENU),
        MappingButton(R.string.virtual_key_b, KeyMapper.SE_KEY_SPECIAL_GAMING_B),
    ),
    listOf(
        MappingButton(R.string.virtual_key_left_soft, Canvas.KEY_SOFT_LEFT),
        MappingButton(R.string.virtual_key_up, Canvas.KEY_UP),
        MappingButton(R.string.virtual_key_right_soft, Canvas.KEY_SOFT_RIGHT),
    ),
    listOf(
        MappingButton(R.string.virtual_key_left, Canvas.KEY_LEFT),
        MappingButton(R.string.virtual_key_f, Canvas.KEY_FIRE),
        MappingButton(R.string.virtual_key_right, Canvas.KEY_RIGHT),
    ),
    listOf(
        MappingButton(R.string.virtual_key_d, Canvas.KEY_SEND),
        MappingButton(R.string.virtual_key_down, Canvas.KEY_DOWN),
        MappingButton(R.string.virtual_key_c, Canvas.KEY_END),
    ),
    listOf(
        MappingButton(R.string.virtual_key_1, Canvas.KEY_NUM1),
        MappingButton(R.string.virtual_key_2, Canvas.KEY_NUM2),
        MappingButton(R.string.virtual_key_3, Canvas.KEY_NUM3),
    ),
    listOf(
        MappingButton(R.string.virtual_key_4, Canvas.KEY_NUM4),
        MappingButton(R.string.virtual_key_5, Canvas.KEY_NUM5),
        MappingButton(R.string.virtual_key_6, Canvas.KEY_NUM6),
    ),
    listOf(
        MappingButton(R.string.virtual_key_7, Canvas.KEY_NUM7),
        MappingButton(R.string.virtual_key_8, Canvas.KEY_NUM8),
        MappingButton(R.string.virtual_key_9, Canvas.KEY_NUM9),
    ),
    listOf(
        MappingButton(R.string.virtual_key_star, Canvas.KEY_STAR),
        MappingButton(R.string.virtual_key_0, Canvas.KEY_NUM0),
        MappingButton(R.string.virtual_key_pound, Canvas.KEY_POUND),
    ),
)

@Composable
private fun KeyMapperContent(
    mappingVisible: Boolean,
    mappingMessage: String,
    onBack: () -> Unit,
    onResetMapping: () -> Unit,
    onKeyClick: (Int) -> Unit,
    onDismissMapping: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                KeyMapperTopBar(onBack = onBack, onResetMapping = onResetMapping)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    mappingRows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.Center) {
                            row.forEach { button ->
                                Button(
                                    onClick = { onKeyClick(button.key) },
                                    modifier = Modifier.padding(2.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        text = stringResource(button.label),
                                        fontSize = 22.sp,
                                        fontFamily = FontFamily.Default,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (mappingVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.56f))
                    .clickable(onClick = onDismissMapping),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.clickable(enabled = true, onClick = {}),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = stringResource(R.string.mapping_dialog_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(text = mappingMessage, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyMapperTopBar(onBack: () -> Unit, onResetMapping: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text("←", fontSize = 32.sp, lineHeight = 32.sp)
            }
            Text(
                text = stringResource(R.string.pref_map_keys),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(onClick = onResetMapping) {
                Text(stringResource(R.string.reset_mapping))
            }
        }
    }
}

@Preview(name = "Key mapper", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun KeyMapperPreview() {
    AppComposeTheme {
        KeyMapperContent(
            mappingVisible = false,
            mappingMessage = "",
            onBack = {},
            onResetMapping = {},
            onKeyClick = {},
            onDismissMapping = {},
                )
            }
        }

@Preview(
    name = "Key mapper dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun KeyMapperDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        KeyMapperContent(
            mappingVisible = false,
            mappingMessage = "",
            onBack = {},
            onResetMapping = {},
            onKeyClick = {},
            onDismissMapping = {},
        )
    }
}

@Preview(name = "Key mapping prompt", showBackground = true, widthDp = 420, heightDp = 760)
@Composable
internal fun KeyMapperDialogPreview() {
    AppComposeTheme {
        KeyMapperContent(
            mappingVisible = true,
            mappingMessage = stringResource(
                R.string.mapping_dialog_message,
                stringResource(R.string.virtual_key_up),
            ),
            onBack = {},
            onResetMapping = {},
            onKeyClick = {},
            onDismissMapping = {},
        )
    }
}

@Preview(
    name = "Key mapping prompt dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 760,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun KeyMapperDialogDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        KeyMapperContent(
            mappingVisible = true,
            mappingMessage = stringResource(
                R.string.mapping_dialog_message,
                stringResource(R.string.virtual_key_up),
            ),
            onBack = {},
            onResetMapping = {},
            onKeyClick = {},
            onDismissMapping = {},
        )
    }
}
