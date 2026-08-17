from pathlib import Path

path = Path("app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt")
text = path.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one marker, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


replace_once(
    """        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Text(""",
    """        item {
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
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                Text(""",
)

replace_once(
    """                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        LibraryActionRow(R.string.about, onAbout)
                        HorizontalDivider()
                        LibraryActionRow(R.string.action_settings, onSettings)
                        HorizontalDivider()
                        LibraryActionRow(R.string.profiles, onProfiles)
                        HorizontalDivider()
                        LibraryActionRow(R.string.help, onHelp)
                        HorizontalDivider()
                        LibraryActionRow(R.string.crash_reports, onCrashReports)
                        HorizontalDivider()
                        LibraryActionRow(R.string.save_log, onSaveLog)
                        HorizontalDivider()
                        LibraryActionRow(R.string.exit, onExit)
                    }
                }
            }
        }
    }
}""",
    """                LibraryOptionsSection(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    title = R.string.library_options_actions_title,
                ) {
                    LibraryActionRow(R.string.about, onAbout)
                    HorizontalDivider()
                    LibraryActionRow(R.string.action_settings, onSettings)
                    HorizontalDivider()
                    LibraryActionRow(R.string.profiles, onProfiles)
                    HorizontalDivider()
                    LibraryActionRow(R.string.help, onHelp)
                    HorizontalDivider()
                    LibraryActionRow(R.string.crash_reports, onCrashReports)
                    HorizontalDivider()
                    LibraryActionRow(R.string.save_log, onSaveLog)
                    HorizontalDivider()
                    LibraryActionRow(R.string.exit, onExit)
                }
                }
            }
        }
    }
}""",
)

# Remove the old standalone Actions heading now that Actions uses the same section primitive.
replace_once(
    """                Text(
                    text = stringResource(R.string.library_options_actions_title),
                    modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
""",
    "",
)

replace_once(
    """                            FilterChip(
                                selected = state.layout == LibraryLayout.List,
                                onClick = { onLayoutChange(LibraryLayout.List) },
                                label = { Text(stringResource(R.string.library_view_list)) },
                            )
                            FilterChip(
                                selected = state.layout == LibraryLayout.Grid,
                                onClick = { onLayoutChange(LibraryLayout.Grid) },
                                label = { Text(stringResource(R.string.library_view_grid)) },
                            )""",
    """                            FilterChip(
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
                            )""",
)

replace_once(
    """                                Text(
                                    text = stringResource(R.string.library_hide_grid_titles),
                                    style = MaterialTheme.typography.titleMedium,
                                )""",
    """                                Text(
                                    text = stringResource(R.string.library_hide_grid_titles),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )""",
)

replace_once(
    """private fun LibraryOptionsSection(
    modifier: Modifier = Modifier,
    title: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}""",
    """private fun LibraryOptionsSection(
    modifier: Modifier = Modifier,
    title: Int,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(title),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}""",
)

replace_once(
    """        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelLarge,
        )""",
    """        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )""",
)

replace_once(
    """private fun LibraryActionRow(label: Int, action: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(label)) },""",
    """private fun LibraryActionRow(label: Int, action: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },""",
)

path.write_text(text)
