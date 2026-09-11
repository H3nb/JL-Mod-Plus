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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout
import io.github.h3nb.jlmodplus.ui.rememberScrollCanScrollForward

interface ConfigErrorActions {
    fun onExit()
}

object ConfigErrorComposeBridge {
    @JvmStatic
    fun install(composeView: ComposeView, message: String, actions: ConfigErrorActions) {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                MissingAppDialog(message = message, onExit = actions::onExit)
            }
        }
    }
}

@Composable
private fun MissingAppDialog(
    message: String,
    onExit: () -> Unit,
) {
    val maxMessageHeight = adaptiveDialogLayout().maxHeight
    val scrollState = rememberScrollState()
    val canScrollForward = rememberScrollCanScrollForward(scrollState)
    AlertDialog(
        textScrollable = false,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        onDismissRequest = {},
        title = { Text(stringResource(R.string.error)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxMessageHeight),
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxMessageHeight)
                        .verticalScroll(scrollState),
                )
                ScrollableContentHint(
                    visible = canScrollForward,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onExit) {
                Text(stringResource(R.string.exit))
            }
        },
    )
}
