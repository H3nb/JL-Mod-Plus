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

package io.github.h3nb.jlmodplus.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.h3nb.jlmodplus.ui.AppComposeTheme

@Composable
private fun ChoiceDialogContent(items: List<String>, onChoice: (Int) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp),
        ) {
            itemsIndexed(items, key = { index, item -> "$index:$item" }) { index, item ->
                TextButton(
                    onClick = { onChoice(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = item,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageDialogContent(message: String, maxHeightDp: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Text(
            text = message,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeightDp.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
        )
    }
}

@Preview(name = "Config choice", showBackground = true, widthDp = 420, heightDp = 360)
@Composable
fun ConfigChoicePreview() {
    AppComposeTheme {
        ChoiceDialogContent(
            items = listOf("Android (recommended)", "Ask before continuing", "Ignore checks (unsafe)"),
            onChoice = {},
        )
    }
}

@Preview(name = "Config choice dark", showBackground = true, widthDp = 420, heightDp = 360, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ConfigChoiceDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        ChoiceDialogContent(
            items = listOf("Android (recommended)", "Ask before continuing", "Ignore checks (unsafe)"),
            onChoice = {},
        )
    }
}

@Preview(name = "Config message", showBackground = true, widthDp = 420, heightDp = 220)
@Composable
fun ConfigMessagePreview() {
    AppComposeTheme {
        MessageDialogContent(
            message = "All HTTPS/SSL certificate checks will be disabled for this MIDlet. Network traffic may be intercepted.",
            maxHeightDp = 180,
        )
    }
}

@Preview(name = "Config message dark", showBackground = true, widthDp = 420, heightDp = 220, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun ConfigMessageDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        MessageDialogContent(
            message = "All HTTPS/SSL certificate checks will be disabled for this MIDlet. Network traffic may be intercepted.",
            maxHeightDp = 180,
        )
    }
}
