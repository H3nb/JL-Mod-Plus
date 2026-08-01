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

package javax.microedition.lcdui.commands

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
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
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import io.github.h3nb.jlmodplus.ui.ComposeViewTreeOwners
import javax.microedition.lcdui.Command

class SoftMenuComposeView(
    context: Context,
    private val callback: Callback,
) : FrameLayout(context) {
    fun interface Callback {
        fun onCommand(command: Command)
    }

    private var commandsState by mutableStateOf<List<Command>>(emptyList())

    init {
        ComposeViewTreeOwners.install(this, context)
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
                )
                setContent {
                    AppComposeTheme {
                        SoftMenuContent(
                            commands = commandsState,
                            onCommand = callback::onCommand,
                        )
                    }
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun setCommands(commands: List<Command>) {
        commandsState = commands.toList()
    }

    fun clearCommands() {
        commandsState = emptyList()
    }
}

@Composable
internal fun SoftMenuContent(
    commands: List<Command>,
    onCommand: (Command) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            commands.forEach { command ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = command.androidLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = { onCommand(command) },
                )
            }
        }
    }
}

@Preview(name = "Soft menu", showBackground = true, widthDp = 240, heightDp = 280)
@Composable
internal fun SoftMenuPreview() {
    AppComposeTheme {
        SoftMenuContent(
            commands = previewSoftMenuCommands(),
            onCommand = {},
        )
    }
}

@Preview(
    name = "Soft menu dark",
    showBackground = true,
    widthDp = 240,
    heightDp = 280,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun SoftMenuDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        SoftMenuContent(
            commands = previewSoftMenuCommands(),
            onCommand = {},
        )
    }
}

private fun previewSoftMenuCommands(): List<Command> = listOf(
    Command("Select", Command.OK, 1),
    Command("Back", Command.BACK, 2),
    Command("Help", Command.HELP, 3),
    Command("Exit", Command.EXIT, 4),
)
