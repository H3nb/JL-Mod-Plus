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

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.input.HostCommand
import io.github.h3nb.jlmodplus.librarydb.LibraryBundleComponents
import io.github.h3nb.jlmodplus.librarydb.LibraryIconDraft
import io.github.h3nb.jlmodplus.librarydb.LibraryLocalizedDescriptionEditorData
import io.github.h3nb.jlmodplus.librarydb.LibraryMidletDetailsData
import io.github.h3nb.jlmodplus.librarydb.LibraryQuickView
import io.github.h3nb.jlmodplus.librarydb.LibraryViewModel
import io.github.h3nb.jlmodplus.ui.AdaptiveAlertDialog as AlertDialog
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.TransientNoticeHost
import io.github.h3nb.jlmodplus.ui.TransientNoticeState

data class LibraryUiState(
    val loading: Boolean = true,
    val projectionRevision: Long = 0L,
    val apps: List<LibraryAppUiItem> = emptyList(),
    val appliedFilter: String = "",
    val layout: LibraryLayout = LibraryLayout.List,
    val iconRatio: LibraryIconRatio = LibraryIconRatio.Square,
    val iconShape: LibraryIconShape = LibraryIconShape.Round,
    val enhancedIcons: Boolean = true,
    val hideGridTitles: Boolean = false,
    val gridSpacing: LibraryGridSpacing = LibraryGridSpacing.Standard,
    val gridIconSize: LibraryGridIconSize = LibraryGridIconSize.Standard,
    val sortVariant: Int = 0,
    val quickView: LibraryQuickView = LibraryQuickView.All,
    val databaseControlsReady: Boolean = false,
    val canAddShortcut: Boolean = true,
    val loadingCompleted: Int = 0,
    val loadingTotal: Int = 0,
    val loadingStorageKey: String = "",
    val errorMessage: String? = null,
    val generation: Long = 0L,
    val availableAppIds: Set<Long> = apps.mapTo(LinkedHashSet()) { it.databaseId },
    val projectedAppIds: List<Long> = apps.map(LibraryAppUiItem::databaseId),
    /** Stable identity of the active Library workdir; database ids are only meaningful inside it. */
    val libraryScope: String = "",
)

interface LibraryActions {
    fun onSearch(query: String)
    fun onQuickView(quickView: LibraryQuickView) = Unit
    fun onFavorite(appId: Int, favorite: Boolean) = Unit
    fun onLoadMidletDetails(
        appId: Int,
        callback: LibraryViewModel.MutationCallback<LibraryMidletDetailsData>,
    ) = callback.complete(null, IllegalStateException("MIDlet details are unavailable"))

    fun onLoadLocalizedDescriptions(
        appId: Int,
        callback: LibraryViewModel.MutationCallback<LibraryLocalizedDescriptionEditorData>,
    ) = callback.complete(null, IllegalStateException("Localized descriptions are unavailable"))

    fun onUpdateMetadata(
        appId: Int,
        title: String,
        vendor: String,
        version: String,
        description: String,
        localizedOverrides: Map<String, String>,
        mainIcon: LibraryIconDraft?,
        portraitIcon: LibraryIconDraft?,
        callback: LibraryViewModel.MutationCallback<Unit>,
    ) = callback.complete(null, IllegalStateException("Metadata saving is unavailable"))

    fun onPickIconByDatabaseId(
        databaseId: Long, portrait: Boolean, callback: LibraryViewModel.MutationCallback<String>,
    ) = callback.complete(null, IllegalStateException("Icon picker is unavailable"))
    fun onPrepareOriginalIcon(
        databaseId: Long, callback: LibraryViewModel.MutationCallback<String>,
    ) = callback.complete(null, IllegalStateException("Original icon is unavailable"))
    fun onLayoutChange(layout: LibraryLayout)
    fun onIconRatioChange(iconRatio: LibraryIconRatio) = Unit
    fun onIconShapeChange(iconShape: LibraryIconShape) = Unit
    fun onEnhancedIconsChange(enabled: Boolean) = Unit
    fun onHideGridTitlesChange(hide: Boolean) = Unit
    fun onGridSpacingChange(spacing: LibraryGridSpacing) = Unit
    fun onSort(sortIndex: Int)
    fun onInstall()
    fun onInstallFolder() = Unit
    fun onImportAppBundle() = Unit
    fun onOpenApp(appId: Int)
    /** Resolve a Room ID to the current host ID only when an all-apps search action is invoked. */
    fun resolveAppUiId(databaseId: Long): Int? = null
    fun onAddShortcut(appId: Int)
    fun onRename(appId: Int, title: String)
    fun onOpenAppSettings(appId: Int)
    fun onExportAppBundle(appId: Int) = Unit
    fun onReinstall(appId: Int)
    fun onDelete(appId: Int)
    fun onOpenSettings()
    fun onOpenUpdates() = onOpenSettings()
    fun onOpenProfiles()
    fun onOpenCrashReports()
    fun onOpenDonation() = Unit
    fun onOpenProjectRepository() = Unit
    fun onOpenUpdateRelease(url: String): Boolean = false
    fun onOpenUpdateDownload(url: String): Boolean = false
    fun onRetryLibrary()
}

/** A Java-callable selection callback without requiring java.util.function on API 23. */
fun interface LibraryBundleExportHandler {
    fun onExport(components: LibraryBundleComponents)
}

class LibraryComposeController(
    composeView: ComposeView,
    private val actions: LibraryActions,
    initialLayout: LibraryLayout,
    initialSortVariant: Int,
    initialIconRatio: LibraryIconRatio,
    initialIconShape: LibraryIconShape,
    initialEnhancedIcons: Boolean,
    initialHideGridTitles: Boolean,
    initialGridSpacing: LibraryGridSpacing,
    initialGridIconSize: LibraryGridIconSize,
    canAddShortcut: Boolean,
    private val controllerKeyEventHandler: LibraryControllerKeyEventHandler? = null,
) {
    private val noticeState = TransientNoticeState()
    private val controllerEventHub = LibraryControllerEventHub()
    @Volatile
    private var controllerBackAvailable = false
    private var controllerEventSequence = 0L
    private var nextProjectionRevision = 0L
    private var pendingBundleExport by mutableStateOf<LibraryBundleExportHandler?>(null)
    private var pendingJarExport by mutableStateOf<Runnable?>(null)
    private var pendingJarExportIsBulk by mutableStateOf(false)
    private var state by mutableStateOf(
        LibraryUiState(
            layout = initialLayout,
            iconRatio = initialIconRatio,
            iconShape = initialIconShape,
            enhancedIcons = initialEnhancedIcons,
            hideGridTitles = initialHideGridTitles,
            gridSpacing = initialGridSpacing,
            gridIconSize = initialGridIconSize,
            sortVariant = initialSortVariant,
            canAddShortcut = canAddShortcut,
        ),
    )

    init {
        composeView.id = R.id.library_compose_root
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            JLModPlusTheme {
                LibraryScreen(
                    state = state,
                    actions = actions,
                    controllerEvents = controllerEventHub.flow,
                    controllerKeyEventHandler = controllerKeyEventHandler,
                    onControllerBackAvailabilityChanged = { available ->
                        controllerBackAvailable = available
                    },
                    noticeHost = { TransientNoticeHost(state = noticeState) },
                )
                pendingBundleExport?.let { request ->
                    BundleComponentsDialog(
                        onDismiss = {
                            pendingBundleExport = null
                            pendingJarExport = null
                        },
                        onConfirm = { options ->
                            pendingBundleExport = null
                            pendingJarExport = null
                            request.onExport(options)
                        },
                        onJarOnly = pendingJarExport?.let { runnable ->
                            {
                                pendingBundleExport = null
                                pendingJarExport = null
                                runnable.run()
                            }
                        },
                        jarOnlyIsBulk = pendingJarExportIsBulk,
                    )
                }
            }
        }
    }

    fun showBundleOptions(
        onExport: LibraryBundleExportHandler,
        onJarOnly: Runnable,
        jarOnlyIsBulk: Boolean,
    ) {
        pendingJarExport = onJarOnly
        pendingJarExportIsBulk = jarOnlyIsBulk
        pendingBundleExport = onExport
    }

    fun close() {
        controllerEventHub.close()
    }

    fun updateApps(
        items: List<LibraryAppUiItem>,
        appliedFilter: String,
        quickView: LibraryQuickView,
        sortVariant: Int,
        generation: Long,
        availableAppIds: Set<Long>,
        projectedAppIds: List<Long> = items.map(LibraryAppUiItem::databaseId),
        libraryScope: String = state.libraryScope,
    ) {
        state = state.copy(
            loading = false,
            projectionRevision = ++nextProjectionRevision,
            apps = items,
            availableAppIds = availableAppIds,
            projectedAppIds = projectedAppIds,
            appliedFilter = appliedFilter,
            quickView = quickView,
            sortVariant = sortVariant,
            databaseControlsReady = true,
            loadingCompleted = 0,
            loadingTotal = 0,
            loadingStorageKey = "",
            errorMessage = null,
            generation = generation,
            libraryScope = libraryScope,
        )
    }

    fun showLoading() {
        state = state.copy(
            loading = true,
            apps = emptyList(),
            databaseControlsReady = false,
            loadingCompleted = 0,
            loadingTotal = 0,
            loadingStorageKey = "",
            errorMessage = null,
        )
    }

    fun showIndexing(completed: Int, total: Int, storageKey: String) {
        state = state.copy(
            loading = true,
            apps = emptyList(),
            databaseControlsReady = false,
            loadingCompleted = completed,
            loadingTotal = total,
            loadingStorageKey = storageKey,
            errorMessage = null,
        )
    }

    fun showError(message: String) {
        state = state.copy(
            loading = false,
            apps = emptyList(),
            databaseControlsReady = false,
            errorMessage = message,
        )
    }

    fun updateAppearance(
        layout: LibraryLayout,
        iconRatio: LibraryIconRatio,
        iconShape: LibraryIconShape,
        enhancedIcons: Boolean,
        hideGridTitles: Boolean,
        gridSpacing: LibraryGridSpacing,
        gridIconSize: LibraryGridIconSize,
    ) {
        state = state.copy(
            layout = layout,
            iconRatio = iconRatio,
            iconShape = iconShape,
            enhancedIcons = enhancedIcons,
            hideGridTitles = hideGridTitles,
            gridSpacing = gridSpacing,
            gridIconSize = gridIconSize,
        )
    }

    fun updateSort(sortVariant: Int) {
        state = state.copy(sortVariant = sortVariant)
    }

    fun showNotice(message: String) {
        noticeState.show(message)
    }

    /** Host navigation enters the library without passing through MIDP/Canvas key codes. */
    fun onHostCommand(command: HostCommand, pressed: Boolean): Boolean {
        if (!pressed) return true
        val libraryCommand = when (command) {
            HostCommand.NavigateUp -> LibraryControllerCommand.MoveUp
            HostCommand.NavigateDown -> LibraryControllerCommand.MoveDown
            HostCommand.NavigateLeft -> LibraryControllerCommand.MoveLeft
            HostCommand.NavigateRight -> LibraryControllerCommand.MoveRight
            HostCommand.Activate -> LibraryControllerCommand.Activate
            HostCommand.Back -> {
                if (!controllerBackAvailable) return false
                LibraryControllerCommand.Back
            }
            HostCommand.OpenMenu -> LibraryControllerCommand.OpenActions
            HostCommand.NextTab -> LibraryControllerCommand.NextTab
            HostCommand.PreviousTab -> LibraryControllerCommand.PreviousTab
            HostCommand.OpenKeypad -> return true
        }
        controllerEventSequence = if (controllerEventSequence == Long.MAX_VALUE) 1L
        else controllerEventSequence + 1L
        controllerEventHub.offer(LibraryControllerEvent(controllerEventSequence, libraryCommand))
        return true
    }

    fun onControllerKeyEvent(event: KeyEvent): Boolean =
        controllerKeyEventHandler?.onControllerKeyEvent(event) == true
}

@Composable
private fun BundleComponentsDialog(
    onDismiss: () -> Unit,
    onConfirm: (LibraryBundleComponents) -> Unit,
    onJarOnly: (() -> Unit)? = null,
    jarOnlyIsBulk: Boolean = false,
) {
    var config by remember { mutableStateOf(false) }
    var saveData by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_bundle_components_title)) },
        text = {
            Column {
                Text(stringResource(R.string.library_bundle_components_explanation))
                listOf(
                    Triple(R.string.library_bundle_config, config) { checked: Boolean -> config = checked },
                    Triple(R.string.library_bundle_save_data, saveData) { checked: Boolean -> saveData = checked },
                    Triple(R.string.library_bundle_saved_variables, saved) { checked: Boolean -> saved = checked },
                ).forEach { (label, checked, toggle) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .toggleable(value = checked, onValueChange = toggle)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(stringResource(label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (onJarOnly != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onJarOnly,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file_download),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(
                            if (jarOnlyIsBulk) R.string.library_export_jars_only
                            else R.string.library_export_jar_only,
                        ))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(LibraryBundleComponents(config, saveData, saved))
            }) { Text(stringResource(R.string.library_bundle_export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
