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

package javax.microedition.lcdui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.activity.ComponentDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

class AlertComposeDialog(
    context: Context,
    title: String?,
    message: String?,
    image: Bitmap?,
    private val indicatorView: View?,
    positive: Command?,
    negative: Command?,
    neutral: Command?,
    private val commandCallback: CommandCallback,
    private val dismissCallback: DismissCallback,
) {
    fun interface CommandCallback {
        fun onCommand(command: Command)
    }

    fun interface DismissCallback {
        fun onDismiss()
    }

    private val dialog = ComponentDialog(context)
    private var titleState by mutableStateOf(title.orEmpty())
    private var messageState by mutableStateOf(message.orEmpty())
    private var imageState by mutableStateOf(image)
    private val positiveCommand = positive
    private val negativeCommand = negative
    private val neutralCommand = neutral

    init {
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
            )
            setContent {
                AppComposeTheme {
                    AlertContent(
                        title = titleState,
                        message = messageState,
                        image = imageState,
                        indicatorView = indicatorView,
                        positive = positiveCommand,
                        negative = negativeCommand,
                        neutral = neutralCommand,
                        onCommand = ::onCommand,
                    )
                }
            }
        }
        dialog.setContentView(composeView)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        dialog.setOnDismissListener { dismissCallback.onDismiss() }
    }

    fun getDialog(): Dialog = dialog

    fun setMessage(message: String?) {
        messageState = message.orEmpty()
    }

    fun setImage(image: Bitmap?) {
        imageState = image
    }

    fun setDismissBehavior(cancelable: Boolean, canceledOnTouchOutside: Boolean) {
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(canceledOnTouchOutside)
    }

    private fun onCommand(command: Command) {
        commandCallback.onCommand(command)
        dialog.dismiss()
    }
}

@Composable
private fun AlertContent(
    title: String,
    message: String,
    image: Bitmap?,
    indicatorView: View?,
    positive: Command?,
    negative: Command?,
    neutral: Command?,
    onCommand: (Command) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                image?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.sizeIn(maxWidth = 48.dp, maxHeight = 48.dp),
                    )
                }
                if (title.isNotEmpty()) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            indicatorView?.let { view ->
                AndroidView(
                    factory = { view },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.End,
            ) {
                neutral?.let { command ->
                    TextButton(onClick = { onCommand(command) }) {
                        Text(command.androidLabel)
                    }
                }
                negative?.let { command ->
                    TextButton(onClick = { onCommand(command) }) {
                        Text(command.androidLabel)
                    }
                }
                positive?.let { command ->
                    TextButton(onClick = { onCommand(command) }) {
                        Text(command.androidLabel)
                    }
                }
            }
        }
    }
}

@Preview(name = "J2ME alert", showBackground = true, widthDp = 420, heightDp = 260)
@Composable
internal fun AlertComposePreview() {
    AppComposeTheme {
        AlertPreviewContent()
    }
}

@Preview(
    name = "J2ME alert dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 260,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
internal fun AlertComposeDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        AlertPreviewContent()
    }
}

@Composable
private fun AlertPreviewContent() {
    AlertContent(
        title = "MIDlet alert",
        message = "This is a Compose-rendered J2ME alert.",
        image = null,
        indicatorView = null,
        positive = Command("OK", Command.OK, 1),
        negative = Command("Cancel", Command.CANCEL, 2),
        neutral = null,
        onCommand = {},
    )
}
