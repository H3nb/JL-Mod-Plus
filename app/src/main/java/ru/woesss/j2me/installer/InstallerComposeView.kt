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

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

class InstallerUiState(context: android.content.Context) {
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
    private var modeLabelsState by mutableStateOf<List<String>?>(null)
    private var modeSelectedState by mutableIntStateOf(0)
    private var modePositiveLabelState by mutableStateOf("")
    private var modeNegativeLabelState by mutableStateOf("")
    private var modeActionState by mutableStateOf<InstallerModeAction?>(null)

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

    fun showTransformModeDialog(
        labels: Array<String>,
        selectedIndex: Int,
        positiveLabel: String,
        negativeLabel: String,
        action: InstallerModeAction,
    ) {
        modeLabelsState = labels.toList()
        modeSelectedState = selectedIndex
        modePositiveLabelState = positiveLabel
        modeNegativeLabelState = negativeLabel
        modeActionState = action
    }

    fun hideTransformModeDialog() {
        modeLabelsState = null
        modeActionState = null
    }

    @Composable
    fun Render() {
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
            modeLabelsState?.let { labels ->
                InstallerModeDialog(
                    labels = labels,
                    selectedIndex = modeSelectedState,
                    positiveLabel = modePositiveLabelState,
                    negativeLabel = modeNegativeLabelState,
                    onDismiss = ::hideTransformModeDialog,
                    onConfirm = { index ->
                        val action = modeActionState
                        hideTransformModeDialog()
                        action?.onSelected(index)
                    },
                )
            }
        }
    }
}

object InstallerComposeHost {
    @JvmStatic
    fun install(activity: ComponentActivity, state: InstallerUiState) {
        activity.setContent { state.Render() }
    }
}

fun interface InstallerModeAction {
    fun onSelected(index: Int)
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall)
            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            if (progressVisible) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(text = statusText, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

@Composable
private fun InstallerModeDialog(
    labels: List<String>,
    selectedIndex: Int,
    positiveLabel: String,
    negativeLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selected by remember { mutableIntStateOf(selectedIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true),
        title = { Text(stringResource(R.string.conversion_mode_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(labels.size) { index ->
                    val label = labels[index]
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selected = index }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == index, onClick = { selected = index })
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(positiveLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(negativeLabel) }
        },
    )
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
