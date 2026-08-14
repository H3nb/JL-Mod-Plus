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

import static ru.playsoftware.j2meloader.util.Constants.KEY_APP_URI;
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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfilesActivity;
import ru.playsoftware.j2meloader.crashes.CrashReportsActivity;
import ru.playsoftware.j2meloader.filepicker.FilePickerContract;
import ru.playsoftware.j2meloader.filepicker.FilteredFilePickerActivity;
import ru.playsoftware.j2meloader.settings.SettingsActivity;
import ru.playsoftware.j2meloader.util.AppUtils;
import ru.playsoftware.j2meloader.util.LogUtils;
import ru.woesss.j2me.installer.InstallerDialog;

public class AppsListFragment extends Fragment {
	private static final int LAYOUT_TYPE_LIST = 0;
	private static final int LAYOUT_TYPE_GRID = 1;

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
						if (dir.canRead()) {
							path = dir.getAbsolutePath();
						}
					}
					intent.putExtra(FilePickerContract.EXTRA_START_PATH, path);
					return intent;
				}

				@Override
				public Uri parseResult(int resultCode, @Nullable Intent intent) {
					if (resultCode == Activity.RESULT_OK && intent != null) {
						return intent.getData();
					}
					return null;
				}
			},
			this::onFilePicked);

	private final Map<Integer, AppItem> appsById = new HashMap<>();
	private Uri appUri;
	private SharedPreferences preferences;
	private AppListModel appListViewModel;
	private LibraryComposeController composeController;

	public static AppsListFragment newInstance(Uri data) {
		AppsListFragment fragment = new AppsListFragment();
		Bundle args = new Bundle();
		args.putParcelable(KEY_APP_URI, data);
		fragment.setArguments(args);
		return fragment;
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Bundle args = requireArguments();
		appUri = args.getParcelable(KEY_APP_URI);
		args.remove(KEY_APP_URI);
		FragmentActivity activity = requireActivity();
		preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		appListViewModel = new ViewModelProvider(activity).get(AppListModel.class);
	}

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
							 @Nullable Bundle savedInstanceState) {
		return new ComposeView(requireContext());
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		int storedLayout = preferences.getInt(PREF_APPS_VIEW, LAYOUT_TYPE_GRID);
		LibraryLayout layout = storedLayout == LAYOUT_TYPE_LIST
				? LibraryLayout.List : LibraryLayout.Grid;
		composeController = new LibraryComposeController(
				(ComposeView) view,
				createActions(),
				layout,
				preferences.getInt(PREF_APP_SORT, 0),
				ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext()));
		appListViewModel.getAppList().observe(getViewLifecycleOwner(), this::onDbUpdated);
	}

	@Override
	public void onDestroyView() {
		composeController = null;
		appsById.clear();
		super.onDestroyView();
	}

	private LibraryActions createActions() {
		return new LibraryActions() {
			@Override
			public void onSearch(@NonNull String query) {
				appListViewModel.setAppListFilter(query);
			}

			@Override
			public void onLayoutChange(@NonNull LibraryLayout layout) {
				int value = layout == LibraryLayout.Grid ? LAYOUT_TYPE_GRID : LAYOUT_TYPE_LIST;
				preferences.edit().putInt(PREF_APPS_VIEW, value).apply();
				LibraryComposeController controller = composeController;
				if (controller != null) {
					controller.updateLayout(layout);
				}
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
				AppItem app = findApp(appId);
				if (app != null) {
					Config.startApp(requireContext(), app.getTitle(), app.getPathExt());
				}
			}

			@Override
			public void onAddShortcut(int appId) {
				AppItem app = findApp(appId);
				if (app != null && ShortcutManagerCompat.isRequestPinShortcutSupported(requireContext())) {
					AppUtils.addShortcut(requireActivity(), app);
				}
			}

			@Override
			public void onRename(int appId, @NonNull String title) {
				AppItem app = findApp(appId);
				if (app != null) {
					app.setTitle(title);
					appListViewModel.updateApp(app);
				}
			}

			@Override
			public void onOpenAppSettings(int appId) {
				AppItem app = findApp(appId);
				if (app != null) {
					Config.openSettings(requireActivity(), app.getTitle(), app.getPathExt());
				}
			}

			@Override
			public void onReinstall(int appId) {
				if (findApp(appId) != null) {
					InstallerDialog.newInstance(appId)
							.show(getParentFragmentManager(), "installer");
				}
			}

			@Override
			public void onDelete(int appId) {
				AppItem app = findApp(appId);
				if (app != null) {
					appListViewModel.deleteApp(app);
				}
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
				try {
					LogUtils.writeLog();
					Toast.makeText(requireActivity(), R.string.log_saved, Toast.LENGTH_SHORT).show();
				} catch (IOException e) {
					Toast.makeText(requireActivity(), R.string.error, Toast.LENGTH_SHORT).show();
				}
			}

			@Override
			public void onExit() {
				requireActivity().finish();
			}
		};
	}

	private AppItem findApp(int id) {
		return appsById.get(id);
	}

	private void setSort(int sortVariant) {
		if (preferences.getInt(PREF_APP_SORT, 0) == sortVariant) {
			sortVariant |= 0x80000000;
		}
		preferences.edit().putInt(PREF_APP_SORT, sortVariant).apply();
		LibraryComposeController controller = composeController;
		if (controller != null) {
			controller.updateSort(sortVariant);
		}
	}

	private void onDbUpdated(List<AppItem> items) {
		appsById.clear();
		for (AppItem item : items) {
			appsById.put(item.getId(), item);
		}
		LibraryComposeController controller = composeController;
		if (controller != null) {
			controller.updateApps(items, appListViewModel.getAppFilter());
		}
		if (appUri != null) {
			InstallerDialog.newInstance(appUri).show(getParentFragmentManager(), "installer");
			appUri = null;
		}
	}

	private void onFilePicked(Uri uri) {
		if (uri == null) {
			return;
		}
		preferences.edit().putString(PREF_LAST_PATH, uri.getPath()).apply();
		InstallerDialog.newInstance(uri).show(getParentFragmentManager(), "installer");
	}
}
