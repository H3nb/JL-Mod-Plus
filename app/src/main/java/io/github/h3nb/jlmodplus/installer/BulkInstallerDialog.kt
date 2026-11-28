/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer

import androidx.compose.ui.platform.testTag

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import io.github.h3nb.jlmodplus.MainActivity
import io.github.h3nb.jlmodplus.R
import io.github.h3nb.jlmodplus.librarydb.LibraryViewModel
import io.github.h3nb.jlmodplus.ui.JLModPlusTheme
import io.github.h3nb.jlmodplus.ui.adaptiveDialogLayout

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
            val omittedWarning = args.getInt(ARG_OMITTED_SOURCES).takeIf { it > 0 }?.let { omitted ->
                resources.getQuantityString(
                    R.plurals.bulk_install_omitted_sources,
                    omitted,
                    omitted,
                )
            }
            val bundleUri = args.getString(ARG_BUNDLE_URI)
            val reinstallAppIds = args.getLongArray(ARG_REINSTALL_APP_IDS)
            if (reinstallAppIds != null) {
                bulkViewModel.planReinstall(
                    context = requireContext(),
                    appIds = reinstallAppIds.toList(),
                    library = libraryViewModel,
                    executeImmediately = true,
                )
            } else if (bundleUri != null) {
                bulkViewModel.planUniversalBundle(
                    context = requireContext(),
                    uriString = bundleUri,
                    library = libraryViewModel,
                    warning = omittedWarning,
                )
            } else {
                bulkViewModel.planExplicit(
                    args.getStringArrayList(ARG_SOURCES).orEmpty(),
                    libraryViewModel,
                    omittedWarning,
                )
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val compose = ComposeView(requireContext()).apply {
            setContent {
                JLModPlusTheme {
                    val state by bulkViewModel.state.collectAsState()
                    BulkInstallSurface(
                        state = state,
                        explicitSourceSelection = arguments?.getStringArrayList(ARG_SOURCES) != null,
                        singlePresentation = bulkViewModel.useSingleInstallPresentation,
                        onInstallSingle = { bulkViewModel.executeSingle(libraryViewModel) },
                        onReviewJarOnly = { bulkViewModel.reviewJarOnly(libraryViewModel) },
                        onToggle = bulkViewModel::toggle,
                        onRecommended = bulkViewModel::selectRecommended,
                        onClear = bulkViewModel::clearSelection,
                        onInstall = { bulkViewModel.execute(libraryViewModel) },
                        onRetry = { bulkViewModel.reviewUnfinished(libraryViewModel) },
                        onCancel = bulkViewModel::cancel,
                        onClose = {
                            bulkViewModel.close()
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
            window?.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        val args = arguments
        val requestId = args?.getString(ARG_REQUEST_ID)
        val bundleUri = args?.getString(ARG_BUNDLE_URI)
        val activity = activity
        if (activity is MainActivity) {
            if (requestId != null && bundleUri != null) {
                activity.completeInstallerRequest(requestId, bundleUri.toUri())
            }
            activity.onInstallerDialogDismissed()
        }
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(android.graphics.Color.TRANSPARENT.toDrawable())
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    companion object {
        private const val ARG_SOURCES = "BulkInstallerDialog.sources"
        private const val ARG_OMITTED_SOURCES = "BulkInstallerDialog.omittedSources"
        private const val ARG_BUNDLE_URI = "BulkInstallerDialog.bundleUri"
        private const val ARG_REINSTALL_APP_IDS = "BulkInstallerDialog.reinstallAppIds"
        private const val ARG_REQUEST_ID = "BulkInstallerDialog.requestId"
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

        @JvmStatic
        fun newReinstall(appIds: Collection<Long>): BulkInstallerDialog =
            BulkInstallerDialog().apply {
                arguments = Bundle().apply {
                    putLongArray(ARG_REINSTALL_APP_IDS, appIds.distinct().sorted().toLongArray())
                }
            }

        @JvmStatic
        fun newBundle(uri: Uri, requestId: String? = null): BulkInstallerDialog =
            BulkInstallerDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BUNDLE_URI, uri.toString())
                    requestId?.let { putString(ARG_REQUEST_ID, it) }
                }
            }
    }
}

@Composable
internal fun BulkInstallSurface(
    state: BulkInstallViewModel.State,
    onToggle: (String) -> Unit,
    onRecommended: () -> Unit,
    onClear: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    explicitSourceSelection: Boolean = false,
    singlePresentation: Boolean = false,
    onInstallSingle: () -> Unit = onInstall,
    onReviewJarOnly: () -> Unit = {},
) {
    val showSingle = explicitSourceSelection && singlePresentation
    BackHandler(enabled = state !is BulkInstallViewModel.State.Running) { onClose() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(if (showSingle) Modifier else Modifier.background(MaterialTheme.colorScheme.background))
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        val compactWidth = maxWidth < 360.dp
        val compactHeight = maxHeight < 480.dp
        val compactHeader = compactWidth || compactHeight
        val dialogLayout = adaptiveDialogLayout(maxWidth, maxHeight)
        val surfaceModifier = if (showSingle) dialogLayout.modifier else Modifier.fillMaxSize()
        var selectionMenuVisible by remember { mutableStateOf(false) }

        Surface(
            shape = if (showSingle) MaterialTheme.shapes.extraLarge else RectangleShape,
            color = if (showSingle) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.background,
            tonalElevation = if (showSingle) 6.dp else 0.dp,
            modifier = surfaceModifier,
        ) {
            Column(
                modifier = (if (showSingle) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                    .padding(
                        horizontal = if (compactWidth) 16.dp else 20.dp,
                        vertical = if (compactHeight) 8.dp else 16.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(if (compactHeader) 8.dp else 12.dp),
            ) {
                val titleRes = when {
                    showSingle || (explicitSourceSelection &&
                        (state is BulkInstallViewModel.State.Idle ||
                            state is BulkInstallViewModel.State.Planning ||
                            state is BulkInstallViewModel.State.Error)) ->
                        R.string.installer_title
                    state is BulkInstallViewModel.State.Finished ->
                        if (state.cancelled || state.fatalError != null) {
                            R.string.bulk_install_cancelled
                        } else {
                            R.string.bulk_install_complete
                        }
                    else -> R.string.bulk_install_title
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!showSingle) {
                        IconButton(
                            onClick = onClose,
                            enabled = state !is BulkInstallViewModel.State.Running,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                    Text(
                        text = stringResource(titleRes),
                        modifier = Modifier.weight(1f),
                        style = if (showSingle) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state is BulkInstallViewModel.State.Review && !showSingle) {
                        Box {
                            IconButton(onClick = { selectionMenuVisible = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_more_vert),
                                    contentDescription = stringResource(R.string.bulk_install_selection_options),
                                )
                            }
                            DropdownMenu(
                                expanded = selectionMenuVisible,
                                onDismissRequest = { selectionMenuVisible = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bulk_install_recommended)) },
                                    onClick = {
                                        selectionMenuVisible = false
                                        onRecommended()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.bulk_install_clear)) },
                                    onClick = {
                                        selectionMenuVisible = false
                                        onClear()
                                    },
                                    enabled = state.plan.selectedCount > 0,
                                )
                            }
                        }
                    }
                }
                val bodyModifier = if (showSingle) Modifier.weight(1f, fill = false)
                    else Modifier.weight(1f)
                when (state) {
                    BulkInstallViewModel.State.Idle -> PlanningContent(onClose = onClose)
                    is BulkInstallViewModel.State.Planning -> PlanningContent(state.sourceLabel, onClose)
                    is BulkInstallViewModel.State.Review -> when {
                        showSingle && state.plan.items.isEmpty() -> EmptySingleReviewContent(
                            plan = state.plan,
                            onClose = onClose,
                        )
                        showSingle && state.plan.items.size == 1 -> SingleReviewContent(
                            plan = state.plan,
                            onInstall = onInstallSingle,
                            onReviewJarOnly = onReviewJarOnly,
                            onClose = onClose,
                            modifier = bodyModifier,
                        )
                        else -> ReviewContent(
                            plan = state.plan,
                            onToggle = onToggle,
                            onInstall = onInstall,
                            onClose = onClose,
                            compactHeader = compactHeader,
                            modifier = bodyModifier,
                        )
                    }
                    is BulkInstallViewModel.State.Running -> RunningContent(
                        state = state,
                        onCancel = onCancel,
                        single = showSingle,
                        modifier = bodyModifier,
                    )
                    is BulkInstallViewModel.State.Finished -> FinishedContent(
                        state = state,
                        onClose = onClose,
                        onRetry = onRetry,
                        compactHeight = compactHeight,
                        single = showSingle,
                        modifier = bodyModifier,
                    )
                    is BulkInstallViewModel.State.Error -> ErrorContent(
                        messageRes = state.messageRes,
                        diagnosticDetail = state.diagnosticDetail,
                        onClose = onClose,
                        modifier = bodyModifier,
                    )
                }
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

private enum class ReviewFilter { All, Selected, Attention }


/** A single discovered MIDlet needs one confirmation, not batch filters or checkboxes. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SingleReviewContent(
    plan: BulkInstallPlan,
    onInstall: () -> Unit,
    onReviewJarOnly: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = plan.items.single()
    val action = singleInstallAction(item)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(item.name, style = MaterialTheme.typography.titleMedium)
            val details = listOf(item.vendor, item.version).filter(String::isNotBlank)
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(statusLabel(item), style = MaterialTheme.typography.labelLarge)
            if (item.status == BulkInstallStatus.AlreadyInstalled ||
                item.status == BulkInstallStatus.ReinstallOrVariant) {
                Text(stringResource(R.string.reinstall))
            }
            if (item.status == BulkInstallStatus.Update ||
                item.status == BulkInstallStatus.Downgrade) {
                item.installedVersion?.let { current ->
                    Text(stringResource(
                        if (item.status == BulkInstallStatus.Update) {
                            R.string.reinstall_newest
                        } else {
                            R.string.reinstall_older
                        },
                        item.version,
                        current,
                    ))
                }
            }
            if (item.status.replacesInstalledApp()) {
                Text(
                    stringResource(R.string.installer_existing_data_preserved),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.single_install_running_replacement_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.effectiveReviewReason?.let { reason ->
                Text(
                    bulkInstallReviewReasonText(LocalResources.current, reason),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (plan.warnings.isNotEmpty()) {
                Text(
                    plan.warnings.joinToString("\n"),
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider()
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
            if (action != null) {
                Button(onClick = if (action == BulkInstallAction.InstallJarOnly) {
                    onReviewJarOnly
                } else {
                    onInstall
                }) {
                    Text(stringResource(when (action) {
                        BulkInstallAction.Reinstall -> R.string.action_reinstall
                        BulkInstallAction.InstallSeparateCopy -> R.string.installer_separate_copy
                        BulkInstallAction.InstallJarOnly -> R.string.single_install_review_jar_only
                        else -> R.string.install
                    }))
                }
            }
        }
    }
}

@Composable
private fun EmptySingleReviewContent(plan: BulkInstallPlan, onClose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.bulk_install_empty))
        if (plan.warnings.isNotEmpty()) {
            Text(
                plan.warnings.joinToString("\n"),
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onClose) { Text(stringResource(R.string.close)) }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReviewContent(
    plan: BulkInstallPlan,
    onToggle: (String) -> Unit,
    onInstall: () -> Unit,
    onClose: () -> Unit,
    compactHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var filter by rememberSaveable { mutableStateOf(ReviewFilter.All) }
    val visibleItems = remember(plan.items, filter) {
        plan.items.filter { item ->
            when (filter) {
                ReviewFilter.All -> true
                ReviewFilter.Selected -> item.selected && item.action != BulkInstallAction.Skip
                ReviewFilter.Attention -> item.status !in setOf(
                    BulkInstallStatus.New,
                    BulkInstallStatus.Update,
                    BulkInstallStatus.AlreadyInstalled,
                    BulkInstallStatus.Duplicate,
                )
            }
        }
    }
    LaunchedEffect(filter) { listState.scrollToItem(0) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            pluralStringResource(
                                R.plurals.bulk_install_found,
                                plan.items.size,
                                plan.items.size,
                            ),
                            style = if (compactHeader) MaterialTheme.typography.bodySmall
                                else MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            pluralStringResource(
                                R.plurals.bulk_install_selected,
                                plan.selectedCount,
                                plan.selectedCount,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            style = if (compactHeader) MaterialTheme.typography.bodySmall
                                else MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        listOf(
                            ReviewFilter.All to R.string.bulk_install_filter_all,
                            ReviewFilter.Selected to R.string.bulk_install_filter_selected,
                            ReviewFilter.Attention to R.string.bulk_install_filter_review,
                        ).forEach { (option, label) ->
                            FilterChip(
                                selected = filter == option,
                                onClick = { filter = option },
                                label = { Text(stringResource(label)) },
                            )
                        }
                    }
                    if (plan.warnings.isNotEmpty()) {
                        Text(
                            pluralStringResource(
                                R.plurals.bulk_install_warning_count,
                                plan.warnings.size,
                                plan.warnings.size,
                            ),
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            plan.warnings.first(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    HorizontalDivider()
                }
            }
            if (visibleItems.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            if (plan.items.isEmpty()) R.string.bulk_install_empty
                            else R.string.bulk_install_empty_filter,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visibleItems, key = { it.id }) { item ->
                BulkItemRow(item, onToggle)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        HorizontalDivider()
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onClose) {
                Text(stringResource(R.string.bulk_install_close))
            }
            Button(onClick = onInstall, enabled = plan.selectedCount > 0) {
                Text(stringResource(R.string.bulk_install_install_count, plan.selectedCount))
            }
        }
    }
}

@Composable
private fun BulkItemRow(item: BulkInstallItem, onToggle: (String) -> Unit) {
    val enabled = item.installable
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .toggleable(
                        value = item.selected,
                        enabled = enabled,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(item.id) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(
                    checked = item.selected,
                    onCheckedChange = null,
                    enabled = enabled,
                )
                Column(
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.vendor.isNotBlank() || item.version.isNotBlank()) {
                        Text(
                            listOf(item.vendor, item.version)
                                .filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val statusColor = statusColor(item.status)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = statusColor.copy(alpha = 0.12f),
                        contentColor = statusColor,
                    ) {
                        Text(
                            text = statusLabel(item),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    item.installedVersion?.let {
                        Text(
                            stringResource(R.string.bulk_install_installed_version, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.effectiveReviewReason?.let { reason ->
                        Text(
                            bulkInstallReviewReasonText(LocalResources.current, reason),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = stringResource(
                        if (expanded) R.string.bulk_install_hide_details
                        else R.string.bulk_install_show_details,
                    ),
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 8.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
                Text(
                    stringResource(R.string.bulk_install_source, item.unit.primaryFile.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RunningContent(
    state: BulkInstallViewModel.State.Running,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    single: Boolean = false,
) {
    val progress = if (state.total == 0) 0f else state.completed.toFloat() / state.total.toFloat()
    val completedCount = state.completed.coerceAtMost(state.total)
    val progressLabel = pluralStringResource(
        R.plurals.bulk_install_running,
        completedCount,
        completedCount,
        state.total,
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = !single)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!single) Text(progressLabel)
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
            if (!single) ResultCounters(state.results)
            state.stageLabel?.let { Text(stringResource(it)) }
            if (state.cancelRequested) {
                Text(
                    stringResource(R.string.bulk_install_cancel_requested),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel, enabled = !state.cancelRequested) {
                Text(stringResource(R.string.bulk_install_cancel))
            }
        }
    }
}

@Composable
private fun FinishedContent(
    state: BulkInstallViewModel.State.Finished,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    compactHeight: Boolean,
    modifier: Modifier = Modifier,
    single: Boolean = false,
) {
    val listState = rememberLazyListState()
    val hasRetryableResults = state.results.any {
        it.kind == BulkInstallResultKind.Failed ||
            it.kind == BulkInstallResultKind.PartiallyInstalled ||
            it.kind == BulkInstallResultKind.NotProcessed
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = !single),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag("bulk-results"),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.fatalError != null) {
                    item {
                        Text(stringResource(R.string.bulk_install_fatal),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!single) item { ResultCounters(state.results) }
                items(state.results, key = { it.itemId }) { result ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(result.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            resultLabel(result.kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (
                                result.kind == BulkInstallResultKind.Failed ||
                                result.kind == BulkInstallResultKind.PartiallyInstalled
                            ) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        result.warning?.let {
                            Text(
                                stringResource(R.string.warning),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val detail = result.reviewReason?.let {
                            bulkInstallReviewReasonText(LocalResources.current, it)
                        } ?: result.detail
                        detail?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        result.diagnosticDetail?.let { InstallerDiagnosticDetails(it) }
                    }
                    HorizontalDivider()
                }
                if (compactHeight && single) {
                    item {
                        FinishedActions(
                            hasRetryableResults = hasRetryableResults,
                            onRetry = onRetry,
                            onClose = onClose,
                        )
                    }
                }
            }
        }
        if (!compactHeight || !single) {
            HorizontalDivider()
            FinishedActions(
                hasRetryableResults = hasRetryableResults,
                onRetry = onRetry,
                onClose = onClose,
            )
        }
    }
}

@Composable
private fun FinishedActions(
    hasRetryableResults: Boolean,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (hasRetryableResults) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.installer_retry_remaining))
            }
        }
        Button(onClick = onClose) {
            Text(stringResource(R.string.bulk_install_close))
        }
    }
}

@Composable
private fun resultLabel(kind: BulkInstallResultKind): String = stringResource(
    when (kind) {
        BulkInstallResultKind.Installed -> R.string.bulk_install_result_installed
        BulkInstallResultKind.Updated -> R.string.bulk_install_result_updated
        BulkInstallResultKind.Reinstalled -> R.string.bulk_install_result_reinstalled
        BulkInstallResultKind.Restored -> R.string.bulk_install_result_restored
        BulkInstallResultKind.Skipped -> R.string.bulk_install_result_skipped
        BulkInstallResultKind.Failed -> R.string.bulk_install_result_failed
        BulkInstallResultKind.PartiallyInstalled -> R.string.installer_partial_restore
        BulkInstallResultKind.NotProcessed -> R.string.installer_not_processed
    },
)

@Composable
private fun ErrorContent(
    @androidx.annotation.StringRes messageRes: Int,
    diagnosticDetail: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(messageRes), color = MaterialTheme.colorScheme.error)
        diagnosticDetail?.let {
            Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                .verticalScroll(rememberScrollState())) {
                InstallerDiagnosticDetails(it)
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
private fun InstallerDiagnosticDetails(detail: String) {
    if (detail.isBlank()) return
    var expanded by rememberSaveable(detail) { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) {
        Text(stringResource(if (expanded) R.string.installer_hide_details
            else R.string.installer_show_details))
    }
    if (expanded) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultCounters(results: List<BulkInstallResult>) {
    val installed = results.count { it.kind == BulkInstallResultKind.Installed }
    val updated = results.count { it.kind == BulkInstallResultKind.Updated }
    val reinstalled = results.count { it.kind == BulkInstallResultKind.Reinstalled }
    val restored = results.count { it.kind == BulkInstallResultKind.Restored }
    val skipped = results.count { it.kind == BulkInstallResultKind.Skipped }
    val failed = results.count {
        it.kind == BulkInstallResultKind.Failed ||
            it.kind == BulkInstallResultKind.PartiallyInstalled
    }
    val parts = mutableListOf<String>()
    if (installed > 0) {
        parts += pluralStringResource(R.plurals.bulk_install_installed_count, installed, installed)
    }
    if (updated > 0) {
        parts += pluralStringResource(R.plurals.bulk_install_updated_count, updated, updated)
    }
    if (reinstalled > 0) {
        parts += pluralStringResource(
            R.plurals.bulk_install_reinstalled_count,
            reinstalled,
            reinstalled,
        )
    }
    if (restored > 0) {
        parts += pluralStringResource(R.plurals.bulk_install_restored_count, restored, restored)
    }
    if (skipped > 0) {
        parts += pluralStringResource(
            R.plurals.bulk_install_skipped_result_count,
            skipped,
            skipped,
        )
    }
    if (failed > 0) {
        parts += pluralStringResource(R.plurals.bulk_install_failed_count, failed, failed)
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun statusLabel(item: BulkInstallItem): String = stringResource(
    when (item.status) {
        BulkInstallStatus.New -> R.string.bulk_install_status_new
        BulkInstallStatus.Update -> R.string.bulk_install_status_update
        BulkInstallStatus.Downgrade -> R.string.bulk_install_status_downgrade
        BulkInstallStatus.AlreadyInstalled -> R.string.bulk_install_status_same
        BulkInstallStatus.ReinstallOrVariant -> if (item.unit.reinstallAppId != null) {
            R.string.action_reinstall
        } else {
            R.string.bulk_install_status_reinstall
        }
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
