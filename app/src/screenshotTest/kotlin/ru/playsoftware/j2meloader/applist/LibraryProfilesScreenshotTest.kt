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

package ru.playsoftware.j2meloader.applist

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import ru.playsoftware.j2meloader.config.ProfileActionsDialog
import ru.playsoftware.j2meloader.config.ProfileNameDialog
import ru.playsoftware.j2meloader.config.ProfileUiItem
import ru.playsoftware.j2meloader.config.ProfilesActions
import ru.playsoftware.j2meloader.config.ProfilesScreen
import ru.playsoftware.j2meloader.config.ProfilesUiState
import ru.playsoftware.j2meloader.ui.JLModPlusTheme

private val PreviewApps = listOf(
    LibraryAppUiItem(
        id = 1,
        title = "Demo MIDlet",
        author = "Example Vendor",
        version = "1.0",
        iconPath = null,
        canReinstall = true,
        description = "This MIDlet description is intentionally long enough to show how a compact preview expands to the complete text without using a marquee. It remains readable in both portrait and landscape layouts.",
    ),
    LibraryAppUiItem(2, "Mascot Capsule 3D", "Sample Studio", "2.4", null, false),
    LibraryAppUiItem(3, "Long application title for wrapping", "Vendor", "0.9", null, true),
    LibraryAppUiItem(4, "Utility", "Open Source", "3.1", null, false),
    LibraryAppUiItem(5, "Puzzle", "Example Vendor", "1.2", null, true),
    LibraryAppUiItem(6, "Reader", "Community", "4.0", null, false),
)

private val PreviewProfiles = listOf(
    ProfileUiItem("Default phone", isDefault = true, canEdit = true),
    ProfileUiItem("240x320 compatibility", isDefault = false, canEdit = true),
    ProfileUiItem("Empty template", isDefault = false, canEdit = false),
)

@PreviewTest
@Preview(name = "Library grid", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LibraryGridScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryScreen(
            state = LibraryUiState(
                loading = false,
                apps = PreviewApps,
                layout = LibraryLayout.Grid,
            ),
            actions = NoOpLibraryActions,
        )
    }
}

@PreviewTest
@Preview(name = "Library grid portrait icons", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LibraryGridPortraitScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryScreen(
            state = LibraryUiState(
                loading = false,
                apps = PreviewApps,
                layout = LibraryLayout.Grid,
                iconRatio = LibraryIconRatio.Portrait,
                hideGridTitles = true,
                gridSpacing = LibraryGridSpacing.Spacious,
            ),
            actions = NoOpLibraryActions,
        )
    }
}

@PreviewTest
@Preview(name = "Library list portrait icons", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LibraryListPortraitScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryScreen(
            state = LibraryUiState(
                loading = false,
                apps = PreviewApps,
                layout = LibraryLayout.List,
                iconRatio = LibraryIconRatio.Portrait,
            ),
            actions = NoOpLibraryActions,
        )
    }
}

@PreviewTest
@Preview(name = "Library landscape", widthDp = 640, heightDp = 360, showBackground = true)
@Composable
fun LibraryLandscapeScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryScreen(
            state = LibraryUiState(loading = false, apps = PreviewApps),
            actions = NoOpLibraryActions,
        )
    }
}

@PreviewTest
@Preview(
    name = "Library list dark",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun LibraryListDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        LibraryScreen(
            state = LibraryUiState(
                loading = false,
                apps = PreviewApps,
                layout = LibraryLayout.List,
            ),
            actions = NoOpLibraryActions,
        )
    }
}

@PreviewTest
@Preview(
    name = "Library landscape dark",
    widthDp = 640,
    heightDp = 360,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun LibraryLandscapeDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        LibraryScreen(
            state = LibraryUiState(loading = false, apps = PreviewApps),
            actions = NoOpLibraryActions,
        )
    }
}

@PreviewTest
@Preview(name = "Library filtered empty", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LibraryFilteredEmptyScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryScreen(
            state = LibraryUiState(
                loading = false,
                apps = emptyList(),
                appliedFilter = "missing",
            ),
            actions = NoOpLibraryActions,
        )
    }
}

@PreviewTest
@Preview(name = "Library app actions", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LibraryAppActionsScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        AppActionsDialog(
            app = PreviewApps.first(),
            onDismiss = {},
            onShortcut = {},
            onRename = {},
            onSettings = {},
            onReinstall = {},
            onDelete = {},
        )
    }
}

@PreviewTest
@Preview(name = "Library options", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LibraryOptionsScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryOptionsDestination(
            state = LibraryUiState(
                loading = false,
                apps = PreviewApps,
                layout = LibraryLayout.Grid,
            ),
            scaffoldPadding = PaddingValues(),
            onLayoutChange = {},
            onIconRatioChange = {},
            onHideGridTitlesChange = {},
            onGridSpacingChange = {},
            onAbout = {},
            onSettings = {},
            onProfiles = {},
            onHelp = {},
            onCrashReports = {},
            onSaveLog = {},
            onExit = {},
        )
    }
}

@PreviewTest
@Preview(name = "Profiles content", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ProfilesContentScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        ProfilesScreen(
            state = ProfilesUiState(PreviewProfiles),
            actions = NoOpProfilesActions,
        )
    }
}

@PreviewTest
@Preview(
    name = "Profiles empty dark",
    widthDp = 360,
    heightDp = 640,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    showBackground = true,
)
@Composable
fun ProfilesEmptyDarkScreenshot() {
    JLModPlusTheme(darkTheme = true) {
        ProfilesScreen(
            state = ProfilesUiState(),
            actions = NoOpProfilesActions,
        )
    }
}

@PreviewTest
@Preview(name = "Profile create", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ProfileCreateScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        ProfileNameDialog(
            dialog = ProfileNameDialog.Create,
            existingNames = PreviewProfiles.mapTo(mutableSetOf()) { it.name },
            onDismiss = {},
            onConfirm = {},
        )
    }
}

@PreviewTest
@Preview(name = "Profile actions", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun ProfileActionsScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        ProfileActionsDialog(
            profile = PreviewProfiles.first(),
            onDismiss = {},
            onDefault = {},
            onEdit = {},
            onRename = {},
            onDelete = {},
        )
    }
}

@PreviewTest
@Preview(name = "About dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun AboutDialogScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryInformationDialog(
            dialog = LibraryInfoDialog.About,
            onDismiss = {},
            onOpen = {},
        )
    }
}

@PreviewTest
@Preview(name = "Help dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun HelpDialogScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryInformationDialog(
            dialog = LibraryInfoDialog.Help,
            onDismiss = {},
            onOpen = {},
        )
    }
}

@PreviewTest
@Preview(name = "Licenses dialog", widthDp = 360, heightDp = 640, showBackground = true)
@Composable
fun LicensesDialogScreenshot() {
    JLModPlusTheme(darkTheme = false) {
        LibraryInformationDialog(
            dialog = LibraryInfoDialog.Licenses,
            onDismiss = {},
            onOpen = {},
        )
    }
}

private object NoOpLibraryActions : LibraryActions {
    override fun onSearch(query: String) = Unit
    override fun onLayoutChange(layout: LibraryLayout) = Unit
    override fun onIconRatioChange(iconRatio: LibraryIconRatio) = Unit
    override fun onHideGridTitlesChange(hide: Boolean) = Unit
    override fun onGridSpacingChange(spacing: LibraryGridSpacing) = Unit
    override fun onSort(sortIndex: Int) = Unit
    override fun onInstall() = Unit
    override fun onOpenApp(appId: Int) = Unit
    override fun onAddShortcut(appId: Int) = Unit
    override fun onRename(appId: Int, title: String) = Unit
    override fun onOpenAppSettings(appId: Int) = Unit
    override fun onReinstall(appId: Int) = Unit
    override fun onDelete(appId: Int) = Unit
    override fun onOpenSettings() = Unit
    override fun onOpenProfiles() = Unit
    override fun onOpenCrashReports() = Unit
    override fun onSaveLog() = Unit
    override fun onExit() = Unit
}

private object NoOpProfilesActions : ProfilesActions {
    override fun onBack() = Unit
    override fun onCreate(name: String) = Unit
    override fun onSetDefault(name: String) = Unit
    override fun onEdit(name: String) = Unit
    override fun onRename(oldName: String, newName: String) = Unit
    override fun onDelete(name: String) = Unit
}
