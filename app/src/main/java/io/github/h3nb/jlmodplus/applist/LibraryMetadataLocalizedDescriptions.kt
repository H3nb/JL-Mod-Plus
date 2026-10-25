/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.applist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.os.ConfigurationCompat
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.jar.MidletDescriptionLocalization
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog
import java.util.Locale

/**
 * One expanded language editor at a time. The map contains only user edits, never descriptor text.
 * A blank value removes an override and restores the descriptor (or the default fallback).
 */
@Composable
internal fun LibraryMetadataLocalizedDescriptions(
    source: Map<String, String>,
    overrides: Map<String, String>,
    onChange: (Map<String, String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val displayLocale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    val keys = remember(source, overrides, pending) {
        (source.keys + overrides.keys + listOfNotNull(pending)).distinct()
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.library_metadata_other_languages),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            TextButton(onClick = { showPicker = true }) {
                Text(stringResource(R.string.library_metadata_add_language))
            }
        }
        keys.forEach { locale ->
            val name = remember(locale, displayLocale) {
                Locale.forLanguageTag(locale).getDisplayName(displayLocale)
            }
            val text = overrides[locale] ?: source[locale].orEmpty()
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().clickable {
                        expanded = if (expanded == locale) null else locale
                    }.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text("$name · $locale", style = MaterialTheme.typography.bodyLarge)
                    if (text.isNotBlank() && expanded != locale) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (expanded == locale) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { value ->
                            // Keep even an empty in-progress edit visible. Normalization happens
                            // when saving, not during typing; otherwise clearing a source string
                            // instantly restores it and prevents replacing the entire text.
                            onChange(if (value == source[locale]) overrides - locale
                                else overrides + (locale to value))
                            pending = null
                        },
                        label = { Text("$name · $locale") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (overrides.containsKey(locale) || pending == locale) {
                        TextButton(onClick = {
                            onChange(overrides - locale)
                            pending = null
                            if (!source.containsKey(locale)) expanded = null
                        }) {
                            Text(stringResource(if (source.containsKey(locale))
                                R.string.library_metadata_reset_to_original
                                else R.string.library_metadata_remove_language))
                        }
                    }
                }
            }
        }
    }
    if (showPicker) {
        LibraryDescriptionLanguagePicker(
            excluded = source.keys + overrides.keys + listOfNotNull(pending),
            displayLocale = displayLocale,
            onDismiss = { showPicker = false },
            onSelect = { locale ->
                pending = if (locale !in source && locale !in overrides) locale else null
                expanded = locale
                showPicker = false
            },
        )
    }
}

/** MIDP accepts two-letter languages, optionally an upper-case region and variants. */
internal fun canonicalMidpDescriptionLocale(input: String): String? {
    val parts = input.trim().replace('_', '-').split('-')
    if (parts.isEmpty()) return null
    val normalized = buildString {
        append(parts[0].lowercase(Locale.ROOT))
        if (parts.size > 1) append('-').append(parts[1].uppercase(Locale.ROOT))
        for (i in 2 until parts.size) append('-').append(parts[i])
    }
    return normalized.takeIf(MidletDescriptionLocalization::isValidLocale)
}

@Composable
private fun LibraryDescriptionLanguagePicker(
    excluded: Collection<String>,
    displayLocale: Locale,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val locales = remember(displayLocale) {
        Locale.getISOLanguages()
            .filter { it.length == 2 && MidletDescriptionLocalization.isValidLocale(it) }
            .sortedBy { Locale.forLanguageTag(it).getDisplayName(displayLocale).lowercase(displayLocale) }
    }
    val typed = canonicalMidpDescriptionLocale(query)
    val results = remember(query, displayLocale, excluded) {
        locales.filter { code ->
            code !in excluded && (code.contains(query.trim(), ignoreCase = true) ||
                Locale.forLanguageTag(code).getDisplayName(displayLocale)
                    .contains(query.trim(), ignoreCase = true))
        }
    }
    AdaptiveAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_metadata_add_language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.library_metadata_language_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Region-specific codes and descriptor variants can be entered directly.
                if (typed != null && typed !in excluded && typed !in locales) {
                    TextButton(onClick = { onSelect(typed) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${Locale.forLanguageTag(typed).getDisplayName(displayLocale)} · $typed")
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                    items(results, key = { it }) { locale ->
                        Text(
                            text = "${Locale.forLanguageTag(locale).getDisplayName(displayLocale)} · $locale",
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(locale) }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
