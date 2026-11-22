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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.ui.availableWindowWidthDp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryGridItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    controllerFocused: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: (LibraryAppUiItem) -> Unit,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
    hideTitle: Boolean,
    gridSpacing: Dp,
    gridIconSize: LibraryGridIconSize,
) {
    val selectionDescription = stringResource(
        R.string.library_selection_checkbox_description,
        app.title,
    )
    val selectionStateDescription = stringResource(
        if (selected) R.string.library_selection_checked
        else R.string.library_selection_unchecked,
    )
    val interactionModifier = if (selectionMode) {
        Modifier.toggleable(
            value = selected,
            role = Role.Checkbox,
            onValueChange = { onToggleSelection(app) },
        )
    } else {
        Modifier.combinedClickable(
            onClick = { onOpenApp(app.id) },
            onLongClickLabel = stringResource(R.string.library_app_more_actions, app.title),
            onLongClick = { onOpenActions(app) },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gridSpacing / 2, vertical = gridSpacing / 2)
            .then(
                if (controllerFocused) {
                    Modifier.testTag("library-controller-focus-indicator")
                } else {
                    Modifier
                },
            )
            .then(
                if (controllerFocused) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(interactionModifier)
            .semantics {
                role = if (selectionMode) Role.Checkbox else Role.Button
                if (selectionMode) {
                    contentDescription = selectionDescription
                    stateDescription = selectionStateDescription
                } else if (hideTitle) {
                    contentDescription = app.title
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            LibraryIconSlot(
                app = app,
                modifier = Modifier.fillMaxWidth(),
                contentSize = null,
                iconRatio = iconRatio,
                iconShape = iconShape,
                enhancedIcons = enhancedIcons,
                maxArtworkSize = gridIconSize.maxArtworkSize,
            )
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .clearAndSetSemantics { },
                )
            }
        }
        if (!hideTitle) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(top = 6.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = app.title,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryListItem(
    app: LibraryAppUiItem,
    onOpenApp: (Int) -> Unit,
    onOpenActions: (LibraryAppUiItem) -> Unit,
    onFavorite: (Int, Boolean) -> Unit,
    controllerFocused: Boolean,
    favoriteEnabled: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: (LibraryAppUiItem) -> Unit,
    iconRatio: LibraryIconRatio,
    iconShape: LibraryIconShape,
    enhancedIcons: Boolean,
) {
    val selectionDescription = stringResource(
        R.string.library_selection_checkbox_description, app.title,
    )
    val selectionStateDescription = stringResource(
        if (selected) R.string.library_selection_checked else R.string.library_selection_unchecked,
    )
    val interactionModifier = if (selectionMode) {
        Modifier.toggleable(
            value = selected,
            role = Role.Checkbox,
            onValueChange = { onToggleSelection(app) },
        )
    } else {
        Modifier.combinedClickable(
            onClick = { onOpenApp(app.id) },
            onLongClickLabel = stringResource(R.string.library_app_more_actions, app.title),
            onLongClick = { onOpenActions(app) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selectionMode && selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .then(
                if (controllerFocused) Modifier.testTag("library-controller-focus-indicator")
                    .border(2.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.large)
                else Modifier
            )
            .then(interactionModifier)
            .semantics {
                role = if (selectionMode) Role.Checkbox else Role.Button
                if (selectionMode) {
                    contentDescription = selectionDescription
                    stateDescription = selectionStateDescription
                }
            }
            .heightIn(min = 80.dp)
            .padding(
                start = 12.dp,
                end = if (selectionMode) 16.dp else 0.dp,
                top = 10.dp,
                bottom = 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryIconSlot(
            app = app,
            modifier = Modifier.width(56.dp),
            contentSize = 52.dp,
            iconRatio = iconRatio,
            iconShape = iconShape,
            enhancedIcons = enhancedIcons,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.library_vendor_version, app.author, app.version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectionMode) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
            )
        } else {
            if (favoriteEnabled && availableWindowWidthDp() >= 360.dp) {
                LibraryFavoriteButton(app, onFavorite)
            }
            IconButton(
                onClick = { onOpenActions(app) },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.library_app_more_actions, app.title),
                )
            }
        }
    }
}

@Composable
private fun LibraryFavoriteButton(
    app: LibraryAppUiItem,
    onFavorite: (Int, Boolean) -> Unit,
) {
    val favoriteActionDescription = stringResource(
        if (app.favorite) R.string.library_remove_from_favorites_action
        else R.string.library_add_to_favorites_action,
    )
    IconButton(
        onClick = { onFavorite(app.id, !app.favorite) },
        modifier = Modifier.size(48.dp),
    ) {
        // Keep the slot dimensions identical for both states. AnimatedVisibility can temporarily
        // measure an exiting/entering child at a different size, which makes a long list reflow
        // when the user only intended to toggle the star.
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (app.favorite) {
                Icon(
                    painter = painterResource(R.drawable.ic_star_filled),
                    contentDescription = favoriteActionDescription,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.ic_star),
                    contentDescription = favoriteActionDescription,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
