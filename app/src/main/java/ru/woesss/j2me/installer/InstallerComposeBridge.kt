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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

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
        is InstallerUiState.Loading, is InstallerUiState.Converting -> null
    }
    val icon = remember(iconPath) {
        iconPath?.let(BitmapFactory::decodeFile)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = 280.dp, max = 480.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally),
                    contentScale = ContentScale.Fit,
                )
            }

            when (state) {
                is InstallerUiState.Loading -> InstallerProgress(state.status)
                is InstallerUiState.Converting -> {
                    InstallerMessage(state.message)
                    InstallerProgress(state.status)
                }

                is InstallerUiState.Confirmation -> {
                    InstallerMessage(state.message)
                    InstallerButtons(
                        closeLabel = state.closeLabel,
                        primaryLabel = state.installLabel,
                        runLabel = state.runLabel,
                        onClose = actions::onClose,
                        onPrimary = actions::onInstall,
                        onRun = actions::onRunExisting,
                    )
                }

                is InstallerUiState.Success -> {
                    InstallerProgressMessage(state.status)
                    InstallerButtons(
                        closeLabel = state.closeLabel,
                        primaryLabel = state.startLabel,
                        runLabel = null,
                        onClose = actions::onClose,
                        onPrimary = actions::onLaunchInstalled,
                        onRun = actions::onRunExisting,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallerProgress(status: String) {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = status },
    )
    InstallerProgressMessage(status)
}

@Composable
private fun InstallerProgressMessage(status: String) {
    Text(
        text = status,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InstallerMessage(message: String) {
    Text(
        text = message,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .verticalScroll(rememberScrollState()),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InstallerButtons(
    closeLabel: String,
    primaryLabel: String,
    runLabel: String?,
    onClose: () -> Unit,
    onPrimary: () -> Unit,
    onRun: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (runLabel != null) {
            TextButton(onClick = onRun) { Text(runLabel) }
        }
        TextButton(onClick = onClose) { Text(closeLabel) }
        Button(onClick = onPrimary) { Text(primaryLabel) }
    }
}
