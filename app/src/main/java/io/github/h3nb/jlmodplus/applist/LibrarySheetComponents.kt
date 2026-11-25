/* SPDX-License-Identifier: Apache-2.0 */
package io.github.h3nb.jlmodplus.applist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (LocalInspectionMode.current) {
        Surface(
            color = colors.surfaceContainer,
            contentColor = colors.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(content = content)
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            containerColor = colors.surfaceContainer,
            contentColor = colors.onSurface,
            contentWindowInsets = contentWindowInsets,
            content = content,
        )
    }
}

@Composable
internal fun RowScope.LibrarySheetTile(
    title: String,
    icon: Int,
    onClick: () -> Unit,
    supporting: String? = null,
    selected: Boolean = false,
    focused: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    titleMaxLines: Int = Int.MAX_VALUE,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val background = when {
        destructive -> colors.errorContainer.copy(alpha = 0.4f)
        selected -> colors.secondaryContainer
        else -> colors.surfaceContainerLow
    }
    val foreground = when {
        destructive -> colors.error
        selected -> colors.onSecondaryContainer
        else -> colors.onSurface
    }
    val contentAlpha = if (enabled) 1f else 0.38f

    Row(
        modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 64.dp)
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .semantics { this.selected = selected || focused }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = foreground.copy(alpha = contentAlpha),
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = foreground.copy(alpha = contentAlpha),
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = (if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                        .copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = foreground.copy(alpha = contentAlpha),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
