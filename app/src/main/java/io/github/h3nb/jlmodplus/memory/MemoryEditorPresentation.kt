/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.ui.PreferenceChevron

@Composable
internal fun MemoryEditorNavigationRow(
    icon: Int,
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val emphasis = if (enabled) 1f else 0.38f
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = title }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colors.secondaryContainer,
            contentColor = colors.onSecondaryContainer,
        ) {
            Icon(painterResource(icon), contentDescription = null,
                modifier = Modifier.padding(10.dp).size(24.dp),
                tint = colors.onSecondaryContainer.copy(alpha = emphasis))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurface.copy(alpha = emphasis))
            if (description != null) Text(description,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant.copy(alpha = emphasis))
        }
        PreferenceChevron(enabled)
    }
}

@Composable
internal fun memoryEditorFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
)
