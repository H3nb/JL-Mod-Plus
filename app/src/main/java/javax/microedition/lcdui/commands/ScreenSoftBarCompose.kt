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

package javax.microedition.lcdui.commands

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import javax.microedition.lcdui.Command
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

interface ScreenSoftBarActions {
    fun onCommand(command: Command)
}

/** Visual-only controller. Command ordering and dispatch remain in ScreenSoftBar/Displayable. */
internal class ScreenSoftBarComposeController(
    private val composeView: ComposeView,
    private val actions: ScreenSoftBarActions,
) {
    private var presentation by mutableStateOf(ScreenSoftBarPresentation())
    private var menuVisible by mutableStateOf(false)

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        composeView.setContent {
            JLModPlusTheme {
                ScreenSoftBarContent(
                    presentation = presentation,
                    menuVisible = menuVisible,
                    onOpenMenu = ::openMenu,
                    onDismissMenu = ::closeMenu,
                    onCommand = { command ->
                        closeMenu()
                        actions.onCommand(command)
                    },
                )
            }
        }
    }

    fun update(nextPresentation: ScreenSoftBarPresentation) {
        closeMenu()
        presentation = nextPresentation
        val hasCommands = nextPresentation.left != null ||
            nextPresentation.middle != null ||
            nextPresentation.right != null ||
            nextPresentation.overflow.isNotEmpty()
        composeView.visibility = if (hasCommands) View.VISIBLE else View.GONE
    }

    fun openMenu() {
        if (presentation.overflow.isNotEmpty()) {
            menuVisible = true
        }
    }

    fun closeMenu() {
        menuVisible = false
    }
}

@Composable
internal fun ScreenSoftBarContent(
    presentation: ScreenSoftBarPresentation,
    menuVisible: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onCommand: (Command) -> Unit,
    modifier: Modifier = Modifier,
) {
    val left = presentation.left
    val middle = presentation.middle
    val right = presentation.right
    if (left == null && middle == null && right == null && presentation.overflow.isEmpty()
    ) {
        return
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (left != null) {
                SoftKeyButton(
                    label = left.androidLabel,
                    alignment = Alignment.CenterStart,
                    onClick = { onCommand(left) },
                )
            } else if (presentation.overflow.isNotEmpty()) {
                SoftKeyMenuButton(
                    menuVisible = menuVisible,
                    onOpenMenu = onOpenMenu,
                    onDismissMenu = onDismissMenu,
                    onCommand = onCommand,
                    overflow = presentation.overflow,
                )
            } else {
                EmptySoftKey()
            }
            if (middle != null) {
                SoftKeyButton(
                    label = middle.androidLabel,
                    alignment = Alignment.Center,
                    onClick = { onCommand(middle) },
                )
            } else {
                EmptySoftKey()
            }
            if (right != null) {
                SoftKeyButton(
                    label = right.androidLabel,
                    alignment = Alignment.CenterEnd,
                    onClick = { onCommand(right) },
                )
            } else {
                EmptySoftKey()
            }
        }
    }
}

@Composable
private fun RowScope.SoftKeyMenuButton(
    menuVisible: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onCommand: (Command) -> Unit,
    overflow: List<Command>,
) {
    Box(modifier = Modifier.weight(1f)) {
        TextButton(
            onClick = onOpenMenu,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Text(
                    text = stringResource(R.string.cmd_menu),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DropdownMenu(
            expanded = menuVisible,
            onDismissRequest = onDismissMenu,
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            overflow.forEach { command ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = command.toString(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = { onCommand(command) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.SoftKeyButton(
    label: String,
    alignment: Alignment,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowScope.EmptySoftKey() {
    Box(modifier = Modifier.weight(1f))
}
