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

package io.github.h3nb.jlmodplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusable
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.input.HostCommand
import android.view.KeyEvent as AndroidKeyEvent

private val DialogMaximumWidth = 720.dp
private val DialogHorizontalMargin = 24.dp
private val DialogCompactHorizontalMargin = 16.dp

internal typealias ControllerHostCommandHandler = (HostCommand, Boolean) -> Boolean

private val LocalControllerDialogKeyEvent =
    compositionLocalOf<((android.view.KeyEvent) -> Boolean)?> { null }
private val LocalControllerHostCommandHandlerChanged =
    compositionLocalOf<((ControllerHostCommandHandler?) -> Unit)?> { null }

@Composable
internal fun ControllerDialogInputScope(
    onControllerKeyEvent: (android.view.KeyEvent) -> Boolean,
    onControllerHostCommandHandlerChanged: (ControllerHostCommandHandler?) -> Unit,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalControllerDialogKeyEvent provides onControllerKeyEvent,
        LocalControllerHostCommandHandlerChanged provides onControllerHostCommandHandlerChanged,
        content = content,
    )
}

/** Shared modal bounds: one width policy, content-wrapped height, and a safe maximum height. */
@Immutable
internal data class AdaptiveDialogLayout(
    val width: Dp,
    val maxHeight: Dp,
) {
    val modifier: Modifier
        get() = Modifier
            .width(width)
            .heightIn(max = maxHeight)

    val properties: DialogProperties
        get() = DialogProperties(usePlatformDefaultWidth = false)
}

@Composable
internal fun adaptiveDialogLayout(): AdaptiveDialogLayout {
    return adaptiveDialogLayout(
        availableWidth = availableWindowWidthDp(),
        availableHeight = availableWindowHeightDp(),
    )
}

/** Same policy for Compose hosted inside an already bounded platform dialog window. */
internal fun adaptiveDialogLayout(
    availableWidth: Dp,
    availableHeight: Dp,
    maxDialogWidth: Dp = DialogMaximumWidth,
): AdaptiveDialogLayout {
    val compactWindow = availableWidth < 600.dp || availableHeight < 480.dp
    val horizontalMargin = if (compactWindow) {
        DialogCompactHorizontalMargin
    } else {
        DialogHorizontalMargin
    }
    return AdaptiveDialogLayout(
        width = (availableWidth - horizontalMargin * 2)
            .coerceAtLeast(0.dp)
            .coerceAtMost(maxDialogWidth),
        // Keep the maximum-height gap visually consistent with the width gap. Long content may
        // use the whole safe window inside this margin before its body becomes scrollable.
        maxHeight = (availableHeight - horizontalMargin * 2).coerceAtLeast(0.dp),
    )
}

/** Material 3 modal whose body gets the space left by its measured title and actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    onControllerKeyEvent: ((android.view.KeyEvent) -> Boolean)? = null,
    onControllerHostCommandHandlerChanged: ((ControllerHostCommandHandler?) -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    dismissButtonBelowWrappedActions: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    textScrollable: Boolean = true,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties(),
    maxWidth: Dp = DialogMaximumWidth,
) {
    val effectiveControllerKeyEvent = onControllerKeyEvent ?: LocalControllerDialogKeyEvent.current
    val effectiveHostCommandHandlerChanged =
        onControllerHostCommandHandlerChanged ?: LocalControllerHostCommandHandlerChanged.current
    val layout = adaptiveDialogLayout(
        availableWidth = availableWindowWidthDp(),
        availableHeight = availableWindowHeightDp(),
        maxDialogWidth = maxWidth,
    )
    val compact = layout.maxHeight < 320.dp
    var actionsWrapped by remember { mutableStateOf(false) }
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = layout.modifier.then(modifier),
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        ),
    ) {
        val focusManager = LocalFocusManager.current
        val dialogView = LocalView.current
        val controllerFocusRequester = if (effectiveControllerKeyEvent != null) {
            remember { FocusRequester() }
        } else {
            null
        }
        val controllerHostCommandHandler: ControllerHostCommandHandler? =
            if (effectiveHostCommandHandlerChanged != null) {
                remember(focusManager, dialogView, onDismissRequest) {
                    { command: HostCommand, pressed: Boolean ->
                        when (command) {
                            HostCommand.NavigateUp -> {
                                if (pressed) focusManager.moveFocus(FocusDirection.Up)
                                true
                            }
                            HostCommand.NavigateDown -> {
                                if (pressed) focusManager.moveFocus(FocusDirection.Down)
                                true
                            }
                            HostCommand.NavigateLeft -> {
                                if (pressed) focusManager.moveFocus(FocusDirection.Left)
                                true
                            }
                            HostCommand.NavigateRight -> {
                                if (pressed) focusManager.moveFocus(FocusDirection.Right)
                                true
                            }
                            HostCommand.PreviousTab -> {
                                if (pressed) focusManager.moveFocus(FocusDirection.Previous)
                                true
                            }
                            HostCommand.NextTab -> {
                                if (pressed) focusManager.moveFocus(FocusDirection.Next)
                                true
                            }
                            HostCommand.Activate -> {
                                // Reuse the focused Compose control's own click/toggle semantics.
                                dialogView.dispatchKeyEvent(
                                    AndroidKeyEvent(
                                        if (pressed) AndroidKeyEvent.ACTION_DOWN else AndroidKeyEvent.ACTION_UP,
                                        AndroidKeyEvent.KEYCODE_ENTER,
                                    ),
                                )
                                true
                            }
                            HostCommand.Back,
                            HostCommand.OpenMenu,
                            HostCommand.OpenKeypad,
                            -> {
                                if (pressed) onDismissRequest()
                                true
                            }
                        }
                    }
                }
            } else {
                null
            }
        DisposableEffect(effectiveHostCommandHandlerChanged, controllerHostCommandHandler) {
            effectiveHostCommandHandlerChanged?.invoke(controllerHostCommandHandler)
            onDispose {
                effectiveHostCommandHandlerChanged?.invoke(null)
            }
        }
        LaunchedEffect(controllerFocusRequester) {
            if (controllerFocusRequester != null) {
                controllerFocusRequester.requestFocus()
                focusManager.moveFocus(FocusDirection.Next)
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adaptive-dialog-surface")
                .then(
                    if (controllerFocusRequester != null) {
                        Modifier
                            .focusRequester(controllerFocusRequester)
                            .focusable()
                            .onPreviewKeyEvent { event ->
                                val native = event.nativeKeyEvent
                                if (native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                                ) {
                                    // Enter belongs to the focused Compose control and synthetic
                                    // Activate must not recurse through the controller router.
                                    false
                                } else {
                                    effectiveControllerKeyEvent?.invoke(native) == true
                                }
                            }
                    } else {
                        Modifier
                    },
                ),
            shape = shape,
            color = containerColor,
            contentColor = textContentColor,
            tonalElevation = tonalElevation,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = if (compact) 12.dp else 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
            ) {
                if (icon != null) {
                    Box(Modifier.align(Alignment.CenterHorizontally)) {
                        CompositionLocalProvider(LocalContentColor provides iconContentColor) { icon() }
                    }
                }
                if (title != null) {
                    CompositionLocalProvider(LocalContentColor provides titleContentColor) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                    }
                }
                if (text != null) {
                    // Measure the title/footer first; no guessed fixed-height reservations.
                    Box(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Start)) {
                            if (textScrollable ||
                                (dismissButtonBelowWrappedActions && actionsWrapped)
                            ) {
                                val scrollState = rememberScrollState()
                                val canScrollForward = rememberScrollCanScrollForward(scrollState)
                                Column(Modifier.fillMaxWidth().verticalScroll(scrollState)) { text() }
                                ScrollableContentHint(canScrollForward, Modifier.align(Alignment.BottomCenter))
                            } else {
                                // Lists and forms can own their scrolling, within this measured viewport.
                                text()
                            }
                        }
                    }
                }
                if (dismissButton != null || confirmButton != null) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                        ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                            if (dismissButtonBelowWrappedActions) {
                                AdaptiveDialogActionLayout(
                                    confirmButton = confirmButton,
                                    dismissButton = dismissButton,
                                    onWrappedChanged = { actionsWrapped = it },
                                )
                            } else {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    dismissButton?.invoke()
                                    confirmButton?.invoke()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep the standard one-row order when it fits; move dismiss below wrapped actions. */
@Composable
private fun AdaptiveDialogActionLayout(
    confirmButton: (@Composable () -> Unit)?,
    dismissButton: (@Composable () -> Unit)?,
    onWrappedChanged: (Boolean) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        var wrapped by remember(maxWidth) { mutableStateOf(false) }
        var dismissBounds by remember(maxWidth) { mutableStateOf<Rect?>(null) }
        var confirmBounds by remember(maxWidth) { mutableStateOf<Rect?>(null) }

        LaunchedEffect(dismissBounds, confirmBounds) {
            val dismiss = dismissBounds
            val confirm = confirmBounds
            if (dismiss != null && confirm != null) {
                val shouldWrap = confirm.top > dismiss.top ||
                    confirm.height > dismiss.height
                if (wrapped != shouldWrap) wrapped = shouldWrap
                onWrappedChanged(shouldWrap)
            }
        }

        if (wrapped) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                confirmButton?.invoke()
                dismissButton?.invoke()
            }
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dismissButton?.let { button ->
                    Box(
                        modifier = Modifier.onGloballyPositioned {
                            dismissBounds = it.boundsInRoot()
                        },
                    ) { button() }
                }
                confirmButton?.let { button ->
                    Box(
                        modifier = Modifier.onGloballyPositioned {
                            confirmBounds = it.boundsInRoot()
                        },
                    ) { button() }
                }
            }
        }
    }
}
