/*
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

package io.github.h3nb.jlmodplus.filepicker;

import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.EXTRA_ALLOW_CREATE_DIR;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.EXTRA_ALLOW_EXISTING_FILE;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.EXTRA_ALLOW_MULTIPLE;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.EXTRA_MODE;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.EXTRA_PATHS;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.EXTRA_SINGLE_CLICK;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.EXTRA_START_PATH;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.MODE_DIR;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.MODE_FILE;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.MODE_FILE_AND_DIR;
import static io.github.h3nb.jlmodplus.filepicker.FilePickerContract.MODE_NEW_FILE;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.util.StoragePermissionHelper;

/** App-owned browser for the File-based picker contract used by JL-Mod Plus. */
public class FilteredFilePickerActivity extends AppCompatActivity {
	private static final String STATE_CURRENT_PATH = "file_picker.current_path";
	private static final String STATE_HISTORY = "file_picker.history";
	private static final String STATE_SELECTED_PATHS = "file_picker.selected_paths";

	private final ArrayDeque<File> history = new ArrayDeque<>();
	private final LinkedHashMap<String, File> selectedItems = new LinkedHashMap<>();
	private final ExecutorService loaderExecutor = Executors.newSingleThreadExecutor();

	private boolean allowMultiple;
	private boolean allowCreateDir;
	private boolean allowExistingFile;
	private boolean singleClick;
	private int mode;
	private File rootPath;
	private File currentPath;
	private File pendingPath;
	private boolean pendingNavigation;
	private boolean pendingCreateFolder;
	private int loadGeneration;
	private boolean destroyed;
	private long lastBackPressed;
	private Toast closeToast;

	private final FilePickerUiState composeState = new FilePickerUiState();
	private StoragePermissionHelper storagePermissionHelper;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		Intent intent = getIntent();
		allowMultiple = intent.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false);
		allowCreateDir = intent.getBooleanExtra(EXTRA_ALLOW_CREATE_DIR, false);
		allowExistingFile = intent.getBooleanExtra(EXTRA_ALLOW_EXISTING_FILE, false);
		singleClick = intent.getBooleanExtra(EXTRA_SINGLE_CLICK, false);
		mode = intent.getIntExtra(EXTRA_MODE, MODE_FILE);
		if (mode < MODE_FILE || mode > MODE_NEW_FILE) {
			mode = MODE_FILE;
		}

		rootPath = getRootPath();
		String requestedPath = intent.getStringExtra(EXTRA_START_PATH);
		currentPath = FilePickerModel.normalizeStartPath(
				requestedPath == null ? null : new File(requestedPath), rootPath);
		restoreState(savedInstanceState);

		storagePermissionHelper = new StoragePermissionHelper(this, this::onPermissionResult);
		composeState.configure(allowMultiple, allowCreateDir);
		FilePickerComposeHost.install(this, composeState, new FilePickerCallbacks() {
			@Override
			public void onNavigateUp() {
				navigateUp();
			}

			@Override
			public void onEntryClick(@NonNull File file) {
				FilteredFilePickerActivity.this.onEntryClick(file);
			}

			@Override
			public boolean onEntryLongClick(@NonNull File file) {
				if (allowMultiple && FilePickerModel.isSelectable(file, mode, allowExistingFile)
						&& !file.isDirectory()) {
					toggleSelection(file, !selectedItems.containsKey(
							FilePickerModel.canonicalFile(file).getPath()));
					return true;
				}
				return false;
			}

			@Override
			public void onToggleSelection(@NonNull File file, boolean checked) {
				toggleSelection(file, checked);
			}

			@Override
			public void onCancel() {
				cancelPicker();
			}

			@Override
			public void onChoose() {
				chooseCurrentSelection();
			}

			@Override
			public void onCreateDirectory() {
				showCreateFolderDialog();
			}

			@Override
			public void onCreateDirectoryConfirmed(@NonNull String name) {
				createFolder(name);
			}
		});
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				handleBackPressed();
			}
		});
		loadCurrentPath();
	}

	@NonNull
	private File getRootPath() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			return FilePickerModel.canonicalFile(Environment.getStorageDirectory());
		}
		return FilePickerModel.canonicalFile(new File("/"));
	}

	private void restoreState(@Nullable Bundle state) {
		if (state == null) {
			return;
		}
		String savedPath = state.getString(STATE_CURRENT_PATH);
		if (savedPath != null) {
			File restoredPath = FilePickerModel.canonicalFile(new File(savedPath));
			if (FilePickerModel.isWithinRoot(restoredPath, rootPath)) {
				currentPath = restoredPath;
			}
		}
		ArrayList<String> savedHistory = state.getStringArrayList(STATE_HISTORY);
		if (savedHistory != null) {
			for (String path : savedHistory) {
				if (path != null) {
					File restoredPath = FilePickerModel.canonicalFile(new File(path));
					if (FilePickerModel.isWithinRoot(restoredPath, rootPath)) {
						history.addLast(restoredPath);
					}
				}
			}
		}
		ArrayList<String> savedSelection = state.getStringArrayList(STATE_SELECTED_PATHS);
		if (savedSelection != null) {
			for (String path : savedSelection) {
				if (path != null) {
					File file = FilePickerModel.canonicalFile(new File(path));
					if (FilePickerModel.isWithinRoot(file, rootPath)
							&& file.isFile()) {
						selectedItems.put(file.getPath(), file);
					}
				}
			}
		}
	}

	private void updateChrome() {
		boolean canGoUp = currentPath != null && !FilePickerModel.isSamePath(currentPath, rootPath);
		composeState.setChrome(currentPath, canGoUp, canChooseCurrentPath());
	}

	private boolean canChooseCurrentPath() {
		if (currentPath == null || !currentPath.isDirectory() || !currentPath.canRead()) {
			return false;
		}
		if (mode == MODE_DIR || mode == MODE_FILE_AND_DIR) {
			return true;
		}
		return !selectedItems.isEmpty();
	}

	private void loadCurrentPath() {
		if (currentPath == null) {
			return;
		}
		if (!StoragePermissionHelper.isGranted(this)) {
			requestPermission(currentPath, false, false);
			return;
		}

		final File path = currentPath;
		final int generation = ++loadGeneration;
		composeState.setLoading();
		updateChrome();
		loaderExecutor.execute(() -> {
			LoadResult result = loadEntries(path);
			runOnUiThread(() -> {
				if (destroyed || generation != loadGeneration
						|| !FilePickerModel.isSamePath(path, currentPath)) {
					return;
				}
				composeState.setEntries(result.entries,
						result.inaccessible
								? getString(R.string.file_picker_unreadable)
								: result.entries.isEmpty()
										? getString(R.string.file_picker_empty) : "");
				composeState.setSelectedPaths(selectedItems.keySet());
				updateChrome();
			});
		});
	}

	@NonNull
	private LoadResult loadEntries(@NonNull File path) {
		if (!path.isDirectory() || !path.canRead()) {
			return new LoadResult(Collections.emptyList(), true);
		}
		List<File> result = new ArrayList<>();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
					&& FilePickerModel.isSamePath(path, rootPath)) {
				StorageManager storageManager = ContextCompat.getSystemService(this, StorageManager.class);
				if (storageManager != null) {
					for (StorageVolume volume : storageManager.getStorageVolumes()) {
						File directory = volume.getDirectory();
						if (directory != null
							&& FilePickerModel.isWithinRoot(directory, rootPath)) {
							result.add(new VolumeFile(directory.getPath(), volume.getDescription(this)));
						}
					}
					if (!result.isEmpty()) {
						FilePickerModel.sortFiles(result);
						return new LoadResult(result, false);
					}
				}
			}
		} catch (SecurityException exception) {
			return new LoadResult(Collections.emptyList(), true);
		}

		File[] files;
		try {
			files = path.listFiles(file -> FilePickerModel.isItemVisible(file, mode));
		} catch (SecurityException exception) {
			return new LoadResult(Collections.emptyList(), true);
		}
		if (files == null) {
			return new LoadResult(Collections.emptyList(), true);
		}
		for (File file : files) {
			if (FilePickerModel.isWithinRoot(file, rootPath)) {
				result.add(file);
			}
		}
		FilePickerModel.sortFiles(result);
		return new LoadResult(result, false);
	}

	private void navigateTo(@NonNull File target) {
		File destination = FilePickerModel.canonicalFile(target);
		if (!FilePickerModel.isWithinRoot(destination, rootPath)
				|| !destination.isDirectory()) {
			showToast(R.string.file_picker_missing);
			return;
		}
		if (!StoragePermissionHelper.isGranted(this)) {
			requestPermission(destination, true, false);
			return;
		}
		if (!destination.canRead()) {
			showToast(R.string.file_picker_unreadable);
			return;
		}
		history.addLast(currentPath);
		currentPath = destination;
		selectedItems.clear();
		loadCurrentPath();
	}

	private void navigateUp() {
		if (currentPath == null || FilePickerModel.isSamePath(currentPath, rootPath)) {
			return;
		}
		if (!history.isEmpty()) {
			currentPath = history.removeLast();
		} else {
			File parent = currentPath.getParentFile();
			currentPath = parent == null ? rootPath : FilePickerModel.canonicalFile(parent);
		}
		selectedItems.clear();
		loadCurrentPath();
	}

	private void handleBackPressed() {
		if (!history.isEmpty() || (currentPath != null && !FilePickerModel.isSamePath(currentPath, rootPath))) {
			navigateUp();
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastBackPressed > 1500L) {
			lastBackPressed = now;
			if (closeToast == null) {
				closeToast = Toast.makeText(this, R.string.msg_press_again_to_close, Toast.LENGTH_SHORT);
			}
			closeToast.show();
			return;
		}
		if (closeToast != null) {
			closeToast.cancel();
		}
		cancelPicker();
	}

	private void onEntryClick(@NonNull File file) {
		if (!FilePickerModel.isWithinRoot(file, rootPath)) {
			showToast(R.string.file_picker_missing);
			return;
		}
		if (file.isDirectory()) {
			navigateTo(file);
			return;
		}
		if (!FilePickerModel.isSelectable(file, mode, allowExistingFile)) {
			return;
		}
		if (!file.canRead()) {
			showToast(R.string.file_picker_unreadable);
			return;
		}
		toggleSelection(file, true);
		if (singleClick) {
			chooseCurrentSelection();
		}
	}

	private void toggleSelection(@NonNull File file, boolean checked) {
		String key = FilePickerModel.canonicalFile(file).getPath();
		if (checked) {
			if (!allowMultiple) {
				selectedItems.clear();
			}
			selectedItems.put(key, FilePickerModel.canonicalFile(file));
		} else {
			selectedItems.remove(key);
		}
		composeState.setSelectedPaths(selectedItems.keySet());
		updateChrome();
	}

	private void chooseCurrentSelection() {
		if (mode == MODE_DIR || (mode == MODE_FILE_AND_DIR && selectedItems.isEmpty())) {
			finishWithFiles(Collections.singletonList(currentPath));
			return;
		}
		if (selectedItems.isEmpty()) {
			showToast(R.string.file_picker_select_something);
			return;
		}
		finishWithFiles(new ArrayList<>(selectedItems.values()));
	}

	private void finishWithFiles(@NonNull List<File> files) {
		if (files.isEmpty()) {
			showToast(R.string.file_picker_select_something);
			return;
		}
		ArrayList<Uri> uris = new ArrayList<>();
		for (File file : files) {
			File canonicalFile = FilePickerModel.canonicalFile(file);
			if (!FilePickerModel.isWithinRoot(canonicalFile, rootPath)) {
				showToast(R.string.file_picker_missing);
				return;
			}
			uris.add(Uri.fromFile(canonicalFile));
		}
		Intent result = new Intent();
		result.setData(uris.get(0));
		if (uris.size() > 1) {
			result.putParcelableArrayListExtra(EXTRA_PATHS, uris);
		}
		setResult(RESULT_OK, result);
		finish();
	}

	private void cancelPicker() {
		setResult(RESULT_CANCELED);
		finish();
	}

	private void showCreateFolderDialog() {
		if (!allowCreateDir || currentPath == null) {
			return;
		}
		if (!StoragePermissionHelper.isGranted(this)) {
			requestPermission(currentPath, false, true);
			return;
		}
		composeState.showCreateFolderDialog();
	}

	private void createFolder(@NonNull String name) {
		if (!FilePickerModel.isValidDirectoryName(name)) {
			composeState.setCreateFolderError(getString(R.string.file_picker_invalid_folder_name));
			return;
		}
		File folder = new File(currentPath, name);
		if (folder.exists()) {
			composeState.setCreateFolderError(getString(R.string.file_picker_folder_exists));
			return;
		}
		try {
			if (!folder.mkdir()) {
				composeState.setCreateFolderError(getString(folder.exists()
						? R.string.file_picker_folder_exists
						: R.string.file_picker_create_folder_error));
				return;
			}
		} catch (SecurityException exception) {
			composeState.setCreateFolderError(getString(R.string.file_picker_create_folder_error));
			return;
		}
		composeState.hideCreateFolderDialog();
		navigateTo(folder);
	}

	private void requestPermission(@NonNull File path, boolean navigate, boolean createFolder) {
		pendingPath = path;
		pendingNavigation = navigate;
		pendingCreateFolder = createFolder;
		storagePermissionHelper.launch(this);
	}

	private void onPermissionResult(Boolean granted) {
		if (!Boolean.TRUE.equals(granted)) {
			showToast(R.string.file_picker_permission_denied);
			cancelPicker();
			return;
		}
		File path = pendingPath;
		boolean navigate = pendingNavigation;
		boolean createFolder = pendingCreateFolder;
		pendingPath = null;
		pendingNavigation = false;
		pendingCreateFolder = false;
		if (navigate && path != null && !FilePickerModel.isSamePath(path, currentPath)) {
			history.addLast(currentPath);
			currentPath = FilePickerModel.canonicalFile(path);
			selectedItems.clear();
		}
		if (createFolder) {
			showCreateFolderDialog();
		} else {
			loadCurrentPath();
		}
	}

	private void showToast(int messageResId) {
		Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show();
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (currentPath != null) {
			outState.putString(STATE_CURRENT_PATH, currentPath.getPath());
		}
		ArrayList<String> savedHistory = new ArrayList<>();
		for (File path : history) {
			savedHistory.add(path.getPath());
		}
		outState.putStringArrayList(STATE_HISTORY, savedHistory);
		outState.putStringArrayList(STATE_SELECTED_PATHS,
				new ArrayList<>(selectedItems.keySet()));
	}

	@Override
	protected void onDestroy() {
		destroyed = true;
		loaderExecutor.shutdownNow();
		super.onDestroy();
	}

	private static final class LoadResult {
		final List<File> entries;
		final boolean inaccessible;

		LoadResult(@NonNull List<File> entries, boolean inaccessible) {
			this.entries = entries;
			this.inaccessible = inaccessible;
		}
	}
}
