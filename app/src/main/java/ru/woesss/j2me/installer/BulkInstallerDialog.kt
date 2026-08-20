/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import ru.playsoftware.j2meloader.R
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

class BulkInstallerDialog : DialogFragment() {
    private lateinit var libraryViewModel: LibraryViewModel
    private lateinit var bulkViewModel: BulkInstallViewModel

    override fun onAttach(context: Context) {
        super.onAttach(context)
        libraryViewModel = ViewModelProvider(requireActivity())[LibraryViewModel::class.java]
        bulkViewModel = ViewModelProvider(this)[BulkInstallViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        if (bulkViewModel.state.value is BulkInstallViewModel.State.Idle) {
            val args = requireArguments()
            bulkViewModel.planExplicit(
                args.getStringArrayList(ARG_SOURCES).orEmpty(),
                libraryViewModel,
                args.getInt(ARG_OMITTED_SOURCES).takeIf { it > 0 }?.let { omitted ->
                    getString(R.string.bulk_install_omitted_sources, omitted)
                },
            )
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val compose = ComposeView(requireContext()).apply {
            setContent {
                JLModPlusTheme {
                    val state by bulkViewModel.state.collectAsState()
                    BulkInstallSurface(
                        state = state,
                        onToggle = bulkViewModel::toggle,
                        onRecommended = bulkViewModel::selectRecommended,
                        onClear = bulkViewModel::clearSelection,
                        onInstall = { bulkViewModel.execute(libraryViewModel) },
                        onCancel = bulkViewModel::cancel,
                        onClose = {
                            bulkViewModel.cancelPlanning()
                            dismissAllowingStateLoss()
                        },
                    )
                }
            }
        }
        return Dialog(requireContext(), theme).apply {
            setContentView(compose)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val metrics = resources.displayMetrics
        val margin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, metrics).toInt()
        val maxWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 720f, metrics).toInt()
        val width = minOf(maxWidth, metrics.widthPixels - margin)
        window.setLayout(width.coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT)
    }

    companion object {
        private const val ARG_SOURCES = "BulkInstallerDialog.sources"
        private const val ARG_OMITTED_SOURCES = "BulkInstallerDialog.omittedSources"
        private const val MAX_EXPLICIT_SOURCES = 500
        const val TAG = "BulkInstallerDialog"

        @JvmStatic
        fun newFiles(uris: List<Uri>): BulkInstallerDialog = BulkInstallerDialog().apply {
            val distinct = uris.distinctBy(Uri::toString)
            val bounded = distinct.take(MAX_EXPLICIT_SOURCES)
            arguments = Bundle().apply {
                putStringArrayList(ARG_SOURCES, ArrayList(bounded.map(Uri::toString)))
                putInt(ARG_OMITTED_SOURCES, distinct.size - bounded.size)
            }
        }
    }
}

@Composable
private fun BulkInstallSurface(
    state: BulkInstallViewModel.State,
    onToggle: (String) -> Unit,
    onRecommended: () -> Unit,
    onClear: () -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp - 48)
        .coerceIn(240, 760)
        .dp
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = maxHeight)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.bulk_install_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
            )
            when (state) {
                BulkInstallViewModel.State.Idle -> PlanningContent(onClose = onClose)
                is BulkInstallViewModel.State.Planning -> PlanningContent(state.sourceLabel, onClose)
                is BulkInstallViewModel.State.Review -> ReviewContent(
                    plan = state.plan,
                    onToggle = onToggle,
                    onRecommended = onRecommended,
                    onClear = onClear,
                    onInstall = onInstall,
                    onClose = onClose,
                    modifier = Modifier.weight(1f, fill = false),
                )
                is BulkInstallViewModel.State.Running -> RunningContent(state, onCancel)
                is BulkInstallViewModel.State.Finished -> FinishedContent(
                    state,
                    onClose,
                    Modifier.weight(1f, fill = false),
                )
                is BulkInstallViewModel.State.Error -> ErrorContent(state.message, onClose)
            }
        }
    }
}

@Composable
private fun PlanningContent(label: String? = null, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.bulk_install_planning))
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.bulk_install_cancel))
        }
    }
}

@Composable
private fun ReviewContent(
    plan: BulkInstallPlan,
    onToggle: (String) -> Unit,
    onRecommended: () -> Unit,
    onClear: () -> Unit,
    onInstall: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.bulk_install_found, plan.items.size))
            Text(
                text = stringResource(R.string.bulk_install_selected, plan.selectedCount),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ReviewStatusSummary(plan)
        if (plan.warnings.isNotEmpty()) {
            Text(
                text = stringResource(R.string.bulk_install_warning_count, plan.warnings.size),
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = plan.warnings.first(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onRecommended) {
                Text(stringResource(R.string.bulk_install_recommended))
            }
            OutlinedButton(onClick = onClear, enabled = plan.selectedCount > 0) {
                Text(stringResource(R.string.bulk_install_clear))
            }
        }
        HorizontalDivider()
        if (plan.items.isEmpty()) {
            Text(
                text = stringResource(R.string.bulk_install_empty),
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(plan.items, key = { it.id }) { item ->
                    BulkItemRow(item, onToggle)
                }
            }
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.bulk_install_close))
            }
            Spacer(Modifier.size(8.dp))
            Button(onClick = onInstall, enabled = plan.selectedCount > 0) {
                Text(stringResource(R.string.bulk_install_install_count, plan.selectedCount))
            }
        }
    }
}

@Composable
private fun ReviewStatusSummary(plan: BulkInstallPlan) {
    val ready = plan.items.count {
        it.status == BulkInstallStatus.New || it.status == BulkInstallStatus.Update
    }
    val skipped = plan.items.count {
        it.status == BulkInstallStatus.AlreadyInstalled || it.status == BulkInstallStatus.Duplicate
    }
    val attention = plan.items.size - ready - skipped
    Text(
        text = stringResource(R.string.bulk_install_review_summary, ready, skipped, attention),
        modifier = Modifier.padding(top = 2.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun BulkItemRow(item: BulkInstallItem, onToggle: (String) -> Unit) {
    val enabled = item.installable
    val rowModifier = if (enabled) {
        Modifier.fillMaxWidth().clickable { onToggle(item.id) }
    } else {
        Modifier.fillMaxWidth().alpha(0.62f)
    }
    Row(
        modifier = rowModifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = item.selected,
            onCheckedChange = if (enabled) ({ onToggle(item.id) }) else null,
            enabled = enabled,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.vendor.isNotBlank() || item.version.isNotBlank()) {
                Text(
                    text = listOf(item.vendor, item.version).filter(String::isNotBlank).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val statusColor = statusColor(item.status)
            Surface(
                shape = MaterialTheme.shapes.small,
                color = statusColor.copy(alpha = 0.12f),
                contentColor = statusColor,
            ) {
                Text(
                    text = statusLabel(item.status),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (item.selected && item.action == BulkInstallAction.InstallSeparateCopy) {
                Text(
                    stringResource(R.string.bulk_install_action_separate_copy),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (item.selected && item.action == BulkInstallAction.InstallJarOnly) {
                Text(
                    stringResource(R.string.bulk_install_action_jar_only),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item.installedVersion?.let {
                Text(
                    stringResource(R.string.bulk_install_installed_version, it),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.bulk_install_source, item.unit.primaryFile.path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RunningContent(state: BulkInstallViewModel.State.Running, onCancel: () -> Unit) {
    val progress = if (state.total == 0) 0f else state.completed.toFloat() / state.total.toFloat()
    val progressLabel = stringResource(
        R.string.bulk_install_running,
        state.completed.coerceAtMost(state.total),
        state.total,
    )
    Text(progressLabel)
    LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
    )
    Text(
        state.currentName,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    ResultCounters(state.results)
    if (state.cancelRequested) {
        Text(
            stringResource(R.string.bulk_install_cancel_requested),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OutlinedButton(onClick = onCancel, enabled = !state.cancelRequested) {
            Text(stringResource(R.string.bulk_install_cancel))
        }
    }
}

@Composable
private fun FinishedContent(
    state: BulkInstallViewModel.State.Finished,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (state.cancelled) stringResource(R.string.bulk_install_cancelled)
            else stringResource(R.string.bulk_install_complete),
            style = MaterialTheme.typography.titleMedium,
        )
        state.fatalError?.let {
            Text(
                stringResource(R.string.bulk_install_fatal, it),
                color = MaterialTheme.colorScheme.error,
            )
        }
        ResultCounters(state.results)
        if (state.results.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.results, key = { it.itemId }) { result ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(result.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            resultLabel(result.kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (result.kind == BulkInstallResultKind.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        result.detail?.let { detail ->
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onClose) {
                Text(stringResource(R.string.bulk_install_close))
            }
        }
    }
}

@Composable
private fun resultLabel(kind: BulkInstallResultKind): String = stringResource(
    when (kind) {
        BulkInstallResultKind.Installed -> R.string.bulk_install_result_installed
        BulkInstallResultKind.Updated -> R.string.bulk_install_result_updated
        BulkInstallResultKind.Reinstalled -> R.string.bulk_install_result_reinstalled
        BulkInstallResultKind.Skipped -> R.string.bulk_install_result_skipped
        BulkInstallResultKind.Failed -> R.string.bulk_install_result_failed
    },
)

@Composable
private fun ErrorContent(message: String, onClose: () -> Unit) {
    Text(message, color = MaterialTheme.colorScheme.error)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClose) {
            Text(stringResource(R.string.bulk_install_close))
        }
    }
}

@Composable
private fun ResultCounters(results: List<BulkInstallResult>) {
    val installed = results.count { it.kind == BulkInstallResultKind.Installed }
    val updated = results.count { it.kind == BulkInstallResultKind.Updated }
    val reinstalled = results.count { it.kind == BulkInstallResultKind.Reinstalled }
    val skipped = results.count { it.kind == BulkInstallResultKind.Skipped }
    val failed = results.count { it.kind == BulkInstallResultKind.Failed }
    Text(
        stringResource(
            R.string.bulk_install_result_summary,
            installed,
            updated,
            reinstalled,
            skipped,
            failed,
        ),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun statusLabel(status: BulkInstallStatus): String = stringResource(
    when (status) {
        BulkInstallStatus.New -> R.string.bulk_install_status_new
        BulkInstallStatus.Update -> R.string.bulk_install_status_update
        BulkInstallStatus.Downgrade -> R.string.bulk_install_status_downgrade
        BulkInstallStatus.AlreadyInstalled -> R.string.bulk_install_status_same
        BulkInstallStatus.ReinstallOrVariant -> R.string.bulk_install_status_reinstall
        BulkInstallStatus.AmbiguousInstalledMatch -> R.string.bulk_install_status_ambiguous
        BulkInstallStatus.JadJarMismatch -> R.string.bulk_install_status_mismatch
        BulkInstallStatus.Duplicate -> R.string.bulk_install_status_duplicate
        BulkInstallStatus.OlderBatchCandidate -> R.string.bulk_install_status_older_batch
        BulkInstallStatus.BatchConflict -> R.string.bulk_install_status_conflict
        BulkInstallStatus.RemoteSourceUnsupported -> R.string.bulk_install_status_remote
        BulkInstallStatus.SourceError -> R.string.bulk_install_status_error
    },
)

@Composable
private fun statusColor(status: BulkInstallStatus) = when (status) {
    BulkInstallStatus.New,
    BulkInstallStatus.Update,
    -> MaterialTheme.colorScheme.primary

    BulkInstallStatus.AlreadyInstalled,
    BulkInstallStatus.Duplicate,
    -> MaterialTheme.colorScheme.onSurfaceVariant

    BulkInstallStatus.Downgrade,
    BulkInstallStatus.ReinstallOrVariant,
    BulkInstallStatus.AmbiguousInstalledMatch,
    BulkInstallStatus.JadJarMismatch,
    BulkInstallStatus.OlderBatchCandidate,
    BulkInstallStatus.BatchConflict,
    BulkInstallStatus.RemoteSourceUnsupported,
    -> MaterialTheme.colorScheme.tertiary

    BulkInstallStatus.SourceError -> MaterialTheme.colorScheme.error
}
