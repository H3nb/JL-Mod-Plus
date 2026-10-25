/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.applist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import io.github.h3nb.jlmodplus.librarydb.LibraryIconDraft
import io.github.h3nb.jlmodplus.librarydb.LibraryLocalizedDescriptionEditorData
import io.github.h3nb.jlmodplus.librarydb.LibraryViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private val localizedOverridesSaver = Saver<Map<String, String>, ArrayList<String>>(
    save = { translations ->
        ArrayList(translations.flatMap { (locale, text) -> listOf(locale, text) })
    },
    restore = { values -> values.chunked(2).associate { it[0] to it[1] } },
)

/** Edits presentation metadata; original MIDlet descriptor values remain immutable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryMetadataEditorScreen(
    app: LibraryAppUiItem,
    onBack: () -> Unit,
    backRequest: Int = 0,
    onLoadDescriptions: (LibraryViewModel.MutationCallback<LibraryLocalizedDescriptionEditorData>) -> Unit = {
        it.complete(LibraryLocalizedDescriptionEditorData(emptyMap(), emptyMap()), null)
    },
    onSaveLocalized: (
        title: String, vendor: String, description: String,
        translations: Map<String, String>,
        mainIcon: LibraryIconDraft?, portraitIcon: LibraryIconDraft?,
        onResult: (Boolean) -> Unit,
    ) -> Unit = { _, _, _, _, _, _, done -> done(false) },
    onPickIcon: (portrait: Boolean, callback: LibraryViewModel.MutationCallback<String>) -> Unit =
        { _, callback -> callback.complete(null, null) },
    onPrepareOriginalIcon: (callback: LibraryViewModel.MutationCallback<String>) -> Unit =
        { callback -> callback.complete(null, null) },
    iconShape: LibraryIconShape = LibraryIconShape.Round,
    enhancedIcons: Boolean = true,
) {
    var title by rememberSaveable(app.databaseId) { mutableStateOf(app.title) }
    var vendor by rememberSaveable(app.databaseId) { mutableStateOf(app.author) }
    var description by rememberSaveable(app.databaseId) { mutableStateOf(app.defaultDescription) }
    var confirmDiscard by rememberSaveable(app.databaseId) { mutableStateOf(false) }
    val mainDraftState = remember(app.databaseId) { mutableStateOf<LibraryIconDraft?>(null) }
    val portraitDraftState = remember(app.databaseId) { mutableStateOf<LibraryIconDraft?>(null) }
    var mainDraft by mainDraftState
    var portraitDraft by portraitDraftState
    val editAlive = remember(app.databaseId) { AtomicBoolean(true) }

    // Draft images live in the app cache only while this editor owns them.
    DisposableEffect(app.databaseId) {
        editAlive.set(true)
        onDispose {
            editAlive.set(false)
            listOfNotNull(mainDraftState.value?.previewPath, portraitDraftState.value?.previewPath)
                .filter(String::isNotEmpty).forEach { File(it).delete() }
        }
    }
    val setIconDraft: (Boolean, LibraryIconDraft?) -> Unit = { portrait, next ->
        val prior = if (portrait) portraitDraft else mainDraft
        if (portrait) portraitDraft = next else mainDraft = next
        if (prior?.previewPath != null && prior.previewPath != next?.previewPath) {
            File(prior.previewPath).delete()
        }
    }
    val chooseIcon: (Boolean) -> Unit = { portrait ->
        onPickIcon(portrait, LibraryViewModel.MutationCallback { path, error ->
            if (!editAlive.get()) {
                if (path != null) File(path).delete()
            } else if (error == null && !path.isNullOrBlank()) {
                setIconDraft(portrait, LibraryIconDraft(reset = false, previewPath = path))
            }
        })
    }
    val resetIcon: (Boolean) -> Unit = { portrait ->
        if (portrait) {
            setIconDraft(true, if (app.portraitIconPath.isNullOrBlank()) null
                else LibraryIconDraft(reset = true))
        } else if (!app.hasCustomIcon) {
            setIconDraft(false, null)
        } else {
            onPrepareOriginalIcon(LibraryViewModel.MutationCallback { path, error ->
                if (!editAlive.get()) {
                    if (!path.isNullOrBlank()) File(path).delete()
                } else if (error == null && path != null) {
                    setIconDraft(false, LibraryIconDraft(reset = true, previewPath = path))
                }
            })
        }
    }
    val previewApp = app.copy(
        iconPath = when {
            mainDraft?.reset == true -> mainDraft?.previewPath?.takeIf(String::isNotEmpty)
            mainDraft != null -> mainDraft?.previewPath
            else -> app.iconPath
        },
        portraitIconPath = when {
            portraitDraft?.reset == true -> null
            portraitDraft != null -> portraitDraft?.previewPath
            else -> app.portraitIconPath
        },
        hasCustomIcon = if (mainDraft != null) !mainDraft!!.reset else app.hasCustomIcon,
        iconRevision = if (mainDraft != null || portraitDraft != null) app.iconRevision + 1L
            else app.iconRevision,
    )
    var draftTranslations by rememberSaveable(app.databaseId, stateSaver = localizedOverridesSaver) {
        mutableStateOf<Map<String, String>>(emptyMap())
    }
    var draftInitialized by rememberSaveable(app.databaseId) { mutableStateOf(false) }
    var sourceTranslations by remember(app.databaseId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var originalOverrides by remember(app.databaseId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var descriptionsLoaded by remember(app.databaseId) { mutableStateOf(false) }
    var loadFailed by remember(app.databaseId) { mutableStateOf(false) }
    var saving by remember(app.databaseId) { mutableStateOf(false) }
    var loadAttempt by remember(app.databaseId) { mutableIntStateOf(0) }
    LaunchedEffect(app.databaseId, loadAttempt) {
        val context = currentCoroutineContext()
        onLoadDescriptions(LibraryViewModel.MutationCallback { data, error ->
            if (context.isActive) {
                if (error == null && data != null) {
                    sourceTranslations = data.source
                    originalOverrides = data.overrides
                    if (!draftInitialized) {
                        draftTranslations = data.overrides
                        draftInitialized = true
                    }
                    descriptionsLoaded = true
                } else {
                    loadFailed = true
                }
            }
        })
        awaitCancellation()
    }
    val changed = title.trim() != app.title ||
        vendor.trim() != app.author || description.trim() != app.defaultDescription ||
        (descriptionsLoaded && draftTranslations != originalOverrides) ||
        mainDraft != null || portraitDraft != null
    val valid = title.trim().isNotEmpty()
    val requestBack = {
        if (!saving) {
            if (changed) confirmDiscard = true else onBack()
        }
    }

    BackHandler(onBack = requestBack)
    LaunchedEffect(backRequest) {
        if (backRequest > 0) requestBack()
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_metadata_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(android.R.string.cancel),
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = valid && changed && descriptionsLoaded && !saving,
                        onClick = {
                            saving = true
                            onSaveLocalized(
                                title.trim(), vendor.trim(), description.trim(),
                                draftTranslations, mainDraft, portraitDraft,
                            ) { success -> if (!success) saving = false }
                        },
                    ) {
                        Text(stringResource(R.string.library_metadata_save))
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            val wide = maxWidth >= 600.dp
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 12.dp),
            ) {
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        LibraryMetadataIconPanel(
                            app = previewApp,
                            onPickIcon = chooseIcon,
                            onResetIcon = resetIcon,
                            iconShape = iconShape,
                            enhancedIcons = enhancedIcons,
                            modifier = Modifier.width(260.dp),
                        )
                        LibraryMetadataFields(
                            title = title,
                            vendor = vendor,
                            description = description,
                            app = app,
                            onTitleChange = { title = it },
                            onVendorChange = { vendor = it },
                            onDescriptionChange = { description = it },
                            sourceTranslations = sourceTranslations,
                            translations = draftTranslations,
                            descriptionsLoaded = descriptionsLoaded,
                            loadFailed = loadFailed,
                            onRetryLoad = {
                                loadFailed = false
                                loadAttempt++
                            },
                            onTranslationsChange = { draftTranslations = it },
                            modifier = Modifier.weight(1f).widthIn(max = 760.dp),
                        )
                    }
                } else {
                    LibraryMetadataIconPanel(
                        app = previewApp,
                        onPickIcon = chooseIcon,
                        onResetIcon = resetIcon,
                        iconShape = iconShape,
                        enhancedIcons = enhancedIcons,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LibraryMetadataFields(
                        title = title,
                        vendor = vendor,
                        description = description,
                        app = app,
                        onTitleChange = { title = it },
                        onVendorChange = { vendor = it },
                        onDescriptionChange = { description = it },
                        sourceTranslations = sourceTranslations,
                        translations = draftTranslations,
                        descriptionsLoaded = descriptionsLoaded,
                        loadFailed = loadFailed,
                        onRetryLoad = {
                            loadFailed = false
                            loadAttempt++
                        },
                        onTranslationsChange = { draftTranslations = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AdaptiveAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.library_metadata_discard_title)) },
            confirmButton = {
                TextButton(
                    enabled = valid && descriptionsLoaded && !saving,
                    onClick = {
                        confirmDiscard = false
                        saving = true
                        onSaveLocalized(
                            title.trim(), vendor.trim(), description.trim(),
                            draftTranslations, mainDraft, portraitDraft,
                        ) { success -> if (!success) saving = false }
                    },
                ) {
                    Text(stringResource(R.string.library_metadata_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = { confirmDiscard = false; onBack() }) {
                    Text(stringResource(R.string.library_metadata_discard))
                }
            },
        )
    }
}

@Composable
private fun LibraryMetadataIconPanel(
    app: LibraryAppUiItem,
    onPickIcon: (Boolean) -> Unit,
    onResetIcon: (Boolean) -> Unit,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.library_metadata_icon),
            style = MaterialTheme.typography.titleMedium,
        )
        LibraryMetadataIconRow(
            app = app,
            portrait = false,
            onPickIcon = { onPickIcon(false) },
            onResetIcon = { onResetIcon(false) },
            iconShape = iconShape,
            enhancedIcons = enhancedIcons,
        )
        LibraryMetadataIconRow(
            app = app,
            portrait = true,
            onPickIcon = { onPickIcon(true) },
            onResetIcon = { onResetIcon(true) },
            iconShape = iconShape,
            enhancedIcons = enhancedIcons,
        )
    }
}

@Composable
private fun LibraryMetadataIconRow(
    app: LibraryAppUiItem,
    portrait: Boolean,
    onPickIcon: () -> Unit,
    onResetIcon: () -> Unit,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
) {
    val hasPortrait = !app.portraitIconPath.isNullOrBlank()
    val hasOverride = if (portrait) hasPortrait else app.hasCustomIcon
    val label = if (portrait) {
        stringResource(R.string.library_metadata_portrait_icon)
    } else {
        stringResource(R.string.library_metadata_main_icon) + " (1:1)"
    }
    val change = stringResource(R.string.library_metadata_change)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 92.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(76.dp).height(96.dp),
            contentAlignment = Alignment.Center,
        ) {
            LibraryIconSlot(
                app = app,
                modifier = Modifier.width(72.dp),
                contentSize = null,
                iconRatio = if (portrait) LibraryIconRatio.Portrait else LibraryIconRatio.Square,
                iconShape = iconShape,
                enhancedIcons = enhancedIcons,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            if (portrait && !hasPortrait) {
                Text(
                    text = stringResource(R.string.library_metadata_follows_main),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = onPickIcon,
                    modifier = Modifier.semantics {
                        contentDescription = "$change $label"
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(change)
                }
                if (hasOverride) {
                    IconButton(onClick = onResetIcon) {
                        Icon(
                            painter = painterResource(
                                R.drawable.ic_refresh,
                            ),
                            contentDescription = stringResource(
                                if (portrait) R.string.library_metadata_remove_portrait
                                else R.string.library_metadata_reset_to_original,
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryMetadataFields(
    title: String,
    vendor: String,
    description: String,
    app: LibraryAppUiItem,
    onTitleChange: (String) -> Unit,
    onVendorChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    sourceTranslations: Map<String, String>,
    translations: Map<String, String>,
    descriptionsLoaded: Boolean,
    loadFailed: Boolean,
    onRetryLoad: () -> Unit,
    onTranslationsChange: (Map<String, String>) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LibraryMetadataField(
            label = R.string.library_metadata_name,
            value = title,
            source = app.sourceTitle,
            onValueChange = onTitleChange,
            singleLine = true,
            required = true,
        )
        LibraryMetadataField(
            label = R.string.library_metadata_vendor,
            value = vendor,
            source = app.sourceAuthor,
            onValueChange = onVendorChange,
            singleLine = true,
        )
        LibraryMetadataField(
            label = R.string.library_metadata_default_description,
            value = description,
            source = app.sourceDescription,
            onValueChange = onDescriptionChange,
            singleLine = false,
        )
        if (descriptionsLoaded) {
            LibraryMetadataLocalizedDescriptions(
                source = sourceTranslations,
                overrides = translations,
                onChange = onTranslationsChange,
            )
        } else if (loadFailed) {
            Text(
                text = stringResource(R.string.library_metadata_language_load_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetryLoad) {
                Text(stringResource(R.string.library_retry))
            }
        }
    }
}

@Composable
private fun LibraryMetadataField(
    label: Int,
    value: String,
    source: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    required: Boolean = false,
) {
    val changed = value.trim() != source
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(label)) },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 8,
            isError = required && value.trim().isEmpty(),
            trailingIcon = if (singleLine && changed) {
                {
                    IconButton(onClick = { onValueChange(source) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.library_metadata_reset_to_original),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else null,
        )
        if (!singleLine && changed) {
            TextButton(
                onClick = { onValueChange(source) },
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.library_metadata_reset_to_original))
            }
        }
    }
}
