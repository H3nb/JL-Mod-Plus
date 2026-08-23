/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
 *
 * Modified by JL-Mod Plus contributors; original upstream attribution is retained.
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

package ru.playsoftware.j2meloader.applist;

import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_GRID_SPACING;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_HIDE_GRID_TITLES;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_ICON_RATIO;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_ICON_SHAPE;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_ENHANCED_ICONS;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_SHOW_LIST_DESCRIPTION;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_VIEW;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APP_SORT;
import static ru.playsoftware.j2meloader.util.Constants.PREF_LAST_PATH;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.playsoftware.j2meloader.MainActivity;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfilesActivity;
import ru.playsoftware.j2meloader.crashes.CrashReportsActivity;
import ru.playsoftware.j2meloader.filepicker.FilePickerContract;
import ru.playsoftware.j2meloader.filepicker.FilteredFilePickerActivity;
import ru.playsoftware.j2meloader.librarydb.LibraryAppRow;
import ru.playsoftware.j2meloader.librarydb.LibraryGenerationToken;
import ru.playsoftware.j2meloader.librarydb.LibraryQuickView;
import ru.playsoftware.j2meloader.librarydb.LibraryTransferActions;
import ru.playsoftware.j2meloader.librarydb.LibraryTransferIntents;
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.LogUtils;
import ru.woesss.j2me.installer.BulkInstallerDialog;
import ru.woesss.j2me.installer.InstallerDialog;

/** Room 3 Library host. AppItem remains only a temporary DTO for explicit shortcut creation. */
public class AppsListFragment extends Fragment {
    private static final String TAG = AppsListFragment.class.getSimpleName();
    private static final int LAYOUT_TYPE_LIST = 0;
    private static final int LAYOUT_TYPE_GRID = 1;
    private static final int ICON_RATIO_SQUARE = 0;
    private static final int ICON_RATIO_PORTRAIT = 1;
    private static final int ICON_SHAPE_ROUND = 0;
    private static final int ICON_SHAPE_SQUARE = 1;
    private static final int GRID_SPACING_NONE = 3;
    private static final int GRID_SPACING_COMPACT = 0;
    private static final int GRID_SPACING_STANDARD = 1;
    private static final int GRID_SPACING_SPACIOUS = 2;
    private static final int NO_UI_ID = Integer.MIN_VALUE;
    private static final long NO_GENERATION = Long.MIN_VALUE;
    private static final String STATE_PENDING_ICON_DATABASE_ID =
            "apps_list.pending_icon_database_id";

    private final ActivityResultLauncher<Void> openFileLauncher = registerForActivityResult(
            new ActivityResultContract<Void, List<Uri>>() {
                @NonNull
                @Override
                public Intent createIntent(@NonNull Context context, Void input) {
                    Intent intent = new Intent(context, FilteredFilePickerActivity.class);
                    intent.putExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, true);
                    intent.putExtra(FilePickerContract.EXTRA_SINGLE_CLICK, false);
                    intent.putExtra(FilePickerContract.EXTRA_ALLOW_CREATE_DIR, false);
                    intent.putExtra(FilePickerContract.EXTRA_MODE, FilePickerContract.MODE_FILE);
                    String path = preferences.getString(PREF_LAST_PATH, null);
                    if (path == null) {
                        File dir = Environment.getExternalStorageDirectory();
                        if (dir.canRead()) path = dir.getAbsolutePath();
                    }
                    intent.putExtra(FilePickerContract.EXTRA_START_PATH, path);
                    return intent;
                }

                @Override
                public List<Uri> parseResult(int resultCode, @Nullable Intent intent) {
                    if (resultCode != Activity.RESULT_OK || intent == null) {
                        return List.of();
                    }
                    Set<Uri> uris = new java.util.LinkedHashSet<>();
                    ArrayList<String> paths = intent.getStringArrayListExtra(
                            FilePickerContract.EXTRA_PATHS);
                    if (paths != null) {
                        for (String path : paths) {
                            if (path != null && !path.isBlank()) uris.add(Uri.parse(path));
                        }
                    }
                    if (intent.getClipData() != null) {
                        for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                            Uri uri = intent.getClipData().getItemAt(i).getUri();
                            if (uri != null) uris.add(uri);
                        }
                    }
                    if (intent.getData() != null) uris.add(intent.getData());
                    return new ArrayList<>(uris);
                }
            },
            this::onFilesPicked);

    private final ActivityResultLauncher<String[]> importBundleLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::onImportBundlePicked);

    private long pendingIconDatabaseId = NO_GENERATION;
    private final ActivityResultLauncher<String[]> iconPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            this::onIconPicked);

    private final Map<Integer, LibraryAppRow> rowsByUiId = new HashMap<>();
    private final Map<Long, Integer> uiIdsByDatabaseId = new HashMap<>();
    private final Map<Long, LibraryAppRow> cachedRowsByDatabaseId = new HashMap<>();
    private final Map<Long, LibraryAppUiItem> cachedUiItemsByDatabaseId = new HashMap<>();
    private final LibraryCollectionsUiStore collectionsUiStore = new LibraryCollectionsUiStore();
    private List<LibraryAppRow> cachedAllReadyRows;
    private int nextUiId = 1;
    private long activeGeneration = NO_GENERATION;
    private File activeWorkdir;
    private SharedPreferences preferences;
    private LibraryViewModel libraryViewModel;
    private LibraryComposeController composeController;

    /** Kept temporarily for source compatibility; installer URI ownership moved to MainActivity. */
    public static AppsListFragment newInstance(@Nullable Uri ignored) {
        return new AppsListFragment();
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingIconDatabaseId = savedInstanceState.getLong(
                    STATE_PENDING_ICON_DATABASE_ID, NO_GENERATION);
        }
        FragmentActivity activity = requireActivity();
        preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        libraryViewModel = new ViewModelProvider(activity).get(LibraryViewModel.class);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putLong(STATE_PENDING_ICON_DATABASE_ID, pendingIconDatabaseId);
        super.onSaveInstanceState(outState);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return new ComposeView(requireContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        int storedLayout = preferences.getInt(PREF_APPS_VIEW, LAYOUT_TYPE_LIST);
        LibraryLayout layout = storedLayout == LAYOUT_TYPE_LIST
                ? LibraryLayout.List : LibraryLayout.Grid;
        int storedIconRatio = preferences.getInt(PREF_APPS_ICON_RATIO, ICON_RATIO_SQUARE);
        LibraryIconRatio iconRatio = storedIconRatio == ICON_RATIO_PORTRAIT
                ? LibraryIconRatio.Portrait : LibraryIconRatio.Square;
        int storedIconShape = preferences.getInt(PREF_APPS_ICON_SHAPE, ICON_SHAPE_ROUND);
        LibraryIconShape iconShape = storedIconShape == ICON_SHAPE_SQUARE
                ? LibraryIconShape.Square : LibraryIconShape.Round;
        int storedGridSpacing = preferences.getInt(PREF_APPS_GRID_SPACING, GRID_SPACING_STANDARD);
        LibraryGridSpacing gridSpacing;
        switch (storedGridSpacing) {
            case GRID_SPACING_COMPACT:
                gridSpacing = LibraryGridSpacing.Compact;
                break;
            case GRID_SPACING_SPACIOUS:
                gridSpacing = LibraryGridSpacing.Spacious;
                break;
            case GRID_SPACING_STANDARD:
            default:
                gridSpacing = LibraryGridSpacing.Standard;
                break;
            case GRID_SPACING_NONE:
                gridSpacing = LibraryGridSpacing.None;
                break;
        }
        composeController = new LibraryComposeController(
                (ComposeView) view,
                createActions(),
                layout,
                preferences.getInt(PREF_APP_SORT, 0),
                iconRatio,
                iconShape,
                preferences.getBoolean(PREF_APPS_ENHANCED_ICONS, true),
                preferences.getBoolean(PREF_APPS_HIDE_GRID_TITLES, false),
                preferences.getBoolean(PREF_APPS_SHOW_LIST_DESCRIPTION, true),
                gridSpacing,
                ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext()));
        libraryViewModel.observe(getViewLifecycleOwner(), this::onLibraryState);
    }

    @Override
    public void onDestroyView() {
        composeController = null;
        clearUiRows();
        super.onDestroyView();
    }

    private LibraryActions createActions() {
        return new LibraryCollectionsHost() {
            @NonNull
            @Override
            public LibraryCollectionsUiStore collectionsStore() {
                return collectionsUiStore;
            }

            @Override
            public void onSearch(@NonNull String query) {
                libraryViewModel.setFilter(query);
            }

            @Override
            public void onQuickView(@NonNull LibraryQuickView quickView) {
                libraryViewModel.setQuickView(quickView);
            }

            @Override
            public void onFavorite(int appId, boolean favorite) {
                LibraryAppRow row = findRow(appId);
                if (row == null) return;
                libraryViewModel.setFavorite(row.getId(), favorite, (ignored, error) -> {
                    if (error != null) showError(error);
                });
            }

            @Override
            public void onUpdateMetadata(int appId, @NonNull String title,
                                         @NonNull String vendor, @NonNull String version,
                                         @NonNull String description) {
                LibraryAppRow row = findRow(appId);
                if (row == null) return;
                libraryViewModel.updateMetadata(
                        row.getId(), title, vendor, version, description,
                        (ignored, error) -> {
                            if (error != null) showError(error);
                        });
            }

            @Override
            public void onPickIcon(int appId) {
                LibraryAppRow row = findRow(appId);
                if (row == null) return;
                pendingIconDatabaseId = row.getId();
                iconPickerLauncher.launch(new String[]{"image/*"});
            }

            @Override
            public void onResetIcon(int appId) {
                LibraryAppRow row = findRow(appId);
                if (row == null) return;
                libraryViewModel.resetIcon(row.getId(), (ignored, error) -> {
                    if (error != null) showError(error);
                });
            }

            @Override
            public void onLayoutChange(@NonNull LibraryLayout layout) {
                int value = layout == LibraryLayout.Grid ? LAYOUT_TYPE_GRID : LAYOUT_TYPE_LIST;
                preferences.edit().putInt(PREF_APPS_VIEW, value).apply();
                LibraryComposeController controller = composeController;
                if (controller != null) controller.updateLayout(layout);
            }

            @Override
            public void onIconRatioChange(@NonNull LibraryIconRatio iconRatio) {
                int value = iconRatio == LibraryIconRatio.Portrait
                        ? ICON_RATIO_PORTRAIT : ICON_RATIO_SQUARE;
                preferences.edit().putInt(PREF_APPS_ICON_RATIO, value).apply();
                LibraryComposeController controller = composeController;
                if (controller != null) controller.updateIconRatio(iconRatio);
            }

            @Override
            public void onIconShapeChange(@NonNull LibraryIconShape iconShape) {
                int value = iconShape == LibraryIconShape.Square
                        ? ICON_SHAPE_SQUARE : ICON_SHAPE_ROUND;
                preferences.edit().putInt(PREF_APPS_ICON_SHAPE, value).apply();
                LibraryComposeController controller = composeController;
                if (controller != null) controller.updateIconShape(iconShape);
            }

            @Override
            public void onEnhancedIconsChange(boolean enabled) {
                preferences.edit().putBoolean(PREF_APPS_ENHANCED_ICONS, enabled).apply();
                LibraryComposeController controller = composeController;
                if (controller != null) controller.updateEnhancedIcons(enabled);
            }

            @Override
            public void onHideGridTitlesChange(boolean hide) {
                preferences.edit().putBoolean(PREF_APPS_HIDE_GRID_TITLES, hide).apply();
                LibraryComposeController controller = composeController;
                if (controller != null) controller.updateHideGridTitles(hide);
            }

            @Override
            public void onShowListDescriptionChange(boolean show) {
                preferences.edit().putBoolean(PREF_APPS_SHOW_LIST_DESCRIPTION, show).apply();
                LibraryComposeController controller = composeController;
                if (controller != null) controller.updateShowListDescription(show);
            }

            @Override
            public void onGridSpacingChange(@NonNull LibraryGridSpacing spacing) {
                int value;
                switch (spacing) {
                    case Compact:
                        value = GRID_SPACING_COMPACT;
                        break;
                    case Spacious:
                        value = GRID_SPACING_SPACIOUS;
                        break;
                    case None:
                        value = GRID_SPACING_NONE;
                        break;
                    case Standard:
                    default:
                        value = GRID_SPACING_STANDARD;
                        break;
                }
                preferences.edit().putInt(PREF_APPS_GRID_SPACING, value).apply();
                LibraryComposeController controller = composeController;
                if (controller != null) controller.updateGridSpacing(spacing);
            }

            @Override
            public void onSort(int sortIndex) {
                setSort(sortIndex);
            }

            @Override
            public void onInstall() {
                openFileLauncher.launch(null);
            }

            @Override
            public void onImportAppBundle() {
                importBundleLauncher.launch(new String[]{
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream"
                });
            }

            @Override
            public void onOpenApp(int appId) {
                LibraryAppRow app = findRow(appId);
                if (app != null && activeWorkdir != null) {
                    Config.startApp(requireContext(), app.getTitle(), appPath(app));
                }
            }

            @Override
            public void onAddShortcut(int appId) {
                LibraryAppRow row = findRow(appId);
                if (row != null && ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
                    AppUtils.addShortcut(requireActivity(), toAppItem(row, appId));
                }
            }

            @Override
            public void onRename(int appId, @NonNull String title) {
                LibraryAppRow row = findRow(appId);
                if (row == null) return;
                libraryViewModel.renameApp(row.getId(), title, (ignored, error) -> {
                    if (error != null) showError(error);
                });
            }

            @Override
            public void onOpenAppSettings(int appId) {
                LibraryAppRow app = findRow(appId);
                if (app != null && activeWorkdir != null) {
                    Config.openSettings(requireActivity(), app.getTitle(), appPath(app));
                }
            }

            @Override
            public void onShareApp(int appId) {
                LibraryAppRow app = findRow(appId);
                if (app == null) return;
                LibraryTransferActions.prepareShareApp(libraryViewModel, app.getId(), (prepared, error) -> {
                    if (error != null) {
                        showTransferError(error);
                        return;
                    }
                    if (!isAdded() || prepared == null) return;
                    try {
                        startActivity(LibraryTransferIntents.shareApp(
                                requireContext(), prepared, app.getTitle()));
                    } catch (ActivityNotFoundException | SecurityException exception) {
                        showTransferError(exception);
                    }
                });
            }

            @Override
            public void onExportAppBundle(int appId) {
                LibraryAppRow app = findRow(appId);
                if (app == null) return;
                LibraryTransferActions.prepareExportAppBundle(
                        libraryViewModel,
                        app.getId(),
                        progress -> {
                            if (!isAdded()) return;
                            LibraryComposeController controller = composeController;
                            if (controller != null) {
                                controller.showNotice(getString(
                                        R.string.library_export_progress,
                                        progress.getCompletedEntries(),
                                        progress.getTotalEntries()));
                            }
                        },
                        (prepared, error) -> {
                            if (error != null) {
                                showTransferError(error);
                                return;
                            }
                            if (!isAdded() || prepared == null) return;
                            try {
                                startActivity(LibraryTransferIntents.exportBundle(
                                        requireContext(), prepared, app.getTitle()));
                            } catch (ActivityNotFoundException | SecurityException exception) {
                                showTransferError(exception);
                            }
                        });
            }

            @Override
            public void onCreateCollection(@NonNull String name) {
                libraryViewModel.createCollection(name, (ignored, error) -> {
                    if (error != null) showError(error);
                });
            }

            @Override
            public void onRenameCollection(long collectionId, @NonNull String name) {
                libraryViewModel.renameCollection(collectionId, name, (ignored, error) -> {
                    if (error != null) showError(error);
                });
            }

            @Override
            public void onDeleteCollection(long collectionId) {
                collectionsUiStore.dismissMembers();
                libraryViewModel.deleteCollection(collectionId, (ignored, error) -> {
                    if (error != null) showError(error);
                });
            }

            @Override
            public void onOpenCollection(long collectionId) {
                loadCollectionMembers(collectionId);
            }

            @Override
            public void onPrepareCollectionAppPicker() {
                publishCollectionAllApps(currentAllReadyRows());
            }

            @Override
            public void onDismissCollectionMembers() {
                collectionsUiStore.dismissMembers();
            }

            @Override
            public void onRequestAddToCollection(int appId) {
                LibraryAppRow app = findRow(appId);
                if (app != null) collectionsUiStore.showAddTarget(appId, app.getTitle());
            }

            @Override
            public void onDismissAddToCollection() {
                collectionsUiStore.dismissAddTarget();
            }

            @Override
            public void onAddAppToCollection(int appId, long collectionId) {
                LibraryAppRow app = findRow(appId);
                if (app == null) return;
                libraryViewModel.setCollectionMembership(
                        collectionId,
                        app.getId(),
                        true,
                        (ignored, error) -> {
                            if (error != null) {
                                showError(error);
                                loadCollectionMembers(collectionId);
                                return;
                            }
                            loadCollectionMembers(collectionId);
                        });
            }

            @Override
            public void onRemoveAppFromCollection(int appId, long collectionId) {
                LibraryAppRow app = findRow(appId);
                if (app == null) return;
                libraryViewModel.setCollectionMembership(
                        collectionId,
                        app.getId(),
                        false,
                        (ignored, error) -> {
                            if (error != null) {
                                showError(error);
                                loadCollectionMembers(collectionId);
                                return;
                            }
                            loadCollectionMembers(collectionId);
                        });
            }

            @Override
            public void onReinstall(int appId) {
                LibraryAppRow app = findRow(appId);
                File workdir = activeWorkdir;
                LibraryGenerationToken generation = libraryViewModel.readyGeneration();
                if (app == null || workdir == null || generation == null ||
                        activeGeneration != generation.getGeneration() ||
                        !workdir.equals(generation.getEmulatorDir())) {
                    return;
                }
                long generationId = generation.getGeneration();
                libraryViewModel.resolveReinstallAvailability(app.getId(), (available, error) -> {
                    if (error != null) {
                        showError(error);
                        return;
                    }
                    if (!isAdded()) return;
                    if (!Boolean.TRUE.equals(available)) {
                        LibraryComposeController controller = composeController;
                        if (controller != null) {
                            controller.showNotice(getString(R.string.library_reinstall_source_missing));
                        }
                        return;
                    }
                    LibraryAppRow current = findRow(appId);
                    if (activeGeneration != generationId || activeWorkdir == null ||
                            !activeWorkdir.equals(workdir) ||
                            !libraryViewModel.isReadyGeneration(generationId, workdir) ||
                            current == null || current.getId() != app.getId() ||
                            !current.getStorageKey().equals(app.getStorageKey())) {
                        showError(new IllegalStateException("Library reinstall target changed"));
                        return;
                    }
                    InstallerDialog.newInstance(
                                    app.getId(),
                                    generationId,
                                    workdir.getAbsolutePath(),
                                    app.getStorageKey())
                            .show(getParentFragmentManager(), "installer");
                });
            }

            @Override
            public void onDelete(int appId) {
                LibraryAppRow app = findRow(appId);
                if (app == null) return;
                libraryViewModel.deleteInstalledApp(app.getId(), (result, error) -> {
                    if (error != null) {
                        showError(error);
                        return;
                    }
                    if (result != null && (result.getLeftoverConfig() || result.getLeftoverSaveData())) {
                        Log.w(TAG, "App removed with leftover config/save data: " + result.getAppPath());
                    }
                });
            }

            @Override
            public void onAddAppsToCollection(@NonNull Set<Long> appIds, long collectionId) {
                libraryViewModel.addAppsToCollection(
                        collectionId,
                        appIds,
                        (ignored, error) -> {
                            if (error != null) {
                                showError(error);
                                loadCollectionMembers(collectionId);
                                return;
                            }
                            loadCollectionMembers(collectionId);
                        });
            }

            @Override
            public void onAddSelectedToCollection(@NonNull Set<Long> appIds) {
                collectionsUiStore.showBulkAddTarget(appIds);
            }

            @Override
            public void onDeleteSelected(@NonNull Set<Long> appIds) {
                libraryViewModel.deleteInstalledApps(appIds, (result, error) -> {
                    if (error != null) {
                        showError(error);
                        return;
                    }
                    if (!isAdded() || result == null) return;
                    LibraryComposeController controller = composeController;
                    if (controller != null) {
                        int deletedCount = result.getSucceeded().size();
                        int failedCount = result.getFailed().size() + result.getMissingAppIds().size();
                        controller.showNotice(getResources().getQuantityString(
                                R.plurals.library_bulk_delete_result,
                                deletedCount,
                                deletedCount,
                                failedCount));
                    }
                });
            }

            @Override
            public void onShareSelected(@NonNull Set<Long> appIds) {
                LibraryTransferActions.prepareShareApps(
                        libraryViewModel,
                        appIds,
                        (prepared, error) -> {
                            if (error != null) {
                                showTransferError(error);
                                return;
                            }
                            if (!isAdded() || prepared == null) return;
                            try {
                                startActivity(LibraryTransferIntents.shareApp(
                                        requireContext(), prepared, getString(R.string.library_bulk_share_apps)));
                            } catch (ActivityNotFoundException | SecurityException exception) {
                                showTransferError(exception);
                            }
                        });
            }

            @Override
            public void onExportSelectedBundle(@NonNull Set<Long> appIds) {
                LibraryTransferActions.prepareExportAppsBundle(
                        libraryViewModel,
                        appIds,
                        progress -> {
                            if (!isAdded()) return;
                            LibraryComposeController controller = composeController;
                            if (controller != null) {
                                controller.showNotice(getString(
                                        R.string.library_export_progress,
                                        progress.getCompletedEntries(),
                                        progress.getTotalEntries()));
                            }
                        },
                        (prepared, error) -> {
                            if (error != null) {
                                showTransferError(error);
                                return;
                            }
                            if (!isAdded() || prepared == null) return;
                            try {
                                startActivity(LibraryTransferIntents.exportBundle(
                                        requireContext(), prepared, getString(R.string.library_bulk_export_apps)));
                            } catch (ActivityNotFoundException | SecurityException exception) {
                                showTransferError(exception);
                            }
                        });
            }

            @Override
            public void onReinstallSelected(@NonNull Set<Long> appIds) {
                File workdir = activeWorkdir;
                LibraryGenerationToken generation = libraryViewModel.readyGeneration();
                if (workdir == null || generation == null ||
                        activeGeneration != generation.getGeneration() ||
                        !workdir.equals(generation.getEmulatorDir())) {
                    showError(new IllegalStateException("Library generation is not ready"));
                    return;
                }
                BulkInstallerDialog.newReinstall(appIds)
                        .show(getParentFragmentManager(), BulkInstallerDialog.TAG);
            }

            @Override
            public void onOpenSettings() {
                startActivity(new Intent(requireActivity(), SettingsActivity.class));
            }

            @Override
            public void onOpenProfiles() {
                startActivity(new Intent(requireActivity(), ProfilesActivity.class));
            }

            @Override
            public void onOpenCrashReports() {
                startActivity(new Intent(requireActivity(), CrashReportsActivity.class));
            }

            @Override
            public void onSaveLog() {
                LibraryComposeController controller = composeController;
                try {
                    LogUtils.writeLog();
                    if (controller != null) controller.showNotice(getString(R.string.log_saved));
                } catch (IOException e) {
                    if (controller != null) controller.showNotice(getString(R.string.error));
                }
            }

            @Override
            public void onExit() {
                requireActivity().finish();
            }

            @Override
            public void onRetryLibrary() {
                libraryViewModel.retry();
            }
        };
    }

    private LibraryAppRow findRow(int uiId) {
        return rowsByUiId.get(uiId);
    }

    private void loadCollectionMembers(long collectionId) {
        libraryViewModel.getCollectionAppIds(collectionId, (appIds, error) -> {
            if (error != null) {
                showError(error);
                return;
            }
            if (!isAdded() || appIds == null) return;
            List<LibraryAppRow> rows = libraryViewModel.getApps(appIds);
            List<LibraryAppUiItem> members = new ArrayList<>(rows.size());
            for (LibraryAppRow row : rows) {
                members.add(toLibraryUiItem(row));
            }
            collectionsUiStore.showMembers(collectionId, members);
        });
    }

    private void setSort(int sortVariant) {
        if (preferences.getInt(PREF_APP_SORT, 0) == sortVariant) {
            sortVariant |= Integer.MIN_VALUE;
        }
        libraryViewModel.setSort(sortVariant);
        LibraryComposeController controller = composeController;
        if (controller != null) controller.updateSort(sortVariant);
    }

    private void onLibraryState(LibraryViewModel.DisplayState state) {
        LibraryComposeController controller = composeController;
        if (state instanceof LibraryViewModel.DisplayState.Ready) {
            publishReady((LibraryViewModel.DisplayState.Ready) state);
            return;
        }

        clearUiRows();
        if (controller == null) return;
        if (state instanceof LibraryViewModel.DisplayState.Indexing) {
            LibraryViewModel.DisplayState.Indexing indexing =
                    (LibraryViewModel.DisplayState.Indexing) state;
            controller.showIndexing(
                    indexing.getCompleted(),
                    indexing.getTotal(),
                    indexing.getStorageKey());
            return;
        }
        if (state instanceof LibraryViewModel.DisplayState.Error) {
            controller.showError(((LibraryViewModel.DisplayState.Error) state).getMessage());
            return;
        }
        controller.showLoading();
    }

    private void publishReady(LibraryViewModel.DisplayState.Ready state) {
        long generation = state.getGeneration();
        File workdir = state.getEmulatorDir();
        boolean generationChanged = activeGeneration != generation || activeWorkdir == null ||
                !activeWorkdir.equals(workdir);
        if (generationChanged) {
            activeGeneration = generation;
            activeWorkdir = workdir;
            rowsByUiId.clear();
            uiIdsByDatabaseId.clear();
            cachedRowsByDatabaseId.clear();
            cachedUiItemsByDatabaseId.clear();
            collectionsUiStore.clear();
            nextUiId = 1;
            cachedAllReadyRows = null;
        }

        collectionsUiStore.publishCollections(state.getCollections());
        // Keep the complete READY snapshot truly lazy. Normal list/filter/favorite/stat emissions only
        // map the already-projected rows below; the O(N) full-library walk happens solely when the user
        // explicitly opens Collection -> Add apps.
        cachedAllReadyRows = null;
        Long activeCollectionId = collectionsUiStore.activeCollectionId();
        if (activeCollectionId != null) {
            loadCollectionMembers(activeCollectionId);
        }

        List<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());
        for (LibraryAppRow row : state.getApps()) {
            uiItems.add(toLibraryUiItem(row));
        }
        LibraryComposeController controller = composeController;
        if (controller != null) {
            controller.updateSort(state.getSortVariant());
            controller.updateApps(uiItems, state.getFilter(), state.getQuickView(), generation);
        }
    }

    private void pruneUiCaches(List<LibraryAppRow> rows) {
        Set<Long> liveDatabaseIds = new HashSet<>(rows.size());
        for (LibraryAppRow row : rows) {
            liveDatabaseIds.add(row.getId());
        }
        cachedRowsByDatabaseId.keySet().retainAll(liveDatabaseIds);
        cachedUiItemsByDatabaseId.keySet().retainAll(liveDatabaseIds);
        uiIdsByDatabaseId.keySet().retainAll(liveDatabaseIds);
        Iterator<Map.Entry<Integer, LibraryAppRow>> iterator = rowsByUiId.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!liveDatabaseIds.contains(iterator.next().getValue().getId())) {
                iterator.remove();
            }
        }
    }

    private List<LibraryAppRow> currentAllReadyRows() {
        List<LibraryAppRow> rows = cachedAllReadyRows;
        if (rows != null) return rows;
        rows = libraryViewModel.getAllApps();
        pruneUiCaches(rows);
        cachedAllReadyRows = rows;
        return rows;
    }

    private void publishCollectionAllApps(List<LibraryAppRow> rows) {
        List<LibraryAppUiItem> allUiItems = new ArrayList<>(rows.size());
        for (LibraryAppRow row : rows) {
            allUiItems.add(toLibraryUiItem(row));
        }
        collectionsUiStore.publishAllApps(allUiItems);
    }

    private LibraryAppUiItem toLibraryUiItem(LibraryAppRow row) {
        LibraryAppRow cachedRow = cachedRowsByDatabaseId.get(row.getId());
        LibraryAppUiItem cachedItem = cachedUiItemsByDatabaseId.get(row.getId());
        if (cachedItem != null && row.equals(cachedRow)) {
            rowsByUiId.put(cachedItem.getId(), row);
            return cachedItem;
        }

        int uiId = uiIdFor(row.getId());
        rowsByUiId.put(uiId, row);
        String iconPath = row.getIconRevision() == 0L
                ? null
                : new File(appPath(row) + Config.MIDLET_ICON_FILE).getAbsolutePath();
        LibraryAppUiItem item = new LibraryAppUiItem(
                uiId,
                row.getTitle(),
                row.getVendor(),
                row.getVersion(),
                iconPath,
                true,
                row.getDescription(),
                row.getIconRevision(),
                row.getFavorite(),
                row.getSourceTitle(),
                row.getSourceVendor(),
                row.getSourceVersion(),
                row.getSourceDescription(),
                row.getPlayCount(),
                row.getTotalPlayTimeMs(),
                row.getId());
        cachedRowsByDatabaseId.put(row.getId(), row);
        cachedUiItemsByDatabaseId.put(row.getId(), item);
        return item;
    }

    private int uiIdFor(long databaseId) {
        Integer existing = uiIdsByDatabaseId.get(databaseId);
        if (existing != null) return existing;
        if (nextUiId == Integer.MAX_VALUE) {
            throw new IllegalStateException("Library UI id space exhausted");
        }
        int uiId = nextUiId++;
        uiIdsByDatabaseId.put(databaseId, uiId);
        return uiId;
    }

    private AppItem toAppItem(LibraryAppRow row, int uiId) {
        AppItem item = new AppItem(
                row.getStorageKey(),
                row.getTitle(),
                row.getVendor(),
                row.getVersion());
        item.setId(uiId);
        item.setIconRevision(row.getIconRevision());
        return item;
    }

    private String appPath(LibraryAppRow row) {
        File root = activeWorkdir;
        if (root == null) throw new IllegalStateException("Library workdir is not READY");
        return new File(new File(root, "converted"), row.getStorageKey()).getAbsolutePath();
    }

    private void clearUiRows() {
        rowsByUiId.clear();
        activeGeneration = NO_GENERATION;
        activeWorkdir = null;
        uiIdsByDatabaseId.clear();
        cachedRowsByDatabaseId.clear();
        cachedUiItemsByDatabaseId.clear();
        nextUiId = 1;
        cachedAllReadyRows = null;
        collectionsUiStore.clear();
    }

    private void showError(Throwable error) {
        Log.e(TAG, "Library operation failed", error);
        LibraryComposeController controller = composeController;
        if (controller != null && isAdded()) controller.showNotice(getString(R.string.error));
    }

    private void showTransferError(Throwable error) {
        Log.e(TAG, "Library transfer failed", error);
        LibraryComposeController controller = composeController;
        if (controller != null && isAdded()) {
            controller.showNotice(getString(R.string.library_transfer_unavailable));
        }
    }

    private void onFilesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || !isAdded()) return;
        Uri first = uris.get(0);
        if (first.getPath() != null) {
            preferences.edit().putString(PREF_LAST_PATH, first.getPath()).apply();
        }
        FragmentManager manager = getParentFragmentManager();
        if (manager.isDestroyed() ||
                manager.findFragmentByTag(BulkInstallerDialog.TAG) != null) {
            return;
        }
        manager.beginTransaction()
                .add(BulkInstallerDialog.newFiles(uris), BulkInstallerDialog.TAG)
                .commitAllowingStateLoss();
    }

    private void onImportBundlePicked(Uri uri) {
        if (uri == null) return;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some document providers expose only the active transient read grant.
        }
        Activity activity = requireActivity();
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).requestBundleInstaller(uri);
            return;
        }
        throw new IllegalStateException("AppsListFragment requires MainActivity host");
    }

    private void onIconPicked(Uri uri) {
        long databaseId = pendingIconDatabaseId;
        pendingIconDatabaseId = NO_GENERATION;
        if (uri == null || databaseId == NO_GENERATION) return;
        try {
            requireContext().getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // Some providers expose only a transient read grant.
        }
        libraryViewModel.updateIcon(databaseId, uri, (ignored, error) -> {
            if (error != null) showError(error);
        });
    }
}
