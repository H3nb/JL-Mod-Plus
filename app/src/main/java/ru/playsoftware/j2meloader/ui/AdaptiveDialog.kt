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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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

    fun maxContentHeight(
        reservedHeight: Dp = 200.dp,
    ): Dp = (maxHeight - reservedHeight)
        .coerceAtLeast(0.dp)
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

/** Material 3 AlertDialog with the project-wide adaptive modal bounds. */
@Composable
internal fun AdaptiveAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
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
    val titleStyle = if (layout.maxHeight < 320.dp) {
        MaterialTheme.typography.titleMedium
    } else {
        MaterialTheme.typography.titleLarge
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = layout.modifier.then(modifier),
        dismissButton = dismissButton,
        icon = icon,
        title = title?.let { titleContent ->
            {
                ProvideTextStyle(titleStyle) {
                    titleContent()
                }
            }
        },
        text = text?.let { textContent ->
            if (textScrollable) {
                {
                    val scrollState = rememberScrollState()
                    val canScrollForward = rememberScrollCanScrollForward(scrollState)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = layout.maxContentHeight()),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                        ) {
                            textContent()
                        }
                        ScrollableContentHint(
                            visible = canScrollForward,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            } else {
                textContent
            }
        },
        shape = shape,
        containerColor = containerColor,
        iconContentColor = iconContentColor,
        titleContentColor = titleContentColor,
        textContentColor = textContentColor,
        tonalElevation = tonalElevation,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        ),
    )
}
