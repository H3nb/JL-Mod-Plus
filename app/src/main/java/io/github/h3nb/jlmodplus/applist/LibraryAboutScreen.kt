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

package io.github.h3nb.jlmodplus.applist

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.BuildConfig
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.ScrollableContentHint
import io.github.h3nb.jlmodplus.ui.rememberScrollCanScrollForward
import android.view.KeyEvent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.Dp
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

/**
 * Full-screen About page reused as a Settings child destination.
 * Licenses remain in the existing adaptive dialog rather than introducing another navigator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryAboutScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onOpenGitHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val canScrollForward = rememberScrollCanScrollForward(scrollState)
    val buildCommit = stringResource(R.string.jlmod_build_commit)
        .takeIf { it != "unknown" && it.isNotBlank() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // The adaptive launcher foreground includes a large transparent safe area.
                    // Crop its canvas inside a fixed frame without modifying the launcher asset.
                    Box(
                        modifier = Modifier.size(128.dp).clipToBounds(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.mipmap.ic_launcher_foreground),
                            contentDescription = null, // The adjacent heading already names the project.
                            modifier = Modifier.matchParentSize().graphicsLayer(
                                scaleX = 1.65f,
                                scaleY = 1.65f,
                            ),
                        )
                    }
                    Text(
                        text = stringResource(R.string.about_product_name),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(R.string.about_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.about_message),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.about_compatibility),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.about_lineage_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.about_lineage),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.about_evolution),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.about_build_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AboutBuildField(R.string.about_version_label, BuildConfig.VERSION_NAME)
                            AboutBuildField(R.string.about_build_label, BuildConfig.VERSION_CODE.toString())
                            if (buildCommit != null) {
                                AboutBuildField(R.string.about_revision_label, buildCommit.take(12))
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.about_links_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column {
                            AboutLinkRow(
                                label = R.string.about_github_label,
                                description = R.string.about_github_repository,
                                icon = R.drawable.ic_github_mark,
                                trailingIcon = R.drawable.ic_open_external,
                                onClick = onOpenGitHub,
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            AboutLinkRow(
                                label = R.string.licenses,
                                description = R.string.library_action_licenses_summary,
                                icon = R.drawable.ic_license,
                                trailingIcon = R.drawable.ic_chevron_right,
                                onClick = onLicenses,
                            )
                        }
                    }
                }
            }
            ScrollableContentHint(
                visible = canScrollForward,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun AboutBuildField(label: Int, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AboutLinkRow(
    label: Int,
    description: Int,
    icon: Int,
    trailingIcon: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painter = painterResource(trailingIcon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal enum class LibraryInfoDialog {
    Licenses,
    Donation,
}

@Composable
internal fun LibraryInformationDialog(
    dialog: LibraryInfoDialog,
    onControllerKeyEvent: ((KeyEvent) -> Boolean)? = null,
    onDismiss: () -> Unit,
    onDonate: () -> Unit = {},
) {
    val context = LocalContext.current
    val title = when (dialog) {
        LibraryInfoDialog.Licenses -> stringResource(R.string.licenses)
        LibraryInfoDialog.Donation -> stringResource(R.string.library_donation_title)
    }
    val icon = when (dialog) {
        LibraryInfoDialog.Licenses -> null
        LibraryInfoDialog.Donation -> R.drawable.ic_favorite
    }
    val maxMessageHeight = adaptiveDialogLayout().maxHeight
    val linkStyles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary))
    val licenseMessage = if (dialog == LibraryInfoDialog.Licenses) {
        try {
            AnnotatedString.fromHtml(
                context.assets.open("licenses.html").bufferedReader().use { it.readText() },
                linkStyles = linkStyles,
            )
        } catch (_: Exception) {
            AnnotatedString(stringResource(R.string.licenses_unavailable))
        }
    } else {
        null
    }

    AlertDialog(
        textScrollable = false,
        onControllerKeyEvent = onControllerKeyEvent,
        onDismissRequest = onDismiss,
        title = {
            if (dialog == LibraryInfoDialog.Licenses) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title)
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        painter = painterResource(requireNotNull(icon)),
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(title)
                }
            }
        },
        text = {
            when (dialog) {
                LibraryInfoDialog.Donation -> LibraryDonationBody(maxHeight = maxMessageHeight)
                LibraryInfoDialog.Licenses -> {
                    val scrollState = rememberScrollState()
                    val canScrollForward = rememberScrollCanScrollForward(scrollState)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxMessageHeight),
                    ) {
                        Text(
                            text = requireNotNull(licenseMessage),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxMessageHeight)
                                .verticalScroll(scrollState),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ScrollableContentHint(
                            visible = canScrollForward,
                            modifier = Modifier
                                .align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (dialog == LibraryInfoDialog.Donation) {
                Button(onClick = { onDismiss(); onDonate() }) {
                    Text(stringResource(R.string.library_donation_continue))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = if (dialog == LibraryInfoDialog.Donation) {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_donation_not_now)) } }
        } else null,
    )
}

@Composable
private fun LibraryDonationBody(maxHeight: Dp) {
    val scrollState = rememberScrollState()
    val canScrollForward = rememberScrollCanScrollForward(scrollState)
    Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.library_donation_message),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Image(
                painter = painterResource(R.drawable.sociabuzz_wordmark),
                contentDescription = null, // Provider named in the action and supporting text.
                modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 24.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = stringResource(R.string.library_donation_provider_note),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ScrollableContentHint(
            visible = canScrollForward,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
