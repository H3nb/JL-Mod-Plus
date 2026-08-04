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

package io.github.h3nb.jlmodplus;

import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;

import io.github.h3nb.jlmodplus.applist.AppListModel;
import io.github.h3nb.jlmodplus.applist.AppsListFragment;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.util.Constants;
import io.github.h3nb.jlmodplus.util.FileUtils;
import io.github.h3nb.jlmodplus.util.PickDirResultContract;
import io.github.h3nb.jlmodplus.util.StoragePermissionHelper;
import io.github.h3nb.jlmodplus.ui.ComposeDialogHost;
import io.github.h3nb.jlmodplus.ui.WindowInsetsPolicy;
import ru.woesss.j2me.installer.InstallerDialog;

public class MainActivity extends AppCompatActivity {

	private final StoragePermissionHelper storagePermissionHelper = new StoragePermissionHelper(this, this::onPermissionResult);

	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult
	);

	private AppListModel appListModel;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		WindowInsetsPolicy.enableEdgeToEdge(getWindow());
		FrameLayout fragmentContainer = new FrameLayout(this);
		fragmentContainer.setId(R.id.container);
		setContentView(fragmentContainer);
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}
		appListModel = new ViewModelProvider(this).get(AppListModel.class);
		storagePermissionHelper.launch(this);
		if (savedInstanceState == null
				|| getSupportFragmentManager().findFragmentById(R.id.container) == null) {
			Intent intent = getIntent();
			Uri uri = null;
			if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
				uri = intent.getData();
			}
			AppsListFragment fragment = AppsListFragment.newInstance(uri);
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.container, fragment).commitNow();
		}
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
	}

	@Override
	protected void onPostResume() {
		super.onPostResume();
		Fragment home = getSupportFragmentManager().findFragmentById(R.id.container);
		if (home == null || home.getView() == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.container, AppsListFragment.newInstance(null))
					.commitNow();
		}
	}

	private void checkAndCreateDirs() {
		String emulatorDir = Config.getEmulatorDir();
		File dir = new File(emulatorDir);
		if (dir.isDirectory() && dir.canWrite()) {
			FileUtils.initWorkDir(dir);
			appListModel.setEmulatorDirectory(emulatorDir);
			return;
		}
		if (dir.exists() || dir.getParentFile() == null || !dir.getParentFile().isDirectory()
				|| !dir.getParentFile().canWrite()) {
			alertDirCannotCreate(emulatorDir);
			return;
		}
		alertCreateDir();
	}

	private void alertDirCannotCreate(String emulatorDir) {
		ComposeDialogHost.showMessage(
				this,
				getString(R.string.error),
				getString(R.string.create_apps_dir_failed, emulatorDir),
				getString(R.string.choose),
				getString(R.string.exit),
				null,
				false,
				() -> openDirLauncher.launch(null),
				this::finish,
				null
		);
	}

	void onPermissionResult(boolean granted) {
		if (granted) {
			checkAndCreateDirs();
			return;
		}
		ComposeDialogHost.showMessage(
				this,
				getString(android.R.string.dialog_alert_title),
				getString(R.string.permission_request_failed),
				getString(R.string.exit),
				getString(R.string.retry),
				null,
				false,
				this::finish,
				() -> storagePermissionHelper.launch(this),
				null
		);
	}

	private void onPickDirResult(Uri uri) {
		if (uri == null || uri.getPath() == null) {
			checkAndCreateDirs();
			return;
		}
		File file = new File(uri.getPath());
		applyWorkDir(file);
	}

	private void alertCreateDir() {
		String emulatorDir = Config.getEmulatorDir();
		String msg = getString(R.string.alert_msg_workdir_not_exists, emulatorDir);
		ComposeDialogHost.showMessage(
				this,
				getString(android.R.string.dialog_alert_title),
				msg,
				getString(R.string.create),
				getString(R.string.exit),
				getString(R.string.change),
				false,
				() -> applyWorkDir(new File(emulatorDir)),
				this::finish,
				() -> openDirLauncher.launch(emulatorDir)
		);
	}

	private void applyWorkDir(File file) {
		String path = file.getAbsolutePath();
		if (!FileUtils.initWorkDir(file)) {
			alertDirCannotCreate(path);
			return;
		}
		PreferenceManager.getDefaultSharedPreferences(this)
				.edit()
				.putString(Constants.PREF_EMULATOR_DIR, path)
				.apply();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		Uri uri = intent.getData();
		if (uri != null) {
			InstallerDialog.newInstance(uri).show(getSupportFragmentManager(), "installer");
		}
	}
}
