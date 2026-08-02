/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2019-2026 Yury Kharchenko
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

package io.github.h3nb.jlmodplus.applist;

import static io.github.h3nb.jlmodplus.util.Constants.KEY_APP_URI;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APPS_VIEW;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_APP_SORT;
import static io.github.h3nb.jlmodplus.util.Constants.PREF_LAST_PATH;

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
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.config.ProfilesActivity;
import io.github.h3nb.jlmodplus.filepicker.FilteredFilePickerActivity;
import io.github.h3nb.jlmodplus.filepicker.FilePickerContract;
import io.github.h3nb.jlmodplus.info.InfoDialogHost;
import io.github.h3nb.jlmodplus.settings.SettingsActivity;
import io.github.h3nb.jlmodplus.util.AppUtils;
import io.github.h3nb.jlmodplus.util.LogUtils;
import io.github.h3nb.jlmodplus.ui.ComposeDialogHost;
import ru.woesss.j2me.installer.InstallerDialog;

public class AppsListFragment extends Fragment implements AppsListComposeController.Callback {

	private final ActivityResultLauncher<Void> openFileLauncher = registerForActivityResult(
			new ActivityResultContract<Void, Uri>() {
				@NonNull
				@Override
				public Intent createIntent(@NonNull Context context, Void input) {
					Intent i = new Intent(context, FilteredFilePickerActivity.class);
					i.putExtra(FilePickerContract.EXTRA_ALLOW_MULTIPLE, false);
					i.putExtra(FilePickerContract.EXTRA_SINGLE_CLICK, true);
					i.putExtra(FilePickerContract.EXTRA_ALLOW_CREATE_DIR, false);
					i.putExtra(FilePickerContract.EXTRA_MODE, FilePickerContract.MODE_FILE);
					String path = preferences.getString(PREF_LAST_PATH, null);
					if (path == null) {
						File dir = Environment.getExternalStorageDirectory();
						if (dir.canRead()) {
							path = dir.getAbsolutePath();
						}
					}
					i.putExtra(FilePickerContract.EXTRA_START_PATH, path);
					return i;
				}

				@Override
				public Uri parseResult(int resultCode, @Nullable Intent intent) {
					if (resultCode == Activity.RESULT_OK && intent != null) {
						return intent.getData();
					}
					return null;
				}
			},
			this::onActivityResult);
	private Uri appUri;
	private SharedPreferences preferences;
	private AppListModel appListViewModel;
	private AppsListComposeController composeController;

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
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		// Fragments can survive an Activity recreation caused by a theme change.
		// Rebind the Activity-scoped model to avoid observing the destroyed Activity's repository.
		FragmentActivity activity = requireActivity();
		preferences = PreferenceManager.getDefaultSharedPreferences(activity);
		appListViewModel = new ViewModelProvider(activity).get(AppListModel.class);
		composeController = new AppsListComposeController(requireContext(), this);
		composeController.setLayout(preferences.getInt(PREF_APPS_VIEW, AppsListComposeController.LAYOUT_TYPE_GRID));
		return composeController.getRootView();
	}

	@Override
	public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
		requireActivity().getOnBackPressedDispatcher().addCallback(
				getViewLifecycleOwner(),
				new OnBackPressedCallback(true) {
					@Override
					public void handleOnBackPressed() {
						if (composeController != null && composeController.collapseSearch()) {
							return;
						}
						setEnabled(false);
						requireActivity().getOnBackPressedDispatcher().onBackPressed();
						setEnabled(true);
					}
				});
		appListViewModel.getAppList().observe(getViewLifecycleOwner(), this::onDbUpdated);
		List<AppItem> currentItems = appListViewModel.getAppList().getValue();
		if (currentItems != null) {
			onDbUpdated(currentItems);
		}
	}

	@Override
	public void onDestroyView() {
		composeController = null;
		super.onDestroyView();
	}

	private void alertRename(AppItem item) {
		ComposeDialogHost.showTextInput(
				requireActivity(),
				getString(R.string.action_context_rename),
				getString(R.string.enter_name),
				item.getTitle(),
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				true,
				titleValue -> {
					String title = titleValue.trim();
					if (title.isEmpty()) {
						Toast.makeText(getActivity(), R.string.error, Toast.LENGTH_SHORT).show();
						return false;
					}
					item.setTitle(title);
					appListViewModel.updateApp(item);
					return true;
				}
		);
	}

	private void alertDelete(AppItem item) {
		ComposeDialogHost.showMessage(
				requireActivity(),
				getString(android.R.string.dialog_alert_title),
				getString(R.string.message_delete),
				getString(android.R.string.ok),
				getString(android.R.string.cancel),
				null,
				true,
				() -> appListViewModel.deleteApp(item),
				null,
				null
		);
	}

	@Override
	public void onAppClick(AppItem item) {
		Config.startApp(requireContext(), item.getTitle(), item.getPathExt());
	}

	@Override
	public void onContextAction(AppItem appItem, int itemId) {
		if (itemId == R.id.action_context_shortcut) {
			AppUtils.addShortcut(requireActivity(), appItem);
		} else if (itemId == R.id.action_context_rename) {
			alertRename(appItem);
		} else if (itemId == R.id.action_context_settings) {
			Config.openSettings(requireActivity(), appItem.getTitle(), appItem.getPathExt());
		} else if (itemId == R.id.action_context_reinstall) {
			InstallerDialog.newInstance(appItem.getId()).show(getParentFragmentManager(), "installer");
		} else if (itemId == R.id.action_context_delete) {
			alertDelete(appItem);
		}
	}

	@Override
	public void onAddClick() {
		openFileLauncher.launch(null);
	}

	@Override
	public void onSearchQueryChanged(String query) {
		appListViewModel.setAppListFilter(query.toLowerCase(Locale.getDefault()));
	}

	@Override
	public void onLayoutChanged(int layoutType) {
		preferences.edit().putInt(PREF_APPS_VIEW, layoutType).apply();
	}

	@Override
	public void onToolbarAction(int itemId) {
		FragmentActivity activity = requireActivity();
		if (itemId == R.id.action_about) {
			InfoDialogHost.showAbout(
					activity,
					() -> InfoDialogHost.showLicenses(activity),
					() -> InfoDialogHost.showMore(activity)
			);
		} else if (itemId == R.id.action_profiles) {
			Intent intentProfiles = new Intent(activity, ProfilesActivity.class);
			startActivity(intentProfiles);
		} else if (itemId == R.id.action_settings) {
			startActivity(new Intent(activity, SettingsActivity.class));
		} else if (itemId == R.id.action_help) {
			InfoDialogHost.showHelp(activity);
		} else if (itemId == R.id.action_save_log) {
			try {
				LogUtils.writeLog();
				Toast.makeText(activity, R.string.log_saved, Toast.LENGTH_SHORT).show();
			} catch (IOException e) {
				e.printStackTrace();
				Toast.makeText(activity, R.string.error, Toast.LENGTH_SHORT).show();
			}
		} else if (itemId == R.id.action_exit_app) {
			activity.finish();
		} else if (itemId == R.id.action_sort) {
			showSortDialog();
		}
	}

	private void showSortDialog() {
		int variant = preferences.getInt(PREF_APP_SORT, 0);
		FragmentActivity activity = requireActivity();
		ComposeDialogHost.showChoice(
				activity,
				getString(R.string.pref_app_sort_title),
				getResources().getStringArray(R.array.pref_app_sort_entries),
				variant & 0x7FFFFFFF,
				getString(android.R.string.cancel),
				true,
				index -> setSort(index)
		);
	}

	private void setSort(int sortVariant) {
		if (preferences.getInt(PREF_APP_SORT, 0) == sortVariant) {
			sortVariant |= 0x80000000;
		}
		preferences.edit().putInt(PREF_APP_SORT, sortVariant).apply();
	}

	private void onDbUpdated(List<AppItem> items) {
		if (composeController == null) {
			return;
		}
		String emptyMessage;
		if (items.isEmpty()) {
			String filter = appListViewModel.getAppFilter();
			if (filter.isEmpty()) {
				emptyMessage = getString(R.string.no_data_for_display);
			} else {
				emptyMessage = getResources().getString(R.string.msg_no_matches, filter);
			}
		} else {
			emptyMessage = "";
		}
		composeController.setItems(items, emptyMessage);
		if (appUri != null) {
			InstallerDialog.newInstance(appUri).show(getParentFragmentManager(), "installer");
			appUri = null;
		}
	}

	private void onActivityResult(Uri uri) {
		if (uri == null) {
			return;
		}
		preferences.edit()
				.putString(PREF_LAST_PATH, uri.getPath())
				.apply();
		InstallerDialog.newInstance(uri).show(getParentFragmentManager(), "installer");
	}

}
