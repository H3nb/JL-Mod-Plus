/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

package io.github.h3nb.jlmodplus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R

internal enum class PreferenceMessageLevel { Info, Warning, Danger }

/** Title and current value are the scan path; supporting explanations follow them. */
@Composable
internal fun PreferenceTextContent(
    title: String,
    value: String? = null,
    description: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val disabled = colors.onSurface.copy(alpha = 0.38f)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> disabled
                destructive -> colors.error
                else -> colors.onSurface
            },
        )
        if (!value.isNullOrBlank()) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) colors.onSurface else disabled,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!description.isNullOrBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) colors.onSurfaceVariant else disabled,
            )
        }
    }
}

/** Neutral explanation or persistent, themed warning; never a decorative badge. */
@Composable
internal fun PreferenceNotice(
    message: String,
    level: PreferenceMessageLevel = PreferenceMessageLevel.Info,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = when (level) {
        PreferenceMessageLevel.Info -> colors.onSurfaceVariant
        PreferenceMessageLevel.Warning -> colors.onTertiaryContainer
        PreferenceMessageLevel.Danger -> colors.onErrorContainer
    }
    val background = when (level) {
        PreferenceMessageLevel.Info -> Color.Transparent
        PreferenceMessageLevel.Warning -> colors.tertiaryContainer
        PreferenceMessageLevel.Danger -> colors.errorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = background,
        contentColor = foreground,
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (level == PreferenceMessageLevel.Info) 0.dp else 10.dp,
                vertical = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(
                    if (level == PreferenceMessageLevel.Info) R.drawable.ic_info else R.drawable.ic_warning,
                ),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
