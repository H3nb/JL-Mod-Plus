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

package io.github.h3nb.jlmodplus.crashes.dialog

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

interface ReportComposeCallbacks {
    fun onPrimaryAction()
    fun onCopyAction()
    fun onCancelAction()
    fun onChoice(index: Int)
}

class ReportComposeState {
    var title by mutableStateOf("")
        private set
    var message by mutableStateOf("")
        private set
    var primaryLabel by mutableStateOf("")
        private set
    var copyLabel by mutableStateOf("")
        private set
    var cancelLabel by mutableStateOf("")
        private set
    var choicesTitle by mutableStateOf<String?>(null)
        private set
    var choices by mutableStateOf<List<String>>(emptyList())
        private set

    fun setReport(
        title: String,
        message: String,
        primaryLabel: String,
        copyLabel: String,
        cancelLabel: String,
    ) {
        this.title = title
        this.message = message
        this.primaryLabel = primaryLabel
        this.copyLabel = copyLabel
        this.cancelLabel = cancelLabel
    }

    fun showChoices(title: String, choices: Array<String>) {
        choicesTitle = title
        this.choices = choices.toList()
    }

    fun hideChoices() {
        choicesTitle = null
        choices = emptyList()
    }
}

object ReportComposeHost {
    @JvmStatic
    fun install(
        activity: ComponentActivity,
        state: ReportComposeState,
        callbacks: ReportComposeCallbacks,
    ) {
        activity.setContent {
            AppComposeTheme {
                ReportDialogs(state, callbacks)
            }
        }
    }
}

@Composable
private fun ReportDialogs(state: ReportComposeState, callbacks: ReportComposeCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks::onCancelAction,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(state.title) },
        text = {
            Text(
                text = state.message,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = callbacks::onPrimaryAction) { Text(state.primaryLabel) }
                TextButton(onClick = callbacks::onCopyAction) { Text(state.copyLabel) }
                TextButton(onClick = callbacks::onCancelAction) { Text(state.cancelLabel) }
            }
        },
    )

    state.choicesTitle?.let { title ->
        var selected by remember(state.choices) { mutableIntStateOf(-1) }
        AlertDialog(
            onDismissRequest = state::hideChoices,
            title = { Text(title) },
            text = {
                Column {
                    state.choices.forEachIndexed { index, choice ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selected = index }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected == index, onClick = { selected = index })
                            Text(choice, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selected >= 0,
                    onClick = {
                        state.hideChoices()
                        callbacks.onChoice(selected)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = state::hideChoices) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}
