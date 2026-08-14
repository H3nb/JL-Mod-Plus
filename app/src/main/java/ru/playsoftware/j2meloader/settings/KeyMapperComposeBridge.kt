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

package ru.playsoftware.j2meloader.settings

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.keyboard.KeyMapper
import kotlin.math.roundToInt

data class KeyMapperButton(
    val labelRes: Int,
    val canvasKey: Int,
)

data class KeyMapperMappingDialog(
    val canvasKey: Int,
    val currentKeyName: String,
)

data class KeyMapperUiState(
    val mappingDialog: KeyMapperMappingDialog? = null,
)

interface KeyMapperActions {
    fun onVirtualKey(canvasKey: Int)
    fun onDismissMapping()
}

class KeyMapperComposeController(
    composeView: ComposeView,
    private val actions: KeyMapperActions,
) {
    private var state by mutableStateOf(KeyMapperUiState())
    private var popupBounds: Rect? = null

    init {
        composeView.id = R.id.key_mapper_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                KeyMapperScreen(
                    state = state,
                    actions = actions,
                    onPopupBoundsChanged = { popupBounds = it },
                )
            }
        }
    }

    fun showMappingDialog(canvasKey: Int, currentKeyName: String) {
        state = KeyMapperUiState(KeyMapperMappingDialog(canvasKey, currentKeyName))
    }

    fun hideMappingDialog() {
        state = KeyMapperUiState()
        popupBounds = null
    }

    fun isMappingDialogVisible(): Boolean = state.mappingDialog != null

    fun getPopupBounds(): Rect? = popupBounds
}

internal fun keyMapperButtons(): List<KeyMapperButton> = listOf(
    KeyMapperButton(R.string.virtual_key_a, KeyMapper.SE_KEY_SPECIAL_GAMING_A),
    KeyMapperButton(R.string.virtual_key_menu, KeyMapper.KEY_OPTIONS_MENU),
    KeyMapperButton(R.string.virtual_key_b, KeyMapper.SE_KEY_SPECIAL_GAMING_B),
    KeyMapperButton(R.string.virtual_key_left_soft, Canvas.KEY_SOFT_LEFT),
    KeyMapperButton(R.string.virtual_key_up, Canvas.KEY_UP),
    KeyMapperButton(R.string.virtual_key_right_soft, Canvas.KEY_SOFT_RIGHT),
    KeyMapperButton(R.string.virtual_key_left, Canvas.KEY_LEFT),
    KeyMapperButton(R.string.virtual_key_f, Canvas.KEY_FIRE),
    KeyMapperButton(R.string.virtual_key_right, Canvas.KEY_RIGHT),
    KeyMapperButton(R.string.virtual_key_d, Canvas.KEY_SEND),
    KeyMapperButton(R.string.virtual_key_down, Canvas.KEY_DOWN),
    KeyMapperButton(R.string.virtual_key_c, Canvas.KEY_END),
    KeyMapperButton(R.string.virtual_key_1, Canvas.KEY_NUM1),
    KeyMapperButton(R.string.virtual_key_2, Canvas.KEY_NUM2),
    KeyMapperButton(R.string.virtual_key_3, Canvas.KEY_NUM3),
    KeyMapperButton(R.string.virtual_key_4, Canvas.KEY_NUM4),
    KeyMapperButton(R.string.virtual_key_5, Canvas.KEY_NUM5),
    KeyMapperButton(R.string.virtual_key_6, Canvas.KEY_NUM6),
    KeyMapperButton(R.string.virtual_key_7, Canvas.KEY_NUM7),
    KeyMapperButton(R.string.virtual_key_8, Canvas.KEY_NUM8),
    KeyMapperButton(R.string.virtual_key_9, Canvas.KEY_NUM9),
    KeyMapperButton(R.string.virtual_key_star, Canvas.KEY_STAR),
    KeyMapperButton(R.string.virtual_key_0, Canvas.KEY_NUM0),
    KeyMapperButton(R.string.virtual_key_pound, Canvas.KEY_POUND),
)

@Composable
fun KeyMapperScreen(
    state: KeyMapperUiState,
    actions: KeyMapperActions,
    modifier: Modifier = Modifier,
    onPopupBoundsChanged: (Rect) -> Unit = {},
) {
    val buttons = keyMapperButtons()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyRow(buttons.subList(0, 3), actions)
            Spacer(Modifier.height(12.dp))
            KeyRow(buttons.subList(3, 6), actions)
            KeyRow(buttons.subList(6, 9), actions)
            KeyRow(buttons.subList(9, 12), actions)
            Spacer(Modifier.height(8.dp))
            KeyRow(buttons.subList(12, 15), actions)
            KeyRow(buttons.subList(15, 18), actions)
            KeyRow(buttons.subList(18, 21), actions)
            KeyRow(buttons.subList(21, 24), actions)
        }

        state.mappingDialog?.let { dialog ->
            MappingOverlay(
                dialog = dialog,
                actions = actions,
                onPopupBoundsChanged = onPopupBoundsChanged,
            )
        }
    }
}

@Composable
private fun KeyRow(
    buttons: List<KeyMapperButton>,
    actions: KeyMapperActions,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        buttons.forEach { button ->
            Button(
                onClick = { actions.onVirtualKey(button.canvasKey) },
                modifier = Modifier.size(width = 72.dp, height = 52.dp),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    text = stringResource(button.labelRes),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun MappingOverlay(
    dialog: KeyMapperMappingDialog,
    actions: KeyMapperActions,
    onPopupBoundsChanged: (Rect) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
            .semantics { contentDescription = "Dismiss mapping" }
            .clickable(onClick = actions::onDismissMapping),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(min = 240.dp, max = 320.dp)
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    val size: IntSize = coordinates.size
                    onPopupBoundsChanged(
                        Rect(
                            position.x.roundToInt(),
                            position.y.roundToInt(),
                            position.x.roundToInt() + size.width,
                            position.y.roundToInt() + size.height,
                        ),
                    )
                }
                .clickable(enabled = false) {},
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.mapping_dialog_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(
                        R.string.mapping_dialog_message,
                        dialog.currentKeyName,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
