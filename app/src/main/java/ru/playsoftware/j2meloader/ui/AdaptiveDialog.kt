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

package ru.playsoftware.j2meloader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

private val DialogMaximumWidth = 720.dp
private val DialogHorizontalMargin = 24.dp
private val DialogCompactHorizontalMargin = 16.dp

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
            .coerceAtMost(DialogMaximumWidth),
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
    confirmButton: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
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
) {
    val layout = adaptiveDialogLayout()
    val compact = layout.maxHeight < 320.dp
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
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("adaptive-dialog-surface"),
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
                            if (textScrollable) {
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
