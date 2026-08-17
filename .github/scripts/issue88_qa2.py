from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def replace_once(path: str, old: str, new: str, label: str) -> None:
    text = read(path)
    if new in text and old not in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match in {path}, found {count}")
    write(path, text.replace(old, new, 1))


def replace_section(path: str, start: str, end: str, replacement: str, marker: str) -> None:
    text = read(path)
    if marker in text:
        return
    start_at = text.find(start)
    end_at = text.find(end, start_at)
    if start_at < 0 or end_at < 0:
        raise RuntimeError(f"section markers missing in {path}: {start!r} -> {end!r}")
    write(path, text[:start_at] + replacement + text[end_at:])


def append_strings(path: str, additions: str, marker: str) -> None:
    text = read(path)
    if marker in text:
        return
    if "</resources>" not in text:
        raise RuntimeError(f"resources close tag missing in {path}")
    write(path, text.replace("</resources>", additions.rstrip() + "\n</resources>", 1))


# --- Android 16 display-cutout QA -------------------------------------------------
# Physical QA showed the cutout preference still left the platform safe strip in place for
# normal MIDlets. The old gate required SkinLayer even though SkinLayer is an optional overlay,
# not a prerequisite for a Canvas to draw edge-to-edge.
MICRO = "app/src/main/java/javax/microedition/shell/MicroActivity.java"
replace_once(
    MICRO,
    "\t\tSkinLayer skinLayer = SkinLayer.getInstance();\n"
    "\t\tif (skinLayer != null) {\n"
    "\t\t\tskinLayerAvailable = true;\n"
    "\t\t\tbinding.overlay.addLayer(skinLayer);\n"
    "\t\t\tconfigureDisplayCutoutWindow();\n"
    "\t\t}\n",
    "\t\tSkinLayer skinLayer = SkinLayer.getInstance();\n"
    "\t\tif (skinLayer != null) {\n"
    "\t\t\tskinLayerAvailable = true;\n"
    "\t\t\tbinding.overlay.addLayer(skinLayer);\n"
    "\t\t}\n"
    "\t\t// SkinLayer is optional. Window cutout eligibility is a Canvas/window policy and\n"
    "\t\t// must be configured even when no decorative skin is active.\n"
    "\t\tconfigureDisplayCutoutWindow();\n",
    "configure cutout without requiring SkinLayer",
)
replace_once(
    MICRO,
    "\t\tboolean allowWindowCutout = displayCutoutEnabled && skinLayerAvailable\n"
    "\t\t\t\t&& !statusBarEnabled && !actionBarEnabled;",
    "\t\tboolean allowWindowCutout = displayCutoutEnabled\n"
    "\t\t\t\t&& !statusBarEnabled && !actionBarEnabled;",
    "remove SkinLayer from window cutout gate",
)
replace_once(
    MICRO,
    "\t\t\tGuestWindowPolicy.Padding guestPadding = GuestWindowPolicy.calculate(canvas,\n"
    "\t\t\t\t\tskinLayerAvailable, statusBarEnabled, actionBarEnabled, displayCutoutEnabled,",
    "\t\t\tGuestWindowPolicy.Padding guestPadding = GuestWindowPolicy.calculate(canvas,\n"
    "\t\t\t\t\tstatusBarEnabled, actionBarEnabled, displayCutoutEnabled,",
    "remove SkinLayer from guest cutout policy call",
)

write(
    "app/src/main/java/javax/microedition/shell/GuestWindowPolicy.java",
    '''/*
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

package javax.microedition.shell;

final class GuestWindowPolicy {
\tprivate GuestWindowPolicy() {
\t}

\tstatic boolean canUseDisplayCutout(boolean canvas,
\t\t\tboolean statusBarEnabled, boolean actionBarEnabled, boolean userAllowsCutout) {
\t\treturn userAllowsCutout && canvas && !statusBarEnabled && !actionBarEnabled;
\t}

\tstatic Padding calculate(boolean canvas, boolean statusBarEnabled,
\t\t\tboolean actionBarEnabled, boolean userAllowsCutout,
\t\t\tint systemLeft, int statusTop, int systemRight, int navigationBottom,
\t\t\tint cutoutLeft, int cutoutTop, int cutoutRight, int cutoutBottom, int imeBottom) {
\t\tboolean canUseCutout = canUseDisplayCutout(canvas,
\t\t\t\tstatusBarEnabled, actionBarEnabled, userAllowsCutout);
\t\tif (canvas) {
\t\t\treturn new Padding(
\t\t\t\t\tcanUseCutout ? 0 : cutoutLeft,
\t\t\t\t\tcanUseCutout ? 0 : Math.max(statusBarEnabled ? statusTop : 0, cutoutTop),
\t\t\t\t\tcanUseCutout ? 0 : cutoutRight,
\t\t\t\t\tcanUseCutout ? 0 : cutoutBottom);
\t\t}
\t\treturn new Padding(
\t\t\t\tMath.max(systemLeft, cutoutLeft),
\t\t\t\tMath.max(statusTop, cutoutTop),
\t\t\t\tMath.max(systemRight, cutoutRight),
\t\t\t\tMath.max(Math.max(navigationBottom, imeBottom), cutoutBottom));
\t}

\tstatic final class Padding {
\t\tfinal int left;
\t\tfinal int top;
\t\tfinal int right;
\t\tfinal int bottom;

\t\tprivate Padding(int left, int top, int right, int bottom) {
\t\t\tthis.left = left;
\t\t\tthis.top = top;
\t\t\tthis.right = right;
\t\t\tthis.bottom = bottom;
\t\t}
\t}
}
''',
)

write(
    "app/src/test/java/javax/microedition/shell/GuestWindowPolicyTest.java",
    '''/*
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

package javax.microedition.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GuestWindowPolicyTest {
\t@Test
\tpublic void cutoutRequiresUserOptInCanvasAndBothBarsDisabled() {
\t\tassertTrue(GuestWindowPolicy.canUseDisplayCutout(true, false, false, true));
\t\tassertFalse(GuestWindowPolicy.canUseDisplayCutout(true, false, false, false));
\t\tassertFalse(GuestWindowPolicy.canUseDisplayCutout(false, false, false, true));
\t\tassertFalse(GuestWindowPolicy.canUseDisplayCutout(true, true, false, true));
\t\tassertFalse(GuestWindowPolicy.canUseDisplayCutout(true, false, true, true));
\t}

\t@Test
\tpublic void immersiveCanvasWithAllowedCutoutKeepsGuestGeometryUnpadded() {
\t\tGuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
\t\t\t\ttrue, false, false, true,
\t\t\t\t30, 40, 50, 60,
\t\t\t\t7, 8, 9, 10, 100);

\t\tassertPadding(padding, 0, 0, 0, 0);
\t}

\t@Test
\tpublic void userDisabledCutoutReservesCutoutEvenWhenRuntimeWouldOtherwiseAllowIt() {
\t\tGuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
\t\t\t\ttrue, false, false, false,
\t\t\t\t30, 40, 50, 60,
\t\t\t\t7, 8, 9, 10, 100);

\t\tassertPadding(padding, 7, 8, 9, 10);
\t}

\t@Test
\tpublic void canvasReservesCutoutButNeverNavigationBarWhenCutoutIsDisallowed() {
\t\tGuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
\t\t\t\ttrue, false, true, true,
\t\t\t\t30, 40, 50, 60,
\t\t\t\t7, 8, 9, 10, 100);

\t\tassertPadding(padding, 7, 8, 9, 10);
\t}

\t@Test
\tpublic void visibleStatusBarIsIncludedForCanvasButCutoutPolicyStillApplies() {
\t\tGuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
\t\t\t\ttrue, true, false, true,
\t\t\t\t30, 40, 50, 60,
\t\t\t\t7, 8, 9, 10, 100);

\t\tassertPadding(padding, 7, 40, 9, 10);
\t}

\t@Test
\tpublic void hostDisplayableReservesSystemCutoutAndImeInsets() {
\t\tGuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
\t\t\t\tfalse, false, false, true,
\t\t\t\t30, 40, 50, 60,
\t\t\t\t7, 8, 9, 10, 100);

\t\tassertPadding(padding, 30, 40, 50, 100);
\t}

\t@Test
\tpublic void hostDisplayableReservesBottomCutoutWhenItExceedsNavigationAndIme() {
\t\tGuestWindowPolicy.Padding padding = GuestWindowPolicy.calculate(
\t\t\t\tfalse, false, false, true,
\t\t\t\t0, 0, 0, 20,
\t\t\t\t0, 0, 0, 40, 10);

\t\tassertPadding(padding, 0, 0, 0, 40);
\t}

\tprivate static void assertPadding(GuestWindowPolicy.Padding padding,
\t\t\tint left, int top, int right, int bottom) {
\t\tassertEquals(left, padding.left);
\t\tassertEquals(top, padding.top);
\t\tassertEquals(right, padding.right);
\t\tassertEquals(bottom, padding.bottom);
\t}
}
''',
)

# --- Shared Library / Config navigation treatment --------------------------------
LIBRARY = "app/src/main/java/ru/playsoftware/j2meloader/applist/LibraryComposeBridge.kt"
replace_once(
    LIBRARY,
    "    NavigationRail {\n        LibraryNavigationRailItem(",
    "    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {\n        LibraryNavigationRailItem(",
    "Library rail container harmonization",
)
replace_once(
    LIBRARY,
    "    NavigationBar {\n        LibraryNavigationItem(",
    "    NavigationBar(\n        containerColor = MaterialTheme.colorScheme.surfaceContainer,\n        tonalElevation = 0.dp,\n    ) {\n        LibraryNavigationItem(",
    "Library bottom bar container harmonization",
)
replace_once(
    LIBRARY,
    "        label = { Text(labelText) },\n    )\n}\n\n@Composable\nprivate fun LibraryAppsDestination(",
    "        label = { Text(labelText) },\n        alwaysShowLabel = false,\n    )\n}\n\n@Composable\nprivate fun LibraryAppsDestination(",
    "Library selected-label navigation behavior",
)

CONFIG = "app/src/main/java/ru/playsoftware/j2meloader/config/ConfigComposeBridge.kt"
replace_once(
    CONFIG,
    "    NavigationBar {\n        destinations.forEach { destination ->",
    "    NavigationBar(\n        containerColor = MaterialTheme.colorScheme.surfaceContainer,\n        tonalElevation = 0.dp,\n    ) {\n        destinations.forEach { destination ->",
    "Config bottom bar container harmonization",
)
replace_once(
    CONFIG,
    "    NavigationRail {\n        destinations.forEach { destination ->",
    "    NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {\n        destinations.forEach { destination ->",
    "Config rail container harmonization",
)

# --- Search + sort + quick-filter hierarchy --------------------------------------
header = '''@OptIn(ExperimentalMaterial3Api::class)
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
replace_section(
    LIBRARY,
    "@OptIn(ExperimentalMaterial3Api::class)\n@Composable\nprivate fun LibraryAppsHeader(",
    "@Composable\nprivate fun LibraryLoadingState()",
    header,
    "val sortEntries = stringArrayResource(R.array.pref_app_sort_entries).toList()",
)

# --- Options information architecture -------------------------------------------
options = '''@OptIn(ExperimentalLayoutApi::class)
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
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.library_destination_options),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    LibraryOptionsSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        title = R.string.library_options_library_title,
                    ) {
                        LibraryOptionGroup(label = R.string.pref_apps_view) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                        LibraryOptionGroup(
                            label = R.string.library_icon_ratio_title,
                            summary = R.string.library_icon_ratio_summary,
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                            LibraryOptionGroup(
                                label = R.string.library_grid_spacing_title,
                                summary = R.string.library_grid_spacing_summary,
                            ) {
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        title = R.string.library_options_diagnostics_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.crash_reports,
                            summary = R.string.library_action_crash_reports_summary,
                            icon = R.drawable.ic_bug_report,
                            action = onCrashReports,
                        )
                        HorizontalDivider()
                        LibraryActionRow(
                            label = R.string.save_log,
                            summary = R.string.library_action_save_log_summary,
                            icon = R.drawable.ic_save,
                            action = onSaveLog,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        title = R.string.library_options_information_title,
                    ) {
                        LibraryActionRow(
                            label = R.string.about,
                            summary = R.string.library_action_about_summary,
                            icon = R.drawable.ic_info,
                            action = onAbout,
                        )
                        HorizontalDivider()
                        LibraryActionRow(
                            label = R.string.help,
                            summary = R.string.library_action_help_summary,
                            icon = R.drawable.ic_help,
                            action = onHelp,
                        )
                    }
                    LibraryOptionsSection(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
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

'''
replace_section(
    LIBRARY,
    "@OptIn(ExperimentalLayoutApi::class)\n@Composable\ninternal fun LibraryOptionsDestination(",
    "@Composable\nprivate fun LibraryOptionsSection(",
    options,
    "title = R.string.library_options_diagnostics_title",
)

replace_once(
    LIBRARY,
    '''@Composable
private fun LibraryActionRow(label: Int, action: () -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = action),
    )
}
''',
    '''@Composable
private fun LibraryActionRow(
    label: Int,
    action: () -> Unit,
    icon: Int? = null,
    summary: Int? = null,
    destructive: Boolean = false,
) {
    val contentColor = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
        },
        supportingContent = if (summary != null) {
            {
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
        } else {
            null
        },
        leadingContent = if (icon != null) {
            {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = contentColor,
                )
            }
        } else {
            null
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = action),
    )
}
''',
    "Library action row hierarchy",
)

# --- About / Help / Licenses / More dialogs -------------------------------------
info_dialog = '''@Composable
internal fun LibraryInformationDialog(
    dialog: LibraryInfoDialog,
    onDismiss: () -> Unit,
    onOpen: (LibraryInfoDialog) -> Unit,
) {
    val context = LocalContext.current
    val title: String
    val message: AnnotatedString
    val icon = when (dialog) {
        LibraryInfoDialog.About, LibraryInfoDialog.More -> R.drawable.ic_info
        LibraryInfoDialog.Help -> R.drawable.ic_help
        LibraryInfoDialog.Licenses -> R.drawable.ic_list
    }
    val layout = libraryDialogLayout()
    val maxMessageHeight = libraryDialogListHeight(
        maxHeight = if (dialog == LibraryInfoDialog.Licenses) 520 else 420,
    )
    when (dialog) {
        LibraryInfoDialog.About -> {
            title = stringResource(R.string.about_product_name)
            message = buildAnnotatedString {
                append(stringResource(R.string.version))
                append(' ')
                append(BuildConfig.VERSION_NAME)
                append('\n')
                append(AnnotatedString.fromHtml(stringResource(R.string.about_github)))
                append('\n')
                append(stringResource(R.string.about_maintainer))
            }
        }
        LibraryInfoDialog.More -> {
            title = stringResource(R.string.more)
            message = AnnotatedString(stringResource(R.string.about_message))
        }
        LibraryInfoDialog.Help -> {
            title = stringResource(R.string.help)
            message = AnnotatedString.fromHtml(stringResource(R.string.help_message))
        }
        LibraryInfoDialog.Licenses -> {
            title = stringResource(R.string.licenses)
            message = try {
                AnnotatedString.fromHtml(
                    context.assets.open("licenses.html").bufferedReader().use { it.readText() },
                )
            } catch (_: Exception) {
                AnnotatedString(stringResource(R.string.licenses_unavailable))
            }
        }
    }
    AlertDialog(
        modifier = layout.modifier,
        properties = layout.properties,
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Text(
                        text = message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxMessageHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (dialog == LibraryInfoDialog.About) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        Column {
                            LibraryInformationActionRow(
                                label = R.string.licenses,
                                icon = R.drawable.ic_list,
                                onClick = { onOpen(LibraryInfoDialog.Licenses) },
                            )
                            HorizontalDivider()
                            LibraryInformationActionRow(
                                label = R.string.more,
                                icon = R.drawable.ic_more_vert,
                                onClick = { onOpen(LibraryInfoDialog.More) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun LibraryInformationActionRow(
    label: Int,
    icon: Int,
    onClick: () -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    )
}
'''
text = read(LIBRARY)
start = text.find("@Composable\ninternal fun LibraryInformationDialog(")
if start < 0:
    if "private fun LibraryInformationActionRow(" not in text:
        raise RuntimeError("LibraryInformationDialog marker missing")
else:
    write(LIBRARY, text[:start] + info_dialog)

# --- New selective Material Symbols ---------------------------------------------
write(
    "app/src/main/res/drawable/ic_info.xml",
    '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <!-- Material Symbols: info (Google, Apache-2.0). -->
    <path
        android:fillColor="#FF000000"
        android:pathData="M440,680L520,680L520,440L440,440L440,680ZM480,360Q497,360 508.5,348.5Q520,337 520,320Q520,303 508.5,291.5Q497,280 480,280Q463,280 451.5,291.5Q440,303 440,320Q440,337 451.5,348.5Q463,360 480,360ZM480,880Q397,880 324,848.5Q251,817 197,763Q143,709 111.5,636Q80,563 80,480Q80,397 111.5,324Q143,251 197,197Q251,143 324,111.5Q397,80 480,80Q563,80 636,111.5Q709,143 763,197Q817,251 848.5,324Q880,397 880,480Q880,563 848.5,636Q817,709 763,763Q709,817 636,848.5Q563,880 480,880ZM480,800Q614,800 707,707Q800,614 800,480Q800,346 707,253Q614,160 480,160Q346,160 253,253Q160,346 160,480Q160,614 253,707Q346,800 480,800Z" />
</vector>
''',
)
write(
    "app/src/main/res/drawable/ic_help.xml",
    '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="960"
    android:viewportHeight="960">
    <!-- Material Symbols: help (Google, Apache-2.0). -->
    <path
        android:fillColor="#FF000000"
        android:pathData="M478,720Q499,720 513.5,705.5Q528,691 528,670Q528,649 513.5,634.5Q499,620 478,620Q457,620 442.5,634.5Q428,649 428,670Q428,691 442.5,705.5Q457,720 478,720ZM442,554L516,554Q516,521 523.5,502Q531,483 566,450Q592,424 607,400.5Q622,377 622,344Q622,288 581.5,254Q541,220 480,220Q423,220 387.5,250Q352,280 338,322L404,348Q409,330 426.5,309Q444,288 480,288Q512,288 528,305.5Q544,323 544,344Q544,364 532,381.5Q520,399 500,414Q456,453 449,475Q442,497 442,554ZM480,880Q397,880 324,848.5Q251,817 197,763Q143,709 111.5,636Q80,563 80,480Q80,397 111.5,324Q143,251 197,197Q251,143 324,111.5Q397,80 480,80Q563,80 636,111.5Q709,143 763,197Q817,251 848.5,324Q880,397 880,480Q880,563 848.5,636Q817,709 763,763Q709,817 636,848.5Q563,880 480,880ZM480,800Q614,800 707,707Q800,614 800,480Q800,346 707,253Q614,160 480,160Q346,160 253,253Q160,346 160,480Q160,614 253,707Q346,800 480,800Z" />
</vector>
''',
)

# --- EN / ID strings -------------------------------------------------------------
append_strings(
    "app/src/main/res/values/strings_ui_harmonization.xml",
    '''
    <string name="library_options_library_title">Library View</string>
    <string name="library_options_application_title">Application</string>
    <string name="library_options_diagnostics_title">Diagnostics</string>
    <string name="library_options_information_title">Help &amp; Information</string>
    <string name="library_options_session_title">Session</string>
    <string name="library_action_settings_summary">Configure global behavior and appearance.</string>
    <string name="library_action_crash_reports_summary">Review retained crash reports and diagnostic evidence.</string>
    <string name="library_action_save_log_summary">Save the current application log for troubleshooting.</string>
    <string name="library_action_about_summary">Version, project, and maintainer information.</string>
    <string name="library_action_help_summary">Usage guidance and common controls.</string>
    <string name="library_action_exit_summary">Close JL-Mod Plus.</string>
''',
    'name="library_options_library_title"',
)
append_strings(
    "app/src/main/res/values-in/strings_ui_harmonization.xml",
    '''
    <string name="library_options_library_title">Tampilan Pustaka</string>
    <string name="library_options_application_title">Aplikasi</string>
    <string name="library_options_diagnostics_title">Diagnostik</string>
    <string name="library_options_information_title">Bantuan &amp; Informasi</string>
    <string name="library_options_session_title">Sesi</string>
    <string name="library_action_settings_summary">Atur perilaku dan tampilan global aplikasi.</string>
    <string name="library_action_crash_reports_summary">Tinjau laporan crash dan bukti diagnostik yang tersimpan.</string>
    <string name="library_action_save_log_summary">Simpan log aplikasi saat ini untuk pemecahan masalah.</string>
    <string name="library_action_about_summary">Informasi versi, proyek, dan maintainer.</string>
    <string name="library_action_help_summary">Panduan penggunaan dan kontrol umum.</string>
    <string name="library_action_exit_summary">Tutup JL-Mod Plus.</string>
''',
    'name="library_options_library_title"',
)

# Add focused coverage for the fourth information-dialog state.
SCREENSHOTS = "app/src/screenshotTest/kotlin/ru/playsoftware/j2meloader/applist/LibraryProfilesScreenshotTest.kt"
replace_once(
    SCREENSHOTS,
    '''@PreviewTest
@Preview(name = "Licenses dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LicensesDialogScreenshot() {
''',
    '''@PreviewTest
@Preview(name = "More dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun MoreDialogScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryInformationDialog(
            dialog = LibraryInfoDialog.More,
            onDismiss = {},
            onOpen = {},
        )
    }
}

@PreviewTest
@Preview(name = "Licenses dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LicensesDialogScreenshot() {
''',
    "More information dialog screenshot coverage",
)

print("Issue 88 QA2 refinements applied")
