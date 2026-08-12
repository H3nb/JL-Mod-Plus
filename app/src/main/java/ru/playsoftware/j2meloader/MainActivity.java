/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2020-2024 Yury Kharchenko
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

// Modified for JL-Mod Plus.
package ru.playsoftware.j2meloader;

import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;

import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.applist.AppsListFragment;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.crashes.CrashReportsActivity;
import ru.playsoftware.j2meloader.crashes.MidletFailureRecovery;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.PickDirResultContract;
import ru.playsoftware.j2meloader.util.StoragePermissionHelper;
import ru.woesss.j2me.installer.InstallerDialog;

public class MainActivity extends AppCompatActivity {

	private final StoragePermissionHelper storagePermissionHelper = new StoragePermissionHelper(this, this::onPermissionResult);

	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult
	);

	private AppListModel appListModel;
	private AlertDialog midletFailureDialog;
	private String lastRecoveryNoticeEventId;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		addMenuProvider(new MenuProvider() {
			@Override
			public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
				menuInflater.inflate(R.menu.crash_reports_entry, menu);
			}

			@Override
			public boolean onMenuItemSelected(@NonNull MenuItem item) {
				if (item.getItemId() != R.id.action_crash_reports) {
					return false;
				}
				startActivity(new Intent(MainActivity.this, CrashReportsActivity.class));
				return true;
			}
		}, this);
		storagePermissionHelper.launch(this);
		appListModel = new ViewModelProvider(this).get(AppListModel.class);
		if (savedInstanceState == null) {
			Intent intent = getIntent();
			Uri uri = null;
			if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
				uri = intent.getData();
			}
			AppsListFragment fragment = AppsListFragment.newInstance(uri);
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.container, fragment).commit();
		}
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			maybeShowMidletFailureRecovery();
		}
	}

	private void maybeShowMidletFailureRecovery() {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		if (midletFailureDialog != null && midletFailureDialog.isShowing()) {
			return;
		}
		if (getSupportFragmentManager().findFragmentByTag("installer") != null) {
			return;
		}

		MidletFailureRecovery.PendingFailure failure = MidletFailureRecovery.findPendingFailure(this);
		if (failure == null || failure.getEventId().equals(lastRecoveryNoticeEventId)) {
			return;
		}

		lastRecoveryNoticeEventId = failure.getEventId();
		String midletName = failure.getMidletName();
		int messageRes = midletName == null || midletName.trim().isEmpty()
				? R.string.midlet_failure_recovery_message
				: R.string.midlet_failure_recovery_message_named;
		String message = messageRes == R.string.midlet_failure_recovery_message
				? getString(messageRes)
				: getString(messageRes, midletName);

		midletFailureDialog = new AlertDialog.Builder(this)
				.setTitle(R.string.midlet_failure_recovery_title)
				.setMessage(message)
				.setCancelable(false)
				.setNeutralButton(R.string.view_reports, (dialog, which) -> {
					MidletFailureRecovery.acknowledgePendingFailures(this);
					startActivity(new Intent(this, CrashReportsActivity.class));
				})
				.setPositiveButton(R.string.close, (dialog, which) ->
						MidletFailureRecovery.acknowledgePendingFailures(this))
				.create();
		midletFailureDialog.setOnDismissListener(dialog -> midletFailureDialog = null);
		midletFailureDialog.show();
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
		new AlertDialog.Builder(this)
				.setTitle(R.string.error)
				.setCancelable(false)
				.setMessage(getString(R.string.create_apps_dir_failed, emulatorDir))
				.setNegativeButton(R.string.exit, (d, w) -> finish())
				.setPositiveButton(R.string.choose, (d, w) -> openDirLauncher.launch(null))
				.show();
	}

	void onPermissionResult(boolean granted) {
		if (granted) {
			checkAndCreateDirs();
			return;
		}
		new AlertDialog.Builder(this)
				.setTitle(android.R.string.dialog_alert_title)
				.setCancelable(false)
				.setMessage(R.string.permission_request_failed)
				.setNegativeButton(R.string.retry, (d, w) -> storagePermissionHelper.launch(this))
				.setPositiveButton(R.string.exit, (d, w) -> finish())
				.show();
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
		new AlertDialog.Builder(this)
				.setTitle(android.R.string.dialog_alert_title)
				.setCancelable(false)
				.setMessage(msg)
				.setPositiveButton(R.string.create, (d, w) -> applyWorkDir(new File(emulatorDir)))
				.setNeutralButton(R.string.change, (d, w) -> openDirLauncher.launch(emulatorDir))
				.setNegativeButton(R.string.exit, (d, w) -> finish())
				.show();
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
