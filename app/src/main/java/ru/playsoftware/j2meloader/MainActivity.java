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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.FragmentContainerView;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;

import ru.playsoftware.j2meloader.applist.AppsListFragment;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.crashes.CrashReporter;
import ru.playsoftware.j2meloader.crashes.CrashReportsActivity;
import ru.playsoftware.j2meloader.crashes.MidletFailureRecovery;
import ru.playsoftware.j2meloader.crashes.ProcessExitStore;
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;
import ru.playsoftware.j2meloader.util.Constants;
import ru.playsoftware.j2meloader.util.EdgeToEdgeCompat;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.PickDirResultContract;
import ru.playsoftware.j2meloader.util.StoragePermissionHelper;
import ru.woesss.j2me.installer.InstallerDialog;

public class MainActivity extends AppCompatActivity {
	private static final long DIAGNOSTIC_RECOVERY_RETRY_MILLIS = 200L;
	private static final String STATE_PENDING_INSTALLERS = "MainActivity.pendingInstallers";
	private static final String INSTALLER_TAG = "installer";

	private final StoragePermissionHelper storagePermissionHelper =
			new StoragePermissionHelper(this, this::onPermissionResult);
	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult
	);
	private final ArrayDeque<Uri> pendingInstallerUris = new ArrayDeque<>();

	private LibraryViewModel libraryViewModel;
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

		libraryViewModel = new ViewModelProvider(this).get(LibraryViewModel.class);
		libraryViewModel.observe(this, ignored -> maybeShowPendingInstaller());
		restorePendingInstallerState(savedInstanceState);

		if (savedInstanceState == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.container, AppsListFragment.newInstance(null)).commit();
		}

		storagePermissionHelper.launch(this);
		setVolumeControlStream(AudioManager.STREAM_MUSIC);
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		ArrayList<String> pending = new ArrayList<>(pendingInstallerUris.size());
		for (Uri uri : pendingInstallerUris) {
			pending.add(uri.toString());
		}
		outState.putStringArrayList(STATE_PENDING_INSTALLERS, pending);
		super.onSaveInstanceState(outState);
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			maybeShowPendingInstaller();
			CrashReporter.requestDiagnosticRefresh(getApplication());
			maybeShowDiagnosticRecovery();
		}
	}

	/** Single READY gate used by initial intents, onNewIntent(), and the app-owned file picker. */
	public void requestInstaller(@Nullable Uri uri) {
		if (uri == null) return;
		pendingInstallerUris.addLast(uri);
		maybeShowPendingInstaller();
	}

	/** Called by InstallerDialog only when an external/file-picker request has reached a terminal UI outcome. */
	public void completeInstallerRequest(@Nullable Uri uri) {
		if (uri == null) return;
		pendingInstallerUris.removeFirstOccurrence(uri);
		Intent intent = getIntent();
		if (uri.equals(intent.getData())) {
			intent.setData(null);
		}
	}

	/** Called after the installer Fragment is dismissed so the next queued request cannot miss the tag-removal boundary. */
	public void onInstallerDialogDismissed() {
		getWindow().getDecorView().post(this::maybeShowPendingInstaller);
	}

	private void restorePendingInstallerState(@Nullable Bundle savedInstanceState) {
		if (savedInstanceState != null) {
			ArrayList<String> pending = savedInstanceState.getStringArrayList(STATE_PENDING_INSTALLERS);
			if (pending != null) {
				for (String value : pending) {
					if (value != null && !value.isEmpty()) {
						pendingInstallerUris.addLast(Uri.parse(value));
					}
				}
			}
			return;
		}
		Intent intent = getIntent();
		if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
			Uri uri = intent.getData();
			if (uri != null) pendingInstallerUris.addLast(uri);
		}
	}

	private void maybeShowPendingInstaller() {
		if (pendingInstallerUris.isEmpty() || isFinishing() || isDestroyed()) return;
		if (libraryViewModel == null || libraryViewModel.readyGeneration() == null) return;
		if (getSupportFragmentManager().isStateSaved()) return;
		if (getSupportFragmentManager().findFragmentByTag(INSTALLER_TAG) != null) return;
		if (mainComposeController != null && mainComposeController.isDialogVisible()) return;

		Uri uri = pendingInstallerUris.peekFirst();
		if (uri == null) return;
		// Do not dequeue here. Presentation is not consumption: process/activity recreation may happen
		// while the dialog is loading or converting. The dialog acknowledges only a terminal outcome.
		InstallerDialog.newInstance(uri).show(getSupportFragmentManager(), INSTALLER_TAG);
	}

	private void maybeShowDiagnosticRecovery() {
		if (isFinishing() || isDestroyed()) return;
		if (mainComposeController != null && mainComposeController.isDialogVisible()) return;
		if (getSupportFragmentManager().findFragmentByTag(INSTALLER_TAG) != null) return;

		if (!CrashReporter.isDiagnosticRefreshReady()) {
			scheduleDiagnosticRecoveryRetry();
			return;
		}

		MidletFailureRecovery.PendingFailure failure =
				MidletFailureRecovery.findPendingStoredFailure(this);
		if (failure != null) {
			String noticeId = "midlet:" + failure.getEventId();
			if (noticeId.equals(lastRecoveryNoticeId)) return;
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

		ProcessExitStore.PendingExit exit = ProcessExitStore.findPendingStoredExit(this);
		if (exit == null || exit.getId().equals(lastRecoveryNoticeId)) return;
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
		if (diagnosticRecoveryRetryScheduled || isFinishing() || isDestroyed()) return;
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
			libraryViewModel.setEmulatorDirectory(emulatorDir);
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
		if (uri == null || !"file".equals(uri.getScheme()) || uri.getPath() == null) {
			checkAndCreateDirs();
			return;
		}
		applyWorkDir(new File(uri.getPath()));
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
		// Do not wait for the SharedPreferences listener to establish the new generation.
		libraryViewModel.setEmulatorDirectory(path);
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		setIntent(intent);
		requestInstaller(intent.getData());
	}
}
