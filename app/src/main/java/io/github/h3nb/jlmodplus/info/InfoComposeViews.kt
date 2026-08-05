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

package io.github.h3nb.jlmodplus.info

import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import javax.microedition.util.ContextHolder

private const val LinkAnnotationTag = "info-link"
private const val GithubUrl = "https://github.com/H3nb/JL-Mod-Plus"

private fun Spanned.toComposeText(): AnnotatedString {
    val source = toString()
    return androidx.compose.ui.text.buildAnnotatedString {
        append(source)
        getSpans(0, length, Any::class.java).forEach { span ->
            val start = getSpanStart(span).coerceIn(0, source.length)
            val end = getSpanEnd(span).coerceIn(start, source.length)
            if (start == end) return@forEach
            when (span) {
                is StyleSpan -> when (span.style) {
                    Typeface.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                    Typeface.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                    Typeface.BOLD_ITALIC -> addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                        start,
                        end,
                    )
                }
                is UnderlineSpan -> addStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline), start, end)
                is RelativeSizeSpan -> addStyle(SpanStyle(fontSize = (16f * span.sizeChange).sp), start, end)
                is URLSpan -> addStringAnnotation(LinkAnnotationTag, span.url, start, end)
            }
        }
    }
}

private fun htmlToComposeText(html: String): AnnotatedString =
    HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toComposeText()

@Composable
private fun Modifier.infoBodyModifier(maxHeight: Int): Modifier =
    this
        .fillMaxWidth()
        .heightIn(max = maxHeight.dp)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 14.dp)

@Composable
private fun InfoBody(text: AnnotatedString, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 16.sp,
    )
    val hasLinks = text.getStringAnnotations(LinkAnnotationTag, 0, text.length).isNotEmpty()
    if (hasLinks) {
        ClickableText(
            text = text,
            modifier = modifier,
            style = textStyle,
            onClick = { offset ->
                text.getStringAnnotations(LinkAnnotationTag, offset, offset)
                    .firstOrNull()
                    ?.let { uriHandler.openUri(it.item) }
            },
        )
    } else {
        Text(text = text, modifier = modifier, style = textStyle)
    }
}

@Composable
private fun AboutContent(version: String) {
    val versionLabel = stringResource(R.string.version)
    val author = stringResource(R.string.about_author)
    val github = stringResource(R.string.about_github)
    val copyright = stringResource(R.string.about_copyright)
    val message = remember(version, versionLabel, author, github, copyright) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(versionLabel)
            append(version)
            append(author)
            val githubText = htmlToComposeText(github).text.trim()
            append("\n")
            val githubStart = length
            append(githubText)
            addStringAnnotation(LinkAnnotationTag, GithubUrl, githubStart, length)
            append("\n")
            append(copyright)
        }
    }
    InfoBody(message, Modifier.infoBodyModifier(maxHeight = 320))
}

@Composable
private fun HelpContent() {
    val help = stringResource(R.string.help_message)
    val message = remember(help) { htmlToComposeText(help) }
    InfoBody(message, Modifier.infoBodyModifier(maxHeight = 360))
}

@Composable
private fun MoreInfoContent() {
    val aboutMessage = stringResource(R.string.about_message)
    InfoBody(
        text = androidx.compose.ui.text.buildAnnotatedString {
            append(aboutMessage)
        },
        modifier = Modifier.infoBodyModifier(maxHeight = 220),
    )
}

@Composable
private fun LicensesContent(licensesHtml: String) {
    val message = remember(licensesHtml) { htmlToComposeText(licensesHtml) }
    InfoBody(message, Modifier.infoBodyModifier(maxHeight = 520))
}

internal sealed interface InfoDialogState {
    data object About : InfoDialogState
    data object Help : InfoDialogState
    data object More : InfoDialogState
    data object Licenses : InfoDialogState
}

@Composable
internal fun InfoDialogs(
    state: InfoDialogState?,
    onDismiss: () -> Unit,
    onLicenses: () -> Unit,
    onMore: () -> Unit,
) {
    when (state) {
        InfoDialogState.About -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.app_name)) },
            text = { AboutContent(io.github.h3nb.jlmodplus.BuildConfig.VERSION_NAME) },
            confirmButton = {
                TextButton(onClick = onMore) { Text(stringResource(R.string.more)) }
            },
            dismissButton = {
                TextButton(onClick = onLicenses) { Text(stringResource(R.string.licenses)) }
            },
        )

        InfoDialogState.Help -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { InfoDialogTitle(stringResource(R.string.help)) },
            text = { HelpContent() },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            },
        )

        InfoDialogState.More -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { InfoDialogTitle(stringResource(R.string.app_name)) },
            text = { MoreInfoContent() },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            },
        )

        InfoDialogState.Licenses -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { InfoDialogTitle(stringResource(R.string.licenses)) },
            text = { LicensesContent(ContextHolder.getAssetAsString("licenses.html")) },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            },
        )

        null -> Unit
    }
}

@Composable
private fun InfoDialogTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun InfoPreviewSurface(content: @Composable () -> Unit) {
    AppComposeTheme {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column { content() }
        }
    }
}

@Preview(name = "About info", showBackground = true, widthDp = 420, heightDp = 320)
@Composable
fun AboutInfoPreview() {
    InfoPreviewSurface { AboutContent("0.1.0") }
}

@Preview(name = "About info dark", showBackground = true, widthDp = 420, heightDp = 320, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun AboutInfoDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) { AboutContent("0.1.0") }
    }
}

@Preview(name = "Help info", showBackground = true, widthDp = 420, heightDp = 260)
@Composable
fun HelpInfoPreview() {
    InfoPreviewSurface { HelpContent() }
}

@Preview(name = "Help info dark", showBackground = true, widthDp = 420, heightDp = 260, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HelpInfoDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) { HelpContent() }
    }
}

@Preview(name = "More info", showBackground = true, widthDp = 420, heightDp = 220)
@Composable
fun MoreInfoPreview() {
    InfoPreviewSurface { MoreInfoContent() }
}

@Preview(name = "More info dark", showBackground = true, widthDp = 420, heightDp = 220, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun MoreInfoDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) { MoreInfoContent() }
    }
}

@Preview(name = "Licenses info", showBackground = true, widthDp = 420, heightDp = 560)
@Composable
fun LicensesInfoPreview() {
    InfoPreviewSurface {
        LicensesContent("JL-Mod Plus\n\nModifications Copyright (C) 2026 H3NB\nApache License 2.0")
    }
}

@Preview(name = "Licenses info dark", showBackground = true, widthDp = 420, heightDp = 560, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
fun LicensesInfoDarkPreview() {
    AppComposeTheme(darkTheme = true) {
        Surface(color = MaterialTheme.colorScheme.background) {
            LicensesContent("JL-Mod Plus\n\nModifications Copyright (C) 2026 H3NB\nApache License 2.0")
        }
    }
}
