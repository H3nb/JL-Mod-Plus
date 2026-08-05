/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2020-2024 Yury Kharchenko
 * Copyright 2026 H3NB
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

package io.github.h3nb.jlmodplus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import io.github.h3nb.jlmodplus.applist.AppItem
import io.github.h3nb.jlmodplus.applist.AppListModel
import io.github.h3nb.jlmodplus.applist.AppsListScreen
import io.github.h3nb.jlmodplus.applist.AppsListUiState
import io.github.h3nb.jlmodplus.applist.HomeDialogState
import io.github.h3nb.jlmodplus.applist.HomeDialogs
import io.github.h3nb.jlmodplus.config.Config
import io.github.h3nb.jlmodplus.config.ProfilesActivity
import io.github.h3nb.jlmodplus.filepicker.FilePickerContract
import io.github.h3nb.jlmodplus.filepicker.FilteredFilePickerActivity
import io.github.h3nb.jlmodplus.info.InfoDialogState
import io.github.h3nb.jlmodplus.info.InfoDialogs
import io.github.h3nb.jlmodplus.settings.SettingsActivity
import io.github.h3nb.jlmodplus.ui.AppComposeTheme
import io.github.h3nb.jlmodplus.ui.WindowInsetsPolicy
import io.github.h3nb.jlmodplus.util.AppUtils
import io.github.h3nb.jlmodplus.util.Constants
import io.github.h3nb.jlmodplus.util.FileUtils
import io.github.h3nb.jlmodplus.util.LogUtils
import io.github.h3nb.jlmodplus.util.PickDirResultContract
import io.github.h3nb.jlmodplus.util.StoragePermissionHelper
import java.io.File
import java.io.IOException
import java.util.Locale
import ru.woesss.j2me.installer.InstallerActivity

class MainActivity : AppCompatActivity() {
    private lateinit var appListModel: AppListModel
    private lateinit var uiState: AppsListUiState
    private var homeDialog by mutableStateOf<HomeDialogState?>(null)
    private var infoDialog by mutableStateOf<InfoDialogState?>(null)
    private var pendingInstallerUri: Uri? = null

    private val storagePermissionHelper by lazy {
        StoragePermissionHelper(this) { granted -> onPermissionResult(granted) }
    }

    private val openDirLauncher = registerForActivityResult(
        PickDirResultContract(),
    ) { uri -> onPickDirResult(uri) }

    private val openFileLauncher = registerForActivityResult(
        object : ActivityResultContract<Unit, Uri>() {
            override fun createIntent(context: Context, input: Unit): Intent {
                val intent = Intent(context, FilteredFilePickerActivity::class.java)
                    .putExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, false)
                    .putExtra(FilePickerContract.EXTRA_SINGLE_CLICK, true)
                    .putExtra(FilePickerContract.EXTRA_ALLOW_CREATE_DIR, false)
                    .putExtra(FilePickerContract.EXTRA_MODE, FilePickerContract.MODE_FILE)
                var path = PreferenceManager.getDefaultSharedPreferences(context)
                    .getString(Constants.PREF_LAST_PATH, null)
                if (path == null) {
                    val directory = Environment.getExternalStorageDirectory()
                    if (directory.canRead()) path = directory.absolutePath
                }
                return intent.putExtra(FilePickerContract.EXTRA_START_PATH, path)
            }

            override fun parseResult(resultCode: Int, intent: Intent?): Uri =
                if (resultCode == Activity.RESULT_OK) intent?.data ?: Uri.EMPTY else Uri.EMPTY
        },
    ) { uri ->
        if (uri != Uri.EMPTY && !isFinishing && !isDestroyed) {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit()
                .putString(Constants.PREF_LAST_PATH, uri.path)
                .apply()
            showInstaller(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowInsetsPolicy.enableEdgeToEdge(window)
        setVolumeControlStream(AudioManager.STREAM_MUSIC)
        appListModel = ViewModelProvider(this)[AppListModel::class.java]
        uiState = AppsListUiState().also {
            it.setLayout(
                PreferenceManager.getDefaultSharedPreferences(this)
                    .getInt(Constants.PREF_APPS_VIEW, AppsListUiState.LAYOUT_TYPE_GRID),
            )
            if (savedInstanceState != null) {
                it.restoreSearchState(
                    savedInstanceState.getBoolean(STATE_SEARCH_EXPANDED, false),
                    savedInstanceState.getString(STATE_SEARCH_QUERY, "") ?: "",
                )
            }
        }
        pendingInstallerUri = if (
            savedInstanceState == null
                && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        ) intent.data else null

        setContent {
            AppComposeTheme {
                AppsListScreen(
                    state = uiState,
                    shortcutSupported = androidx.core.content.pm.ShortcutManagerCompat
                        .isRequestPinShortcutSupported(this),
                    onAddClick = { openFileLauncher.launch(Unit) },
                    onAppClick = { item -> Config.startApp(this, item.title, item.pathExt) },
                    onContextAction = ::onContextAction,
                    onSearchQueryDebounced = { query ->
                        appListModel.setAppListFilter(query.lowercase(Locale.getDefault()))
                    },
                    onLayoutChanged = { layoutType ->
                        PreferenceManager.getDefaultSharedPreferences(this)
                            .edit()
                            .putInt(Constants.PREF_APPS_VIEW, layoutType)
                            .apply()
                    },
                    onToolbarAction = ::onToolbarAction,
                )
                HomeDialogs(
                    dialog = homeDialog,
                    onDismiss = { homeDialog = null },
                    onRenameValueChange = { value ->
                        val dialog = homeDialog as? HomeDialogState.Rename
                        if (dialog != null) homeDialog = dialog.copy(value = value)
                    },
                    onRenameConfirm = ::confirmRename,
                    onDeleteConfirm = ::confirmDelete,
                    onSortSelected = ::setSort,
                    onCreateDirectory = {
                        val dialog = homeDialog as? HomeDialogState.CreateDirectory
                        homeDialog = null
                        if (dialog != null) applyWorkDir(File(dialog.path))
                    },
                    onChooseDirectory = {
                        val dialog = homeDialog
                        homeDialog = null
                        openDirLauncher.launch(
                            (dialog as? HomeDialogState.CreateDirectory)?.path,
                        )
                    },
                    onRetryPermission = {
                        homeDialog = null
                        storagePermissionHelper.launch(this)
                    },
                    onExit = ::finish,
                )
                InfoDialogs(
                    state = infoDialog,
                    onDismiss = { infoDialog = null },
                    onLicenses = { infoDialog = InfoDialogState.Licenses },
                    onMore = { infoDialog = InfoDialogState.More },
                )
            }
        }

        val searchBackCallback = object : OnBackPressedCallback(uiState.searchExpandedState) {
            override fun handleOnBackPressed() {
                if (!uiState.collapseSearch()) isEnabled = false
            }
        }
        onBackPressedDispatcher.addCallback(this, searchBackCallback)
        storagePermissionHelper.launch(this)
        appListModel.appList.observe(this) { onDbUpdated(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_SEARCH_EXPANDED, uiState.searchExpandedState)
        outState.putString(STATE_SEARCH_QUERY, uiState.searchQueryState)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::showInstaller)
    }

    private fun onPermissionResult(granted: Boolean) {
        if (isFinishing || isDestroyed) return
        if (granted) {
            checkAndCreateDirs()
        } else {
            homeDialog = HomeDialogState.Permission
        }
    }

    private fun checkAndCreateDirs() {
        val emulatorDir = Config.getEmulatorDir()
        val directory = File(emulatorDir)
        if (directory.isDirectory && directory.canWrite()) {
            FileUtils.initWorkDir(directory)
            appListModel.setEmulatorDirectory(emulatorDir)
            return
        }
        if (directory.exists() || directory.parentFile == null
            || !directory.parentFile!!.isDirectory || !directory.parentFile!!.canWrite()
        ) {
            homeDialog = HomeDialogState.DirectoryError(emulatorDir)
            return
        }
        homeDialog = HomeDialogState.CreateDirectory(emulatorDir)
    }

    private fun onPickDirResult(uri: Uri?) {
        if (isFinishing || isDestroyed) return
        if (uri?.path == null) {
            checkAndCreateDirs()
            return
        }
        applyWorkDir(File(uri.path!!))
    }

    private fun applyWorkDir(file: File) {
        val path = file.absolutePath
        if (!FileUtils.initWorkDir(file)) {
            homeDialog = HomeDialogState.DirectoryError(path)
            return
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(Constants.PREF_EMULATOR_DIR, path)
            .apply()
    }

    private fun onContextAction(item: AppItem, itemId: Int) {
        when (itemId) {
            R.id.action_context_shortcut -> AppUtils.addShortcut(this, item)
            R.id.action_context_rename -> homeDialog = HomeDialogState.Rename(item, item.title)
            R.id.action_context_settings -> Config.openSettings(this, item.title, item.pathExt)
            R.id.action_context_reinstall -> showInstaller(item.id)
            R.id.action_context_delete -> homeDialog = HomeDialogState.Delete(item)
        }
    }

    private fun confirmRename() {
        val dialog = homeDialog as? HomeDialogState.Rename ?: return
        val title = dialog.value.trim()
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
            return
        }
        dialog.item.title = title
        appListModel.updateApp(dialog.item)
        homeDialog = null
    }

    private fun confirmDelete() {
        val dialog = homeDialog as? HomeDialogState.Delete ?: return
        appListModel.deleteApp(dialog.item)
        homeDialog = null
    }

    private fun onToolbarAction(actionId: Int) {
        when (actionId) {
            R.id.action_about -> infoDialog = InfoDialogState.About
            R.id.action_profiles -> startActivity(Intent(this, ProfilesActivity::class.java))
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_help -> infoDialog = InfoDialogState.Help
            R.id.action_save_log -> try {
                LogUtils.writeLog()
                Toast.makeText(this, R.string.log_saved, Toast.LENGTH_SHORT).show()
            } catch (error: IOException) {
                error.printStackTrace()
                Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show()
            }
            R.id.action_exit_app -> finish()
            R.id.action_sort -> {
                val sort = PreferenceManager.getDefaultSharedPreferences(this)
                    .getInt(Constants.PREF_APP_SORT, 0)
                homeDialog = HomeDialogState.Sort(sort and Int.MAX_VALUE)
            }
        }
    }

    private fun setSort(sortVariant: Int) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        var value = sortVariant
        if (preferences.getInt(Constants.PREF_APP_SORT, 0) == sortVariant) {
            value = sortVariant or Int.MIN_VALUE
        }
        preferences.edit().putInt(Constants.PREF_APP_SORT, value).apply()
        homeDialog = null
    }

    private fun onDbUpdated(items: List<AppItem>) {
        val filter = appListModel.appFilter
        val emptyMessage = if (items.isEmpty()) {
            if (filter.isEmpty()) getString(R.string.no_data_for_display)
            else getString(R.string.msg_no_matches, filter)
        } else {
            ""
        }
        uiState.setItems(items, emptyMessage)
        pendingInstallerUri?.let { uri ->
            pendingInstallerUri = null
            showInstaller(uri)
        }
    }

    private fun showInstaller(uri: Uri) {
        if (!isFinishing && !isDestroyed) {
            startActivity(InstallerActivity.newIntent(this, uri))
        }
    }

    private fun showInstaller(id: Int) {
        if (!isFinishing && !isDestroyed) {
            startActivity(InstallerActivity.newIntent(this, id))
        }
    }

    companion object {
        private const val STATE_SEARCH_EXPANDED = "apps_list_search_expanded"
        private const val STATE_SEARCH_QUERY = "apps_list_search_query"
    }
}
