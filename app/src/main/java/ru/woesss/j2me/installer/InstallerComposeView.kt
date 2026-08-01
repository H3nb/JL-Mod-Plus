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

package ru.woesss.j2me.installer

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

class InstallerComposeView(context: Context) {
    private val composeView = ComposeView(context)
    private var titleState by mutableStateOf(context.getString(R.string.app_name))
    private var messageState by mutableStateOf("")
    private var progressVisibleState by mutableStateOf(true)
    private var statusTextState by mutableStateOf(context.getString(R.string.loading_info))
    private var positiveLabelState by mutableStateOf<String?>(null)
    private var negativeLabelState by mutableStateOf<String?>(null)
    private var neutralLabelState by mutableStateOf<String?>(null)
    private var positiveActionState by mutableStateOf<Runnable?>(null)
    private var negativeActionState by mutableStateOf<Runnable?>(null)
    private var neutralActionState by mutableStateOf<Runnable?>(null)

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        composeView.setContent {
            AppComposeTheme {
                InstallerContent(
                    title = titleState,
                    message = messageState,
                    progressVisible = progressVisibleState,
                    statusText = statusTextState,
                    positiveLabel = positiveLabelState,
                    negativeLabel = negativeLabelState,
                    neutralLabel = neutralLabelState,
                    positiveAction = positiveActionState,
                    negativeAction = negativeActionState,
                    neutralAction = neutralActionState,
                )
            }
        }
    }

    fun getComposeView(): ComposeView = composeView

    fun setTitle(title: CharSequence) {
        titleState = title.toString()
    }

    fun setMessage(message: CharSequence) {
        messageState = message.toString()
    }

    fun setPositiveButton(label: CharSequence?, action: Runnable?) {
        positiveLabelState = label?.toString()
        positiveActionState = action
    }

    fun setNegativeButton(label: CharSequence?, action: Runnable?) {
        negativeLabelState = label?.toString()
        negativeActionState = action
    }

    fun setNeutralButton(label: CharSequence?, action: Runnable?) {
        neutralLabelState = label?.toString()
        neutralActionState = action
    }

    fun clearButtons() {
        setPositiveButton(null, null)
        setNegativeButton(null, null)
        setNeutralButton(null, null)
    }

    fun setProgressVisible(visible: Boolean) {
        progressVisibleState = visible
    }

    fun setStatusText(text: CharSequence) {
        statusTextState = text.toString()
    }
}

@Composable
private fun InstallerContent(
    title: String,
    message: String,
    progressVisible: Boolean,
    statusText: String,
    positiveLabel: String?,
    negativeLabel: String?,
    neutralLabel: String?,
    positiveAction: Runnable?,
    negativeAction: Runnable?,
    neutralAction: Runnable?,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (progressVisible) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = statusText,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
            ) {
                positiveLabel?.let { label ->
                    TextButton(onClick = { positiveAction?.run() }) { Text(label) }
                }
                neutralLabel?.let { label ->
                    TextButton(onClick = { neutralAction?.run() }) { Text(label) }
                }
                negativeLabel?.let { label ->
                    TextButton(onClick = { negativeAction?.run() }) { Text(label) }
                }
            }
        }
    }
}

@Preview(name = "Installer loading", showBackground = true, widthDp = 420, heightDp = 120)
@Composable
internal fun InstallerLoadingPreview() {
    AppComposeTheme {
        InstallerContent(
            title = "MIDlet installer",
            message = "",
            progressVisible = true,
            statusText = stringResource(R.string.loading_info),
            positiveLabel = null,
            negativeLabel = null,
            neutralLabel = null,
            positiveAction = null,
            negativeAction = null,
            neutralAction = null,
        )
    }
}

@Preview(
    name = "Installer loading dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 120,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun InstallerLoadingDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        InstallerContent(
            title = "MIDlet installer",
            message = "",
            progressVisible = true,
            statusText = stringResource(R.string.loading_info),
            positiveLabel = null,
            negativeLabel = null,
            neutralLabel = null,
            positiveAction = null,
            negativeAction = null,
            neutralAction = null,
        )
    }
}

@Preview(name = "Installer complete", showBackground = true, widthDp = 420, heightDp = 120)
@Composable
internal fun InstallerCompletePreview() {
    AppComposeTheme {
        InstallerContent(
            title = "MIDlet installer",
            message = "",
            progressVisible = false,
            statusText = stringResource(R.string.install_done),
            positiveLabel = null,
            negativeLabel = null,
            neutralLabel = null,
            positiveAction = null,
            negativeAction = null,
            neutralAction = null,
        )
    }
}

@Preview(
    name = "Installer complete dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 120,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun InstallerCompleteDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        InstallerContent(
            title = "MIDlet installer",
            message = "",
            progressVisible = false,
            statusText = stringResource(R.string.install_done),
            positiveLabel = null,
            negativeLabel = null,
            neutralLabel = null,
            positiveAction = null,
            negativeAction = null,
            neutralAction = null,
        )
    }
}
