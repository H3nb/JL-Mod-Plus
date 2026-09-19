/*
 * Modified for JL-Mod Plus.
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

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import javax.microedition.lcdui.Command
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.HostCommand
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.clearNavigationFocusOnTouch
import io.github.h3nb.jlmodplus.ui.showNavigationFocusForKey

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
    private var menuFocusIndex by mutableIntStateOf(0)
    private var menuFocusVisible by mutableStateOf(false)

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindow,
        )
        composeView.setContent {
            JLModPlusTheme {
                ScreenSoftBarContent(
                    presentation = presentation,
                    menuVisible = menuVisible,
                    menuFocusIndex = menuFocusIndex,
                    menuFocusVisible = menuFocusVisible,
                    onOpenMenu = ::openMenu,
                    onDismissMenu = ::closeMenu,
                    onNavigationFocusChanged = { menuFocusVisible = it },
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
        openMenu(showNavigationFocus = false)
    }

    private fun openMenu(showNavigationFocus: Boolean) {
        if (presentation.overflow.isNotEmpty()) {
            menuFocusIndex = 0
            menuFocusVisible = showNavigationFocus
            menuVisible = true
        }
    }

    fun closeMenu() {
        menuVisible = false
        menuFocusIndex = 0
        menuFocusVisible = false
    }

    fun isControllerMenuVisible(): Boolean = menuVisible

    fun handleHostCommand(command: HostCommand, pressed: Boolean): Boolean {
        if (!menuVisible) return false
        if (!pressed) return true
        menuFocusVisible = true
        when (command) {
            HostCommand.NavigateUp,
            HostCommand.NavigateLeft,
            HostCommand.PreviousTab,
            -> moveFocus(-1)
            HostCommand.NavigateDown,
            HostCommand.NavigateRight,
            HostCommand.NextTab,
            -> moveFocus(1)
            HostCommand.Activate -> presentation.overflow.getOrNull(menuFocusIndex)?.let {
                closeMenu()
                actions.onCommand(it)
            }
            HostCommand.Back,
            HostCommand.OpenMenu,
            HostCommand.OpenKeypad,
            -> closeMenu()
        }
        return true
    }

    fun handleControllerBack(): Boolean {
        if (menuVisible) {
            closeMenu()
            return true
        }
        val command = presentation.right
        if (command != null &&
            (command.commandType == Command.BACK || command.commandType == Command.EXIT)
        ) {
            actions.onCommand(command)
            return true
        }
        return false
    }

    private fun moveFocus(delta: Int) {
        if (presentation.overflow.isEmpty()) return
        menuFocusIndex = (menuFocusIndex + delta).mod(presentation.overflow.size)
    }
}

@Composable
internal fun ScreenSoftBarContent(
    presentation: ScreenSoftBarPresentation,
    menuVisible: Boolean,
    menuFocusIndex: Int = 0,
    menuFocusVisible: Boolean = false,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onCommand: (Command) -> Unit,
    modifier: Modifier = Modifier,
    onNavigationFocusChanged: (Boolean) -> Unit = {},
) {
    val left = presentation.left
    val middle = presentation.middle
    val right = presentation.right
    if (left == null && middle == null && right == null && presentation.overflow.isEmpty()
    ) {
        return
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clearNavigationFocusOnTouch { onNavigationFocusChanged(false) }
            .showNavigationFocusForKey { onNavigationFocusChanged(true) },
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
                    focusIndex = menuFocusIndex,
                    focusVisible = menuFocusVisible,
                    onNavigationFocusChanged = onNavigationFocusChanged,
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
    focusIndex: Int,
    focusVisible: Boolean,
    onNavigationFocusChanged: (Boolean) -> Unit,
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
            modifier = Modifier
                .clearNavigationFocusOnTouch { onNavigationFocusChanged(false) }
                .showNavigationFocusForKey { onNavigationFocusChanged(true) },
        ) {
            overflow.forEachIndexed { index, command ->
                val focused = focusVisible && index == focusIndex
                DropdownMenuItem(
                    text = {
                        Text(
                            text = command.toString(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier
                        .then(
                            if (focused) {
                                Modifier
                                    .testTag("screen-controller-focus-indicator")
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                                        MaterialTheme.shapes.small,
                                    )
                            } else {
                                Modifier
                            },
                        ),
                    onClick = {
                        onNavigationFocusChanged(false)
                        onCommand(command)
                    },
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
