/*
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

package ru.playsoftware.j2meloader.crashes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.ui.JLModPlusTheme
import java.text.DateFormat
import java.util.Date

/** Java-callable event boundary; data/repository ownership stays in the Activity. */
interface CrashReportsActions {
    fun onBack()
    fun onOpen(reportId: String)
    fun onCopySelected(reportIds: List<String>)
    fun onShareSelected(reportIds: List<String>)
    fun onDeleteSelected(reportIds: List<String>)
}

/** Java-callable event boundary for the detail screen. */
interface CrashReportDetailsActions {
    fun onBack()
    fun onCopy()
    fun onShare()
    fun onDelete()
}

data class CrashReportListItem(
    val id: String,
    val title: String,
    val subtitle: String,
)

data class CrashReportDetailState(
    val displayText: String,
)

data class CrashReportsListState(
    val loading: Boolean,
    val records: List<CrashReportListItem>,
)

enum class CrashReportConfirmation {
    Share,
    Delete,
}

/** Keeps list data observable without reinstalling the Activity's Compose content. */
class CrashReportsListController internal constructor(
    private val context: android.content.Context,
) {
    var state by mutableStateOf(
        CrashReportsListState(loading = true, records = emptyList()),
    )
        private set

    fun update(records: List<LocalDiagnosticRepository.Record>) {
        state = CrashReportsListState(
            loading = false,
            records = records.map { it.toComposeListItem(context) },
        )
    }
}

private fun LocalDiagnosticRepository.Record.toComposeListItem(
    context: android.content.Context,
): CrashReportListItem {
    val type = context.getString(
        when (kind) {
            LocalDiagnosticRepository.Kind.MIDLET_FAILURE -> R.string.crash_report_midlet_failure
            LocalDiagnosticRepository.Kind.JAVA_REPORT -> R.string.crash_report_java_report
            LocalDiagnosticRepository.Kind.PROCESS_EXIT -> R.string.crash_report_process_exit
        },
    )
    val time = if (timestampMillis > 0) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timestampMillis))
    } else {
        ""
    }
    return CrashReportListItem(
        id = id,
        title = midletName?.takeIf { it.isNotBlank() } ?: type,
        subtitle = if (time.isEmpty()) type else context.getString(
            R.string.crash_report_list_subtitle,
            type,
            time,
        ),
    )
}

private val NoWindowInsets = WindowInsets(left = 0, top = 0, right = 0, bottom = 0)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CrashReportsScreen(
    state: CrashReportsListState,
    actions: CrashReportsActions,
) {
    val loadingDescription = stringResource(R.string.crash_reports_loading)
    var selectedIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var confirmation by rememberSaveable { mutableStateOf<CrashReportConfirmation?>(null) }

    LaunchedEffect(state.records) {
        val retainedIds = state.records.mapTo(HashSet()) { it.id }
        val retainedSelection = ArrayList(selectedIds.filter { it in retainedIds })
        if (retainedSelection != selectedIds) {
            selectedIds = retainedSelection
        }
    }

    val selectionMode = selectedIds.isNotEmpty()
    fun toggleSelection(reportId: String) {
        val updated = ArrayList(selectedIds)
        if (!updated.remove(reportId)) {
            updated.add(reportId)
        }
        selectedIds = updated
    }

    Scaffold(
        contentWindowInsets = NoWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoWindowInsets,
                title = {
                    Text(
                        text = if (selectionMode) {
                            stringResource(R.string.crash_reports_selected_count, selectedIds.size)
                        } else {
                            stringResource(R.string.crash_reports)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectionMode) {
                                confirmation = null
                                selectedIds = arrayListOf()
                            } else {
                                actions.onBack()
                            }
                        },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(onClick = { actions.onCopySelected(selectedIds.toList()) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_copy),
                                contentDescription = stringResource(R.string.copy_selected_reports),
                            )
                        }
                        IconButton(onClick = { confirmation = CrashReportConfirmation.Share }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_share),
                                contentDescription = stringResource(R.string.share_selected_reports),
                            )
                        }
                        IconButton(onClick = { confirmation = CrashReportConfirmation.Delete }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete_report),
                                contentDescription = stringResource(R.string.delete_selected_reports),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .semantics {
                        contentDescription = loadingDescription
                    },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier,
                )
            }
        } else if (state.records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.crash_reports_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                items(state.records, key = { it.id }) { record ->
                    val selected = record.id in selectedIds
                    ListItem(
                        headlineContent = { Text(record.title) },
                        supportingContent = { Text(record.subtitle) },
                        trailingContent = if (selectionMode) {
                            {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { toggleSelection(record.id) },
                                )
                            }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) {
                                        toggleSelection(record.id)
                                    } else {
                                        actions.onOpen(record.id)
                                    }
                                },
                                onLongClick = {
                                    if (!selected) {
                                        toggleSelection(record.id)
                                    }
                                },
                            ),
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirmation?.let { pendingConfirmation ->
        CrashReportConfirmationDialog(
            confirmation = pendingConfirmation,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                when (pendingConfirmation) {
                    CrashReportConfirmation.Share -> actions.onShareSelected(selectedIds.toList())
                    CrashReportConfirmation.Delete -> actions.onDeleteSelected(selectedIds.toList())
                }
            },
            batchSelection = true,
            reportCount = selectedIds.size,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashReportDetailsScreen(
    state: CrashReportDetailState,
    actions: CrashReportDetailsActions,
) {
    var confirmation by rememberSaveable { mutableStateOf<CrashReportConfirmation?>(null) }

    Scaffold(
        contentWindowInsets = NoWindowInsets,
        topBar = {
            TopAppBar(
                windowInsets = NoWindowInsets,
                title = {
                    Text(
                        text = stringResource(R.string.crash_reports),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = actions::onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.close),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = actions::onCopy) {
                        Icon(
                            painter = painterResource(R.drawable.ic_content_copy),
                            contentDescription = stringResource(R.string.copy_report),
                        )
                    }
                    IconButton(onClick = { confirmation = CrashReportConfirmation.Share }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.share_report),
                        )
                    }
                    IconButton(onClick = { confirmation = CrashReportConfirmation.Delete }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_report),
                            contentDescription = stringResource(R.string.delete_report),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    text = state.displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }

    confirmation?.let { pendingConfirmation ->
        CrashReportConfirmationDialog(
            confirmation = pendingConfirmation,
            onDismiss = { confirmation = null },
            onConfirm = {
                confirmation = null
                when (pendingConfirmation) {
                    CrashReportConfirmation.Share -> actions.onShare()
                    CrashReportConfirmation.Delete -> actions.onDelete()
                }
            },
        )
    }
}

@Composable
fun CrashReportConfirmationDialog(
    confirmation: CrashReportConfirmation,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    batchSelection: Boolean = false,
    reportCount: Int = 1,
) {
    when (confirmation) {
        CrashReportConfirmation.Share -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (batchSelection) {
                        stringResource(R.string.crash_reports_batch_share_title, reportCount)
                    } else {
                        stringResource(R.string.share_report)
                    },
                )
            },
            text = {
                Text(
                    stringResource(
                        if (batchSelection) {
                            R.string.crash_reports_batch_share_disclosure
                        } else {
                            R.string.crash_report_share_disclosure
                        },
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(
                            if (batchSelection) {
                                R.string.share_selected_reports
                            } else {
                                R.string.share_report
                            },
                        ),
                    )
                }
            },
        )

        CrashReportConfirmation.Delete -> AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (batchSelection) {
                        stringResource(R.string.crash_reports_batch_delete_title, reportCount)
                    } else {
                        stringResource(R.string.crash_report_delete_title)
                    },
                )
            },
            text = {
                Text(
                    stringResource(
                        if (batchSelection) {
                            R.string.crash_reports_batch_delete_message
                        } else {
                            R.string.crash_report_delete_message
                        },
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        stringResource(
                            if (batchSelection) {
                                R.string.delete_selected_reports
                            } else {
                                R.string.delete_report
                            },
                        ),
                    )
                }
            },
        )
    }
}

/** Feature-local bridge used by the existing Java Activities; it owns no domain state. */
object CrashReportsComposeBridge {
    @JvmStatic
    fun installList(
        view: ComposeView,
        actions: CrashReportsActions,
    ): CrashReportsListController {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        val controller = CrashReportsListController(view.context)
        view.setContent {
            JLModPlusTheme {
                CrashReportsScreen(
                    state = controller.state,
                    actions = actions,
                )
            }
        }
        return controller
    }

    @JvmStatic
    fun installDetails(
        view: ComposeView,
        displayText: String,
        actions: CrashReportDetailsActions,
    ) {
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        view.setContent {
            JLModPlusTheme {
                CrashReportDetailsScreen(
                    state = CrashReportDetailState(displayText),
                    actions = actions,
                )
            }
        }
    }
}
