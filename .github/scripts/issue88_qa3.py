from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LIBRARY = ROOT / "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"


def read() -> str:
    return LIBRARY.read_text(encoding="utf-8")


def write(text: str) -> None:
    LIBRARY.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def replace_section(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_at = text.find(start)
    end_at = text.find(end, start_at)
    if start_at < 0 or end_at < 0:
        raise RuntimeError(f"{label}: section markers not found")
    return text[:start_at] + replacement.rstrip() + "\n\n" + text[end_at:]


text = read()

# The horizontal quick-control strip owns horizontal gestures that begin on it. Consume any
# unhandled edge delta/fling before it can bubble into the Library HorizontalPager.
text = replace_once(
    text,
    "import androidx.compose.ui.unit.IntOffset\n",
    "import androidx.compose.ui.unit.IntOffset\nimport androidx.compose.ui.unit.Velocity\n",
    "Velocity import",
)

header_start = "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun LibraryAppsHeader("
header_end = "@Composable\nprivate fun LibraryLoadingState()"
header_replacement = r'''@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryAppsHeader(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    state: LibraryUiState,
    sortVisible: Boolean,
    onSortVisibilityChanged: (Boolean) -> Unit,
    onSort: (Int) -> Unit,
    interactive: Boolean = true,
) {
    val sortEntries = stringArrayResource(R.array.pref_app_sort_entries).toList()
    val selectedSort = state.sortVariant and Int.MAX_VALUE
    val ascending = state.sortVariant >= 0
    val quickControlsPagerBoundary = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset = Offset(x = available.x, y = 0f)

            override suspend fun onPostFling(
                consumed: Velocity,
                available: Velocity,
            ): Velocity = Velocity(x = available.x, y = 0f)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .height(54.dp),
            enabled = interactive,
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            placeholder = { Text(stringResource(R.string.library_search_placeholder)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = stringResource(R.string.search),
                )
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .nestedScroll(quickControlsPagerBoundary)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box {
                FilterChip(
                    selected = false,
                    onClick = { onSortVisibilityChanged(true) },
                    enabled = interactive,
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_sort),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = {
                        Text(
                            sortEntries.getOrElse(selectedSort) {
                                stringResource(R.string.pref_app_sort_title)
                            },
                        )
                    },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(
                                if (ascending) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward,
                            ),
                            contentDescription = stringResource(
                                if (ascending) R.string.pref_app_sort_ascending else R.string.pref_app_sort_descending,
                            ),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                LibrarySortMenu(
                    expanded = sortVisible && interactive,
                    entries = sortEntries,
                    selectedSort = selectedSort,
                    ascending = ascending,
                    onDismissRequest = { onSortVisibilityChanged(false) },
                    onSelected = { index ->
                        onSortVisibilityChanged(false)
                        onSort(index)
                    },
                )
            }
            LibraryQuickFilter(
                label = R.string.library_filter_all,
                icon = R.drawable.ic_apps,
                selected = true,
                enabled = true,
            )
            LibraryQuickFilter(
                label = R.string.library_filter_favorites,
                icon = R.drawable.ic_star,
            )
            LibraryQuickFilter(
                label = R.string.library_filter_recently_added,
                icon = R.drawable.ic_add,
            )
            LibraryQuickFilter(
                label = R.string.library_filter_recently_opened,
                icon = R.drawable.ic_play,
            )
        }
    }
}

@Composable
private fun LibraryQuickFilter(
    label: Int,
    icon: Int,
    selected: Boolean = false,
    enabled: Boolean = false,
) {
    FilterChip(
        selected = selected,
        onClick = {},
        enabled = enabled,
        leadingIcon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        label = { Text(stringResource(label)) },
    )
}
'''
text = replace_section(text, header_start, header_end, header_replacement, "Library Apps header")

options_start = "@OptIn(ExperimentalLayoutApi::class)\n@Composable\ninternal fun LibraryOptionsDestination("
options_end = "@OptIn(ExperimentalFoundationApi::class)\n@Composable\nprivate fun LibraryGridItem("
options_replacement = r'''@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryOptionsDestination(
    state: LibraryUiState,
    scaffoldPadding: PaddingValues,
    onLayoutChange: (LibraryLayout) -> Unit,
    onIconRatioChange: (LibraryIconRatio) -> Unit,
    onHideGridTitlesChange: (Boolean) -> Unit,
    onGridSpacingChange: (LibraryGridSpacing) -> Unit,
    onAbout: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onCrashReports: () -> Unit,
    onSaveLog: () -> Unit,
    onExit: () -> Unit,
) {
    val hideGridTitlesLabel = stringResource(R.string.library_hide_grid_titles)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        contentPadding = scaffoldPadding,
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .widthIn(max = 840.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.library_destination_options),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_library_title,
                    ) {
                        LibraryOptionGroup(label = R.string.pref_apps_view) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = state.layout == LibraryLayout.List,
                                    onClick = { onLayoutChange(LibraryLayout.List) },
                                    label = { Text(stringResource(R.string.library_view_list)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_library_list),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                                FilterChip(
                                    selected = state.layout == LibraryLayout.Grid,
                                    onClick = { onLayoutChange(LibraryLayout.Grid) },
                                    label = { Text(stringResource(R.string.library_view_grid)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_library_grid),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    },
                                )
                            }
                        }
                        HorizontalDivider()
                        LibraryOptionGroup(
                            label = R.string.library_icon_ratio_title,
                            summary = R.string.library_icon_ratio_summary,
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(
                                    selected = state.iconRatio == LibraryIconRatio.Square,
                                    onClick = { onIconRatioChange(LibraryIconRatio.Square) },
                                    label = { Text(stringResource(R.string.library_icon_ratio_square)) },
                                )
                                FilterChip(
                                    selected = state.iconRatio == LibraryIconRatio.Portrait,
                                    onClick = { onIconRatioChange(LibraryIconRatio.Portrait) },
                                    label = { Text(stringResource(R.string.library_icon_ratio_portrait)) },
                                )
                            }
                        }
                        if (state.layout == LibraryLayout.Grid) {
                            HorizontalDivider()
                            LibraryOptionGroup(
                                label = R.string.library_grid_spacing_title,
                                summary = R.string.library_grid_spacing_summary,
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    LibraryGridSpacing.entries.forEach { spacing ->
                                        val label = when (spacing) {
                                            LibraryGridSpacing.Compact -> R.string.library_grid_spacing_compact
                                            LibraryGridSpacing.Standard -> R.string.library_grid_spacing_standard
                                            LibraryGridSpacing.Spacious -> R.string.library_grid_spacing_spacious
                                        }
                                        FilterChip(
                                            selected = state.gridSpacing == spacing,
                                            onClick = { onGridSpacingChange(spacing) },
                                            label = { Text(stringResource(label)) },
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = hideGridTitlesLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = stringResource(R.string.library_hide_grid_titles_summary),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = state.hideGridTitles,
                                    onCheckedChange = onHideGridTitlesChange,
                                    modifier = Modifier.semantics {
                                        contentDescription = hideGridTitlesLabel
                                    },
                                )
                            }
                        }
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_application_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.action_settings,
                            summary = R.string.library_action_settings_summary,
                            icon = R.drawable.ic_settings,
                            action = onSettings,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_diagnostics_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.crash_reports,
                            summary = R.string.library_action_crash_reports_summary,
                            icon = R.drawable.ic_bug_report,
                            action = onCrashReports,
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                        LibraryActionRow(
                            label = R.string.save_log,
                            summary = R.string.library_action_save_log_summary,
                            icon = R.drawable.ic_save,
                            action = onSaveLog,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_information_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.about,
                            summary = R.string.library_action_about_summary,
                            icon = R.drawable.ic_info,
                            action = onAbout,
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 46.dp))
                        LibraryActionRow(
                            label = R.string.help,
                            summary = R.string.library_action_help_summary,
                            icon = R.drawable.ic_help,
                            action = onHelp,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier.fillMaxWidth(),
                        title = R.string.library_options_session_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.exit,
                            summary = R.string.library_action_exit_summary,
                            icon = R.drawable.ic_logout,
                            destructive = true,
                            action = onExit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryOptionsSection(
    modifier: Modifier = Modifier,
    title: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(title),
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun LibraryOptionGroup(
    label: Int,
    summary: Int? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        summary?.let {
            Text(
                text = stringResource(it),
                modifier = Modifier.padding(top = 1.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(5.dp))
        content()
    }
}
'''
text = replace_section(text, options_start, options_end, options_replacement, "Library Options")

# Match the compact Config preference-row rhythm for Options actions instead of relying on the
# roomier default ListItem metrics.
action_start = "@Composable\nprivate fun LibraryActionRow("
action_end = "@Composable\ninternal fun LibrarySortMenu("
action_replacement = r'''@Composable
private fun LibraryActionRow(
    label: Int,
    action: () -> Unit,
    icon: Int? = null,
    summary: Int? = null,
    destructive: Boolean = false,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = action)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = contentColor,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
            if (summary != null) {
                Text(
                    text = stringResource(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.82f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
'''
text = replace_section(text, action_start, action_end, action_replacement, "Library action row")

info_start = "@Composable\ninternal fun LibraryInformationDialog("
info_at = text.find(info_start)
if info_at < 0:
    raise RuntimeError("Library information dialog marker not found")
info_replacement = r'''@Composable
internal fun LibraryInformationDialog(
    dialog: LibraryInfoDialog,
    onDismiss: () -> Unit,
    onOpen: (LibraryInfoDialog) -> Unit,
) {
    val context = LocalContext.current
    val title = when (dialog) {
        LibraryInfoDialog.About -> stringResource(R.string.about)
        LibraryInfoDialog.More -> stringResource(R.string.more)
        LibraryInfoDialog.Help -> stringResource(R.string.help)
        LibraryInfoDialog.Licenses -> stringResource(R.string.licenses)
    }
    val icon = when (dialog) {
        LibraryInfoDialog.About, LibraryInfoDialog.More -> R.drawable.ic_info
        LibraryInfoDialog.Help -> R.drawable.ic_help
        LibraryInfoDialog.Licenses -> R.drawable.ic_list
    }
    val layout = libraryDialogLayout()
    val maxMessageHeight = libraryDialogListHeight(
        maxHeight = if (dialog == LibraryInfoDialog.Licenses) 520 else 420,
    )
    val message = when (dialog) {
        LibraryInfoDialog.About -> AnnotatedString(stringResource(R.string.about_message))
        LibraryInfoDialog.More -> AnnotatedString(stringResource(R.string.about_message))
        LibraryInfoDialog.Help -> AnnotatedString.fromHtml(stringResource(R.string.help_message))
        LibraryInfoDialog.Licenses -> try {
            AnnotatedString.fromHtml(
                context.assets.open("licenses.html").bufferedReader().use { it.readText() },
            )
        } catch (_: Exception) {
            AnnotatedString(stringResource(R.string.licenses_unavailable))
        }
    }

    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(title)
            }
        },
        text = {
            when (dialog) {
                LibraryInfoDialog.About -> LibraryAboutBody(
                    onLicenses = { onOpen(LibraryInfoDialog.Licenses) },
                    onMore = { onOpen(LibraryInfoDialog.More) },
                )
                LibraryInfoDialog.Help -> LibraryHelpBody(
                    message = message,
                    maxHeight = maxMessageHeight,
                )
                LibraryInfoDialog.Licenses,
                LibraryInfoDialog.More -> Text(
                    text = message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxMessageHeight)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun LibraryAboutBody(
    onLicenses: () -> Unit,
    onMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.about_product_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = buildString {
                append(stringResource(R.string.version))
                append(' ')
                append(BuildConfig.VERSION_NAME)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = AnnotatedString.fromHtml(stringResource(R.string.about_github)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.about_maintainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.about_message).trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.padding(top = 2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onLicenses) {
                Icon(
                    painter = painterResource(R.drawable.ic_list),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.licenses))
            }
            TextButton(onClick = onMore) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.more))
            }
        }
    }
}

@Composable
private fun LibraryHelpBody(
    message: AnnotatedString,
    maxHeight: Dp,
) {
    val items = remember(message.text) {
        message.text
            .split('•')
            .map(String::trim)
            .filter(String::isNotEmpty)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
'''
text = text[:info_at] + info_replacement.rstrip() + "\n"

write(text)
print("Issue 88 QA3 refinements applied")
