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

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import javax.microedition.lcdui.Command

@SuppressLint("ViewConstructor") // Programmatic J2ME/Compose host owns callback wiring; no XML inflation path.
class ScreenSoftBarComposeView(
    context: Context,
    private val callback: Callback,
) : FrameLayout(context) {
    fun interface Callback {
        fun onCommand(command: Command?)
    }

    private var commandsState by mutableStateOf<List<Command>>(emptyList())

    init {
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
                setContent {
                    AppComposeTheme {
                        SoftBarContent(commands = commandsState, onCommand = callback::onCommand)
                    }
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun setCommands(commands: List<Command>) {
        commandsState = commands.toList()
    }
}

internal sealed interface SoftBarSlot {
    data class Item(val command: Command) : SoftBarSlot
    data object Menu : SoftBarSlot
    data object Empty : SoftBarSlot
}

internal fun buildSoftBarSlots(commands: List<Command>): List<SoftBarSlot> {
    return when {
        commands.isEmpty() -> emptyList()
        commands.size == 1 -> listOf(SoftBarSlot.Item(commands[0]), SoftBarSlot.Empty, SoftBarSlot.Empty)
        commands.size == 2 -> listOf(SoftBarSlot.Item(commands[0]), SoftBarSlot.Empty, SoftBarSlot.Item(commands[1]))
        commands.size == 3 -> commands.map { SoftBarSlot.Item(it) }
        else -> listOf(SoftBarSlot.Item(commands[0]), SoftBarSlot.Item(commands[1]), SoftBarSlot.Menu)
    }
}

@Composable
private fun SoftBarContent(commands: List<Command>, onCommand: (Command?) -> Unit) {
    val slots = buildSoftBarSlots(commands)
    if (slots.isNotEmpty()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                slots.forEach { slot ->
                    val buttonModifier = Modifier.weight(1f).padding(horizontal = 1.dp)
                    when (slot) {
                        is SoftBarSlot.Item -> Button(
                            onClick = { onCommand(slot.command) },
                            modifier = buttonModifier,
                        ) {
                            Text(slot.command.androidLabel)
                        }
                        SoftBarSlot.Menu -> Button(
                            onClick = { onCommand(null) },
                            modifier = buttonModifier,
                        ) {
                            Text(stringResource(R.string.cmd_menu))
                        }
                        SoftBarSlot.Empty -> Spacer(modifier = buttonModifier)
                    }
                }
            }
        }
    }
}

@Preview(name = "Soft bar", showBackground = true, widthDp = 420)
@Composable
internal fun SoftBarPreview() {
    AppComposeTheme {
        SoftBarContent(
            commands = listOf(
                Command("Options", Command.SCREEN, 1),
                Command("Back", Command.BACK, 2),
            ),
            onCommand = {},
        )
    }
}

@Preview(
    name = "Soft bar dark",
    showBackground = true,
    widthDp = 420,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun SoftBarDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        SoftBarContent(
            commands = listOf(
                Command("Options", Command.SCREEN, 1),
                Command("Back", Command.BACK, 2),
            ),
            onCommand = {},
        )
    }
}

@Preview(name = "Soft bar menu", showBackground = true, widthDp = 420)
@Composable
internal fun SoftBarMenuPreview() {
    AppComposeTheme {
        SoftBarContent(
            commands = listOf(
                Command("Select", Command.OK, 1),
                Command("Back", Command.BACK, 2),
                Command("Help", Command.HELP, 3),
                Command("Exit", Command.EXIT, 4),
            ),
            onCommand = {},
        )
    }
}

@Preview(
    name = "Soft bar menu dark",
    showBackground = true,
    widthDp = 420,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun SoftBarMenuDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        SoftBarContent(
            commands = listOf(
                Command("Select", Command.OK, 1),
                Command("Back", Command.BACK, 2),
                Command("Help", Command.HELP, 3),
                Command("Exit", Command.EXIT, 4),
            ),
            onCommand = {},
        )
    }
}
