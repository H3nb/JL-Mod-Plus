/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
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
import static ru.playsoftware.j2meloader.util.Constants.PREF_APPS_VIEW;
import static ru.playsoftware.j2meloader.util.Constants.PREF_APP_SORT;
import static ru.playsoftware.j2meloader.util.Constants.PREF_LAST_PATH;

import android.app.Activity;
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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.LogUtils;
import ru.woesss.j2me.installer.InstallerDialog;

/** Room 3 Library host. AppItem remains only a temporary DTO for explicit shortcut creation. */
public class AppsListFragment extends Fragment {
	private static final String TAG = AppsListFragment.class.getSimpleName();
	private static final int LAYOUT_TYPE_LIST = 0;
	private static final int LAYOUT_TYPE_GRID = 1;
	private static final int ICON_RATIO_SQUARE = 0;
	private static final int ICON_RATIO_PORTRAIT = 1;
	private static final int GRID_SPACING_COMPACT = 0;
	private static final int GRID_SPACING_STANDARD = 1;
	private static final int GRID_SPACING_SPACIOUS = 2;
	private static final long NO_GENERATION = Long.MIN_VALUE;

	private final ActivityResultLauncher<Void> openFileLauncher = registerForActivityResult(
			new ActivityResultContract<Void, Uri>() {
				@NonNull
				@Override
				public Intent createIntent(@NonNull Context context, Void input) {
					Intent intent = new Intent(context, FilteredFilePickerActivity.class);
					intent.putExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, false);
					intent.putExtra(FilePickerContract.EXTRA_SINGLE_CLICK, true);
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
				public Uri parseResult(int resultCode, @Nullable Intent intent) {
					if (resultCode == Activity.RESULT_OK && intent != null) return intent.getData();
					return null;
				}
			},
			this::onFilePicked);

	private final Map<Integer, LibraryAppRow> rowsByUiId = new HashMap<>();
	private final Map<Long, Integer> uiIdsByDatabaseId = new HashMap<>();
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
		FragmentActivity activity = requireActivity();
		preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		libraryViewModel = new ViewModelProvider(activity).get(LibraryViewModel.class);
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
		}
		composeController = new LibraryComposeController(
				(ComposeView) view,
				createActions(),
				layout,
				preferences.getInt(PREF_APP_SORT, 0),
				iconRatio,
				preferences.getBoolean(PREF_APPS_HIDE_GRID_TITLES, false),
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
		return new LibraryActions() {
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
			public void onHideGridTitlesChange(boolean hide) {
				preferences.edit().putBoolean(PREF_APPS_HIDE_GRID_TITLES, hide).apply();
				LibraryComposeController controller = composeController;
				if (controller != null) controller.updateHideGridTitles(hide);
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
					// Shortcut creation is explicit user action, so bounded icon/file work is acceptable.
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

		// Never expose rows from an older workdir generation while the next generation opens/indexes.
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
		if (activeGeneration != generation || activeWorkdir == null || !activeWorkdir.equals(workdir)) {
			activeGeneration = generation;
			activeWorkdir = workdir;
			rowsByUiId.clear();
			uiIdsByDatabaseId.clear();
			nextUiId = 1;
		} else {
			rowsByUiId.clear();
		}

		List<LibraryAppUiItem> uiItems = new ArrayList<>(state.getApps().size());
		for (LibraryAppRow row : state.getApps()) {
			int uiId = uiIdFor(row.getId());
			rowsByUiId.put(uiId, row);
			String iconPath = row.getIconRevision() == 0L
					? null
					: new File(appPath(row) + Config.MIDLET_ICON_FILE).getAbsolutePath();
			uiItems.add(new LibraryAppUiItem(
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
					row.getSourceDescription()));
		}
		LibraryComposeController controller = composeController;
		if (controller != null) {
			controller.updateSort(state.getSortVariant());
			controller.updateApps(uiItems, state.getFilter(), state.getQuickView());
		}
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
		nextUiId = 1;
	}

	private void showError(Throwable error) {
		Log.e(TAG, "Library operation failed", error);
		LibraryComposeController controller = composeController;
		if (controller != null && isAdded()) controller.showNotice(getString(R.string.error));
	}

	private void onFilePicked(Uri uri) {
		if (uri == null) return;
		preferences.edit().putString(PREF_LAST_PATH, uri.getPath()).apply();
		Activity activity = requireActivity();
		if (activity instanceof MainActivity) {
			((MainActivity) activity).requestInstaller(uri);
			return;
		}
		throw new IllegalStateException("AppsListFragment requires MainActivity host");
	}
}
