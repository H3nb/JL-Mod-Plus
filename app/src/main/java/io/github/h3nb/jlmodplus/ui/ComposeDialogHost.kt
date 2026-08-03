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

package io.github.h3nb.jlmodplus.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy

/**
 * Compose-only dialog content with a small Android Dialog host for Java entry points.
 * The host keeps existing lifecycle/callback contracts while removing native widget
 * chrome that otherwise bypasses the app's light/dark Compose theme.
 */
object ComposeDialogHost {
    @JvmStatic
    @JvmOverloads
    fun showMessage(
        context: Context,
        title: String,
        message: String,
        positiveLabel: String?,
        negativeLabel: String?,
        neutralLabel: String?,
        cancelable: Boolean,
        positiveAction: Runnable?,
        negativeAction: Runnable?,
        neutralAction: Runnable?,
        onCanceled: Runnable? = null,
        dismissOnAction: Boolean = true,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    MessageDialogContent(
                        dialog = dialog,
                        title = title,
                        message = message,
                        positiveLabel = positiveLabel,
                        negativeLabel = negativeLabel,
                        neutralLabel = neutralLabel,
                        positiveAction = positiveAction,
                        negativeAction = negativeAction,
                        neutralAction = neutralAction,
                        dismissOnAction = dismissOnAction,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { onCanceled?.run() }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    @JvmStatic
    @JvmOverloads
    fun showChoice(
        context: Context,
        title: String,
        entries: Array<String>,
        selectedIndex: Int,
        cancelLabel: String?,
        cancelable: Boolean,
        onSelected: ComposeChoiceAction,
        onCanceled: Runnable? = null,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    ChoiceDialogContent(
                        dialog = dialog,
                        title = title,
                        entries = entries,
                        selectedIndex = selectedIndex,
                        cancelLabel = cancelLabel,
                        onSelected = onSelected,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { onCanceled?.run() }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    @JvmStatic
    @JvmOverloads
    fun showChoiceActions(
        context: Context,
        title: String,
        entries: Array<String>,
        selectedIndex: Int,
        positiveLabel: String?,
        neutralLabel: String?,
        negativeLabel: String?,
        cancelable: Boolean,
        neutralRequiresSelection: Boolean,
        positiveAction: ComposeChoiceButtonAction?,
        neutralAction: ComposeChoiceButtonAction?,
        negativeAction: Runnable?,
        onCanceled: Runnable? = null,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    ChoiceActionsDialogContent(
                        dialog = dialog,
                        title = title,
                        entries = entries,
                        selectedIndex = selectedIndex,
                        positiveLabel = positiveLabel,
                        neutralLabel = neutralLabel,
                        negativeLabel = negativeLabel,
                        neutralRequiresSelection = neutralRequiresSelection,
                        positiveAction = positiveAction,
                        neutralAction = neutralAction,
                        negativeAction = negativeAction,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { onCanceled?.run() }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    @JvmStatic
    @JvmOverloads
    fun showMultiChoice(
        context: Context,
        title: String,
        entries: Array<String>,
        checked: BooleanArray,
        positiveLabel: String,
        negativeLabel: String?,
        cancelable: Boolean,
        onConfirmed: ComposeMultiChoiceAction,
        onCanceled: Runnable? = null,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    MultiChoiceDialogContent(
                        dialog = dialog,
                        title = title,
                        entries = entries,
                        checked = checked,
                        positiveLabel = positiveLabel,
                        negativeLabel = negativeLabel,
                        onConfirmed = onConfirmed,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { onCanceled?.run() }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    @JvmStatic
    @JvmOverloads
    fun showCheckboxMessage(
        context: Context,
        title: String,
        message: String,
        checkboxLabel: String,
        initiallyChecked: Boolean,
        positiveLabel: String,
        negativeLabel: String?,
        cancelable: Boolean,
        onConfirmed: ComposeCheckboxAction,
        onCanceled: Runnable? = null,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    CheckboxMessageDialogContent(
                        dialog = dialog,
                        title = title,
                        message = message,
                        checkboxLabel = checkboxLabel,
                        initiallyChecked = initiallyChecked,
                        positiveLabel = positiveLabel,
                        negativeLabel = negativeLabel,
                        onConfirmed = onConfirmed,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { onCanceled?.run() }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    @JvmStatic
    fun showTextInput(
        context: Context,
        title: String,
        label: String,
        initialValue: String,
        positiveLabel: String,
        negativeLabel: String?,
        cancelable: Boolean,
        onConfirmed: ComposeTextInputAction,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    TextInputDialogContent(
                        dialog = dialog,
                        title = title,
                        label = label,
                        initialValue = initialValue,
                        positiveLabel = positiveLabel,
                        negativeLabel = negativeLabel,
                        onConfirmed = onConfirmed,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }

    @JvmStatic
    @JvmOverloads
    fun showTextInputActions(
        context: Context,
        title: String,
        label: String,
        initialValue: String,
        numericOnly: Boolean,
        positiveLabel: String,
        neutralLabel: String?,
        negativeLabel: String?,
        cancelable: Boolean,
        onConfirmed: ComposeTextInputAction,
        neutralAction: Runnable?,
        onCanceled: Runnable? = null,
    ): Dialog {
        val dialog = ComponentDialog(context)
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    TextInputActionsDialogContent(
                        dialog = dialog,
                        title = title,
                        label = label,
                        initialValue = initialValue,
                        numericOnly = numericOnly,
                        positiveLabel = positiveLabel,
                        neutralLabel = neutralLabel,
                        negativeLabel = negativeLabel,
                        onConfirmed = onConfirmed,
                        neutralAction = neutralAction,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(cancelable)
        dialog.setOnCancelListener { onCanceled?.run() }
        dialog.window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f)
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        dialog.show()
        return dialog
    }
}

fun interface ComposeChoiceAction {
    fun onSelected(index: Int)
}

fun interface ComposeChoiceButtonAction {
    fun onAction(index: Int)
}

fun interface ComposeMultiChoiceAction {
    fun onConfirmed(checked: BooleanArray)
}

fun interface ComposeCheckboxAction {
    fun onConfirmed(checked: Boolean)
}

fun interface ComposeTextInputAction {
    fun onConfirmed(value: String): Boolean
}

@Composable
private fun MessageDialogContent(
    dialog: Dialog,
    title: String,
    message: String,
    positiveLabel: String?,
    negativeLabel: String?,
    neutralLabel: String?,
    positiveAction: Runnable?,
    negativeAction: Runnable?,
    neutralAction: Runnable?,
    dismissOnAction: Boolean,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                positiveLabel?.let { label ->
                    TextButton(onClick = {
                        positiveAction?.run()
                        if (dismissOnAction) dialog.dismiss()
                    }) {
                        Text(label)
                    }
                }
                neutralLabel?.let { label ->
                    TextButton(onClick = {
                        neutralAction?.run()
                        if (dismissOnAction) dialog.dismiss()
                    }) {
                        Text(label)
                    }
                }
                negativeLabel?.let { label ->
                    TextButton(onClick = {
                        negativeAction?.run()
                        if (dismissOnAction) dialog.dismiss()
                    }) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceDialogContent(
    dialog: Dialog,
    title: String,
    entries: Array<String>,
    selectedIndex: Int,
    cancelLabel: String?,
    onSelected: ComposeChoiceAction,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(entries.size) { index ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                dialog.dismiss()
                                onSelected.onSelected(index)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selectedIndex,
                            onClick = {
                                dialog.dismiss()
                                onSelected.onSelected(index)
                            },
                        )
                        Text(
                            text = entries[index],
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            cancelLabel?.let { label ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { dialog.dismiss() }) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextInputDialogContent(
    dialog: Dialog,
    title: String,
    label: String,
    initialValue: String,
    positiveLabel: String,
    negativeLabel: String?,
    onConfirmed: ComposeTextInputAction,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                negativeLabel?.let { cancelLabel ->
                    TextButton(onClick = { dialog.dismiss() }) {
                        Text(cancelLabel)
                    }
                }
                TextButton(onClick = {
                    if (onConfirmed.onConfirmed(value)) {
                        dialog.dismiss()
                    }
                }) {
                    Text(positiveLabel)
                }
            }
        }
    }
}

@Composable
private fun ChoiceActionsDialogContent(
    dialog: Dialog,
    title: String,
    entries: Array<String>,
    selectedIndex: Int,
    positiveLabel: String?,
    neutralLabel: String?,
    negativeLabel: String?,
    neutralRequiresSelection: Boolean,
    positiveAction: ComposeChoiceButtonAction?,
    neutralAction: ComposeChoiceButtonAction?,
    negativeAction: Runnable?,
) {
    var selected by remember(selectedIndex) { mutableIntStateOf(selectedIndex) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                items(entries.size) { index ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = index }
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = index == selected,
                            onClick = { selected = index },
                        )
                        Text(
                            text = entries[index],
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
            ) {
                positiveLabel?.let { label ->
                    TextButton(
                        enabled = positiveAction != null,
                        onClick = {
                            dialog.dismiss()
                            positiveAction?.onAction(selected)
                        },
                    ) {
                        Text(label)
                    }
                }
                neutralLabel?.let { label ->
                    TextButton(
                        enabled = neutralAction != null &&
                            (!neutralRequiresSelection || selected >= 0),
                        onClick = {
                            dialog.dismiss()
                            neutralAction?.onAction(selected)
                        },
                    ) {
                        Text(label)
                    }
                }
                negativeLabel?.let { label ->
                    TextButton(onClick = {
                        dialog.dismiss()
                        negativeAction?.run()
                    }) {
                        Text(label)
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiChoiceDialogContent(
    dialog: Dialog,
    title: String,
    entries: Array<String>,
    checked: BooleanArray,
    positiveLabel: String,
    negativeLabel: String?,
    onConfirmed: ComposeMultiChoiceAction,
) {
    var selected by remember(checked) { mutableStateOf(checked.copyOf()) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(entries.size) { index ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = selected.copyOf().also { it[index] = !it[index] }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected.getOrElse(index) { false },
                            onCheckedChange = {
                                selected = selected.copyOf().also { it[index] = !it[index] }
                            },
                        )
                        Text(
                            text = entries[index],
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
            ) {
                negativeLabel?.let { label ->
                    TextButton(onClick = { dialog.dismiss() }) {
                        Text(label)
                    }
                }
                TextButton(onClick = {
                    dialog.dismiss()
                    onConfirmed.onConfirmed(selected)
                }) {
                    Text(positiveLabel)
                }
            }
        }
    }
}

@Composable
private fun CheckboxMessageDialogContent(
    dialog: Dialog,
    title: String,
    message: String,
    checkboxLabel: String,
    initiallyChecked: Boolean,
    positiveLabel: String,
    negativeLabel: String?,
    onConfirmed: ComposeCheckboxAction,
) {
    var checked by remember(initiallyChecked) { mutableStateOf(initiallyChecked) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = message,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { checked = !checked },
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { checked = it })
                Text(
                    text = checkboxLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
            ) {
                negativeLabel?.let { label ->
                    TextButton(onClick = { dialog.dismiss() }) {
                        Text(label)
                    }
                }
                TextButton(onClick = {
                    dialog.dismiss()
                    onConfirmed.onConfirmed(checked)
                }) {
                    Text(positiveLabel)
                }
            }
        }
    }
}

@Composable
private fun TextInputActionsDialogContent(
    dialog: Dialog,
    title: String,
    label: String,
    initialValue: String,
    numericOnly: Boolean,
    positiveLabel: String,
    neutralLabel: String?,
    negativeLabel: String?,
    onConfirmed: ComposeTextInputAction,
    neutralAction: Runnable?,
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = if (numericOnly) it.filter(Char::isDigit) else it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
            ) {
                neutralLabel?.let { labelText ->
                    TextButton(onClick = {
                        dialog.dismiss()
                        neutralAction?.run()
                    }) {
                        Text(labelText)
                    }
                }
                negativeLabel?.let { labelText ->
                    TextButton(onClick = { dialog.dismiss() }) {
                        Text(labelText)
                    }
                }
                TextButton(onClick = {
                    if (onConfirmed.onConfirmed(value)) {
                        dialog.dismiss()
                    }
                }) {
                    Text(positiveLabel)
                }
            }
        }
    }
}
