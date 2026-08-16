/*
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2020 Nikita Shakarun
 * Copyright 2020-2024 Yury Kharchenko
 * Modifications for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader;

import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.FragmentContainerView;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;

import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.applist.AppsListFragment;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.crashes.CrashReporter;
import ru.playsoftware.j2meloader.crashes.CrashReportsActivity;
import ru.playsoftware.j2meloader.crashes.MidletFailureRecovery;
import ru.playsoftware.j2meloader.crashes.ProcessExitStore;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.PickDirResultContract;
import ru.playsoftware.j2meloader.util.StoragePermissionHelper;
import ru.woesss.j2me.installer.InstallerDialog;

public class MainActivity extends AppCompatActivity {
	private static final long DIAGNOSTIC_RECOVERY_RETRY_MILLIS = 200L;

	private final StoragePermissionHelper storagePermissionHelper = new StoragePermissionHelper(this, this::onPermissionResult);

	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult
	);

	private AppListModel appListModel;
	private MainActivityComposeController mainComposeController;
	private String lastRecoveryNoticeId;
	private boolean diagnosticRecoveryRetryScheduled;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		EdgeToEdgeCompat.enableForComposeLibrary(this);
		FrameLayout root = new FrameLayout(this);
		root.setId(R.id.main_host_root);
		FragmentContainerView container = new FragmentContainerView(this);
		container.setId(R.id.container);
		root.addView(container, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		ComposeView overlay = new ComposeView(this);
		root.addView(overlay, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		setContentView(root);
		mainComposeController = new MainActivityComposeController(overlay, new MainHostActions() {
			@Override
			public void onViewMidletReports() {
				mainComposeController.dismiss();
				MidletFailureRecovery.acknowledgePendingFailures(MainActivity.this);
				startActivity(new Intent(MainActivity.this, CrashReportsActivity.class));
			}

			@Override
			public void onCloseMidletNotice() {
				mainComposeController.dismiss();
				MidletFailureRecovery.acknowledgePendingFailures(MainActivity.this);
			}

			@Override
			public void onViewProcessReports() {
				mainComposeController.dismiss();
				ProcessExitStore.acknowledgePendingExits(MainActivity.this);
				startActivity(new Intent(MainActivity.this, CrashReportsActivity.class));
			}

			@Override
			public void onCloseProcessNotice() {
				mainComposeController.dismiss();
				ProcessExitStore.acknowledgePendingExits(MainActivity.this);
			}

			@Override
			public void onChooseDirectory() {
				mainComposeController.dismiss();
				openDirLauncher.launch(null);
			}

			@Override
			public void onCreateDirectory() {
				mainComposeController.dismiss();
				applyWorkDir(new File(Config.getEmulatorDir()));
			}

			@Override
			public void onRetryPermission() {
				mainComposeController.dismiss();
				storagePermissionHelper.launch(MainActivity.this);
			}

			@Override
			public void onExit() {
				mainComposeController.dismiss();
				finish();
			}
		});
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
			CrashReporter.requestProcessExitRefresh(getApplication());
			maybeShowDiagnosticRecovery();
		}
	}

	private void maybeShowDiagnosticRecovery() {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		if (mainComposeController != null && mainComposeController.isDialogVisible()) {
			return;
		}
		if (getSupportFragmentManager().findFragmentByTag("installer") != null) {
			return;
		}

		MidletFailureRecovery.PendingFailure failure = MidletFailureRecovery.findPendingFailure(this);
		if (failure != null) {
			String noticeId = "midlet:" + failure.getEventId();
			if (noticeId.equals(lastRecoveryNoticeId)) {
				return;
			}
			lastRecoveryNoticeId = noticeId;
			String midletName = failure.getMidletName();
			int messageRes = midletName == null || midletName.trim().isEmpty()
					? R.string.midlet_failure_recovery_message
					: R.string.midlet_failure_recovery_message_named;
			String message = messageRes == R.string.midlet_failure_recovery_message
					? getString(messageRes)
					: getString(messageRes, midletName);

			mainComposeController.showMidletFailure(message);
			return;
		}

		// Historical process-exit reconciliation runs on a diagnostics background thread.
		// Never make window focus wait on ApplicationExitInfo, trace copying, or legacy
		// process/journal reconciliation. Recheck shortly once that evidence is ready.
		if (!CrashReporter.isProcessExitEvidenceReady()) {
			scheduleDiagnosticRecoveryRetry();
			return;
		}

		ProcessExitStore.PendingExit exit = ProcessExitStore.findPendingStoredExit(this);
		if (exit == null || exit.getId().equals(lastRecoveryNoticeId)) {
			return;
		}
		lastRecoveryNoticeId = exit.getId();
		String midletName = exit.getMidletName();
		String message;
		if (midletName != null && !midletName.trim().isEmpty()) {
			message = getString(R.string.process_exit_recovery_message_named,
					midletName, exit.getReason());
		} else if ("midlet".equals(exit.getProcessRole())) {
			message = getString(R.string.process_exit_recovery_message_midlet, exit.getReason());
		} else {
			message = getString(R.string.process_exit_recovery_message, exit.getReason());
		}

		mainComposeController.showProcessExit(message);
	}

	private void scheduleDiagnosticRecoveryRetry() {
		if (diagnosticRecoveryRetryScheduled || isFinishing() || isDestroyed()) {
			return;
		}
		diagnosticRecoveryRetryScheduled = true;
		getWindow().getDecorView().postDelayed(() -> {
			diagnosticRecoveryRetryScheduled = false;
			if (!isFinishing() && !isDestroyed() && hasWindowFocus()) {
				maybeShowDiagnosticRecovery();
			}
		}, DIAGNOSTIC_RECOVERY_RETRY_MILLIS);
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
		mainComposeController.showDirectoryFailure(
				getString(R.string.create_apps_dir_failed, emulatorDir));
	}

	void onPermissionResult(boolean granted) {
		if (granted) {
			checkAndCreateDirs();
			return;
		}
		mainComposeController.showPermissionFailure();
	}

	private void onPickDirResult(Uri uri) {
		// PickDirResultContract is backed by the app's raw-path picker. Keep this
		// boundary explicit: external content URIs are installer inputs, not workdir paths.
		if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
			checkAndCreateDirs();
			return;
		}
		File file = new File(uri.getPath());
		applyWorkDir(file);
	}

	private void alertCreateDir() {
		String emulatorDir = Config.getEmulatorDir();
		String msg = getString(R.string.alert_msg_workdir_not_exists, emulatorDir);
		mainComposeController.showDirectoryMissing(msg);
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
		setIntent(intent);
		Uri uri = intent.getData();
		if (uri != null) {
			InstallerDialog.newInstance(uri).show(getSupportFragmentManager(), "installer");
		}
	}
}
