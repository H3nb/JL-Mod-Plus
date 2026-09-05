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

package ru.woesss.j2me.installer

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import ru.playsoftware.j2meloader.ui.ScrollableContentHint
import ru.playsoftware.j2meloader.ui.adaptiveDialogLayout
import ru.playsoftware.j2meloader.ui.rememberScrollCanScrollForward

/** Presentation-only state. Installation and repository ownership remain in InstallerDialog. */
sealed interface InstallerUiState {
    val title: String

    data class Loading(
        override val title: String,
        val status: String,
    ) : InstallerUiState

    data class Confirmation(
        override val title: String,
        val message: String,
        val installLabel: String,
        val closeLabel: String,
        val runLabel: String?,
        val iconPath: String?,
    ) : InstallerUiState

    data class Converting(
        override val title: String,
        val message: String,
        val status: String,
    ) : InstallerUiState

    data class Success(
        override val title: String,
        val status: String,
        val startLabel: String,
        val closeLabel: String,
        val iconPath: String?,
    ) : InstallerUiState

    data class Error(
        override val title: String,
        val message: String,
        val closeLabel: String,
        val retryLabel: String? = null,
        val details: String? = null,
    ) : InstallerUiState
}

/** Stable event boundary for the Java installer state machine. */
interface InstallerActions {
    fun onInstall()
    fun onClose()
    fun onRunExisting()
    fun onLaunchInstalled()
}

class InstallerComposeController internal constructor(
    composeView: ComposeView,
    private val actions: InstallerActions,
    initialState: InstallerUiState,
) {
    private var state by mutableStateOf(initialState)

    init {
        composeView.id = R.id.installer_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                InstallerScreen(state = state, actions = actions)
            }
        }
    }

    fun showLoading(title: String, status: String) {
        state = InstallerUiState.Loading(title = title, status = status)
    }

    fun showConfirmation(
        title: String,
        message: String,
        installLabel: String,
        closeLabel: String,
        runLabel: String?,
        iconPath: String?,
    ) {
        state = InstallerUiState.Confirmation(
            title = title,
            message = message,
            installLabel = installLabel,
            closeLabel = closeLabel,
            runLabel = runLabel,
            iconPath = iconPath,
        )
    }

    fun showConverting(title: String, message: String, status: String) {
        state = InstallerUiState.Converting(title = title, message = message, status = status)
    }

    fun showSuccess(
        title: String,
        status: String,
        startLabel: String,
        closeLabel: String,
        iconPath: String?,
    ) {
        state = InstallerUiState.Success(
            title = title,
            status = status,
            startLabel = startLabel,
            closeLabel = closeLabel,
            iconPath = iconPath,
        )
    }

    @JvmOverloads
    fun showError(title: String, message: String, closeLabel: String,
                  retryLabel: String? = null, details: String? = null) {
        state = InstallerUiState.Error(
            title = title,
            message = message,
            closeLabel = closeLabel,
            retryLabel = retryLabel,
            details = details,
        )
    }
}

object InstallerComposeBridge {
    @JvmStatic
    fun install(
        composeView: ComposeView,
        actions: InstallerActions,
        initialState: InstallerUiState,
    ): InstallerComposeController = InstallerComposeController(
        composeView = composeView,
        actions = actions,
        initialState = initialState,
    )
}

@Composable
fun InstallerScreen(
    state: InstallerUiState,
    actions: InstallerActions,
    modifier: Modifier = Modifier,
) {
    val iconPath = when (state) {
        is InstallerUiState.Confirmation -> state.iconPath
        is InstallerUiState.Success -> state.iconPath
        is InstallerUiState.Loading,
        is InstallerUiState.Converting,
        is InstallerUiState.Error -> null
    }
    val icon = remember(iconPath) {
        iconPath?.let(BitmapFactory::decodeFile)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.56f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        val compactWidth = maxWidth < 360.dp
        val compactHeight = maxHeight < 480.dp
        val dialogLayout = adaptiveDialogLayout(maxWidth, maxHeight)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val compactScrollState = rememberScrollState()
            val compactCanScrollForward = rememberScrollCanScrollForward(compactScrollState)
            val dialogShape = MaterialTheme.shapes.extraLarge
            Surface(
                modifier = dialogLayout.modifier.clip(dialogShape).testTag("installer-popup"),
                shape = dialogShape,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (compactHeight) Modifier.verticalScroll(compactScrollState)
                                else Modifier,
                            )
                            .padding(
                                horizontal = if (compactWidth) 16.dp else 24.dp,
                                vertical = if (compactHeight) 8.dp else 20.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(if (compactHeight) 4.dp else 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(if (compactHeight) 40.dp else 56.dp),
                                    contentScale = ContentScale.Fit,
                                )
                            }
                            Text(
                                text = state.title,
                                modifier = Modifier.weight(1f),
                                style = if (compactHeight) {
                                    MaterialTheme.typography.titleSmall
                                } else {
                                    MaterialTheme.typography.titleLarge
                                },
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        when (state) {
                            is InstallerUiState.Loading -> {
                                InstallerProgress(state.status, compactHeight)
                                TextButton(onClick = actions::onClose) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }
                            is InstallerUiState.Converting -> {
                                InstallerMessage(
                                    message = state.message,
                                    modifier = if (compactHeight) {
                                        Modifier
                                    } else {
                                        Modifier.weight(1f, fill = false)
                                    },
                                    compact = compactHeight,
                                    scrollable = !compactHeight,
                                )
                                InstallerProgress(state.status, compactHeight)
                                TextButton(onClick = actions::onClose) {
                                    Text(stringResource(android.R.string.cancel))
                                }
                            }

                            is InstallerUiState.Confirmation -> {
                                InstallerMessage(
                                    message = state.message,
                                    modifier = if (compactHeight) {
                                        Modifier
                                    } else {
                                        Modifier.weight(1f, fill = false)
                                    },
                                    compact = compactHeight,
                                    scrollable = !compactHeight,
                                )
                                InstallerButtons(
                                    closeLabel = state.closeLabel,
                                    primaryLabel = state.installLabel,
                                    runLabel = state.runLabel,
                                    onClose = actions::onClose,
                                    onPrimary = actions::onInstall,
                                    onRun = actions::onRunExisting,
                                    compact = compactHeight,
                                )
                            }

                            is InstallerUiState.Success -> {
                                InstallerProgressMessage(state.status, compactHeight)
                                InstallerButtons(
                                    closeLabel = state.closeLabel,
                                    primaryLabel = state.startLabel,
                                    runLabel = null,
                                    onClose = actions::onClose,
                                    onPrimary = actions::onLaunchInstalled,
                                    onRun = actions::onRunExisting,
                                    compact = compactHeight,
                                )
                            }

                            is InstallerUiState.Error -> {
                                val clipboard = LocalClipboardManager.current
                                InstallerMessage(
                                    message = state.message,
                                    isError = true,
                                    modifier = if (compactHeight) {
                                        Modifier
                                    } else {
                                        Modifier.weight(1f, fill = false)
                                    },
                                    compact = compactHeight,
                                    scrollable = !compactHeight,
                                )
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    if (state.details != null) {
                                        TextButton(onClick = { clipboard.setText(AnnotatedString(state.details)) }) {
                                            Text(stringResource(R.string.installer_copy_details))
                                        }
                                    }
                                    if (state.retryLabel != null) {
                                        Button(onClick = actions::onInstall) { Text(state.retryLabel) }
                                    }
                                    TextButton(onClick = actions::onClose) {
                                        Text(
                                            state.closeLabel,
                                            style = if (compactHeight) {
                                                MaterialTheme.typography.labelMedium
                                            } else {
                                                MaterialTheme.typography.labelLarge
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ScrollableContentHint(
                        visible = compactCanScrollForward,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallerProgress(status: String, compact: Boolean) {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = status },
    )
    InstallerProgressMessage(status, compact)
}

@Composable
private fun InstallerProgressMessage(status: String, compact: Boolean) {
    Text(
        text = status,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun InstallerMessage(
    message: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    compact: Boolean = false,
    scrollable: Boolean = true,
) {
    val scrollState = rememberScrollState()
    val canScrollForward = rememberScrollCanScrollForward(scrollState) && scrollable
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier),
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
        )
        ScrollableContentHint(
            visible = canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun InstallerButtons(
    closeLabel: String,
    primaryLabel: String,
    runLabel: String?,
    onClose: () -> Unit,
    onPrimary: () -> Unit,
    onRun: () -> Unit,
    compact: Boolean,
) {
    val labelStyle = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (runLabel != null) {
            TextButton(onClick = onRun) { Text(runLabel, style = labelStyle) }
        }
        TextButton(onClick = onClose) { Text(closeLabel, style = labelStyle) }
        Button(
            onClick = onPrimary,
            shape = MaterialTheme.shapes.medium,
        ) {
            Text(primaryLabel, style = labelStyle)
        }
    }
}
