/*
 * Modified by JL-Mod Plus contributors; original upstream attribution is retained.
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

import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import io.reactivex.Single;
import io.reactivex.disposables.Disposable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
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
import ru.woesss.j2me.installer.BulkInstallerDialog;
import ru.playsoftware.j2meloader.librarydb.LibraryAppBundleImporter;

public class MainActivity extends AppCompatActivity {
	private static final long DIAGNOSTIC_RECOVERY_RETRY_MILLIS = 200L;
	private static final String STATE_PENDING_INSTALLER_IDS = "MainActivity.pendingInstallerIds";
	private static final String STATE_PENDING_INSTALLER_URIS = "MainActivity.pendingInstallerUris";
	private static final String STATE_PENDING_INSTALLER_BUNDLES = "MainActivity.pendingInstallerBundles";
	private static final String PREF_ACKED_INSTALLER_REQUEST_IDS =
			"MainActivity.ackedInstallerRequestIds";
	private static final String INSTALLER_TAG = "installer";

	private static final class PendingInstallerRequest {
		final String id;
		final Uri uri;
		final boolean bundle;

		PendingInstallerRequest(String id, Uri uri, boolean bundle) {
			this.id = id;
			this.uri = uri;
			this.bundle = bundle;
		}
	}

	private final StoragePermissionHelper storagePermissionHelper =
			new StoragePermissionHelper(this, this::onPermissionResult);
	private final ActivityResultLauncher<String> openDirLauncher = registerForActivityResult(
			new PickDirResultContract(),
			this::onPickDirResult
	);
	private final ArrayDeque<PendingInstallerRequest> pendingInstallerRequests = new ArrayDeque<>();

	private LibraryViewModel libraryViewModel;
	private MainActivityComposeController mainComposeController;
	private String lastRecoveryNoticeId;
	private boolean diagnosticRecoveryRetryScheduled;
	private boolean installerStateSnapshotExists;
	private boolean bundleRoutingInFlight;
	private Disposable bundleRouting;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		updateRecentTaskDescription();
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
	protected void onResume() {
		super.onResume();
		updateRecentTaskDescription();
		if (libraryViewModel != null) libraryViewModel.refreshPlayStats();
	}

	private void updateRecentTaskDescription() {
		setTaskDescription(new ActivityManager.TaskDescription(getString(R.string.app_name)));
	}

	@Override
	protected void onDestroy() {
		if (bundleRouting != null) {
			bundleRouting.dispose();
			bundleRouting = null;
		}
		bundleRoutingInFlight = false;
		super.onDestroy();
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		ArrayList<String> ids = new ArrayList<>(pendingInstallerRequests.size());
		ArrayList<String> uris = new ArrayList<>(pendingInstallerRequests.size());
		boolean[] bundles = new boolean[pendingInstallerRequests.size()];
		int requestIndex = 0;
		for (PendingInstallerRequest request : pendingInstallerRequests) {
			ids.add(request.id);
			uris.add(request.uri.toString());
			bundles[requestIndex++] = request.bundle;
		}
		outState.putStringArrayList(STATE_PENDING_INSTALLER_IDS, ids);
		outState.putStringArrayList(STATE_PENDING_INSTALLER_URIS, uris);
		outState.putBooleanArray(STATE_PENDING_INSTALLER_BUNDLES, bundles);

		// This snapshot now reflects every ACK that happened before this callback. Only ACKs that
		// happen after the snapshot need a durable tombstone to suppress stale-state replay.
		installerStateSnapshotExists = true;
		installerPreferences().edit().remove(PREF_ACKED_INSTALLER_REQUEST_IDS).apply();
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
		enqueueInstaller(uri, false);
	}

	public void requestBundleInstaller(@Nullable Uri uri) {
		enqueueInstaller(uri, true);
	}

	private void enqueueInstaller(@Nullable Uri uri, boolean bundle) {
		if (uri == null) return;
		pendingInstallerRequests.addLast(
				new PendingInstallerRequest(UUID.randomUUID().toString(), uri, bundle));
		maybeShowPendingInstaller();
	}

	/** Called by InstallerDialog only when an external/file-picker request has reached a terminal UI outcome. */
	public void completeInstallerRequest(@Nullable String requestId, @Nullable Uri uri) {
		PendingInstallerRequest completed = null;
		Iterator<PendingInstallerRequest> iterator = pendingInstallerRequests.iterator();
		while (iterator.hasNext()) {
			PendingInstallerRequest candidate = iterator.next();
			boolean matches = requestId != null
					? requestId.equals(candidate.id)
					: uri != null && uri.equals(candidate.uri);
			if (matches) {
				completed = candidate;
				iterator.remove();
				break;
			}
		}
		if (completed != null && installerStateSnapshotExists) {
			recordAcknowledgedInstallerRequest(completed.id);
		}
		if (completed != null && completed.bundle
				&& "content".equals(completed.uri.getScheme())) {
			try {
				getContentResolver().releasePersistableUriPermission(
						completed.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
			} catch (SecurityException | IllegalArgumentException ignored) {
				// The provider may have supplied only a transient grant.
			}
		}

		Intent intent = getIntent();
		if (uri != null && uri.equals(intent.getData())) {
			intent.setData(null);
		}
	}

	/** Called after the installer Fragment is dismissed so the next queued request cannot miss the tag-removal boundary. */
	public void onInstallerDialogDismissed() {
		getWindow().getDecorView().post(this::maybeShowPendingInstaller);
	}

	private void restorePendingInstallerState(@Nullable Bundle savedInstanceState) {
		SharedPreferences preferences = installerPreferences();
		if (savedInstanceState != null) {
			installerStateSnapshotExists = true;
			ArrayList<String> ids = savedInstanceState.getStringArrayList(STATE_PENDING_INSTALLER_IDS);
			ArrayList<String> uris = savedInstanceState.getStringArrayList(STATE_PENDING_INSTALLER_URIS);
			boolean[] bundles = savedInstanceState.getBooleanArray(STATE_PENDING_INSTALLER_BUNDLES);
			Set<String> acknowledged = preferences.getStringSet(
					PREF_ACKED_INSTALLER_REQUEST_IDS,
					new HashSet<>());
			if (ids != null && uris != null) {
				int count = Math.min(ids.size(), uris.size());
				for (int i = 0; i < count; i++) {
					String id = ids.get(i);
					String value = uris.get(i);
					if (id != null && !id.isEmpty() && value != null && !value.isEmpty()
							&& !acknowledged.contains(id)) {
						boolean bundle = bundles != null && i < bundles.length && bundles[i];
					pendingInstallerRequests.addLast(
								new PendingInstallerRequest(id, Uri.parse(value), bundle));
					}
				}
			}
			return;
		}

		// A genuinely fresh Activity cannot consume an old saved-state snapshot, so ACK tombstones
		// from an earlier Activity lifetime are no longer useful and must not suppress a new request.
		installerStateSnapshotExists = false;
		preferences.edit().remove(PREF_ACKED_INSTALLER_REQUEST_IDS).apply();
		Intent intent = getIntent();
		if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
			requestInstaller(intent.getData());
		}
	}

	private void maybeShowPendingInstaller() {
		if (pendingInstallerRequests.isEmpty() || isFinishing() || isDestroyed()) return;
		if (libraryViewModel == null || libraryViewModel.readyGeneration() == null) return;
		if (getSupportFragmentManager().isStateSaved()) return;
		if (getSupportFragmentManager().findFragmentByTag(INSTALLER_TAG) != null ||
				getSupportFragmentManager().findFragmentByTag(BulkInstallerDialog.TAG) != null ||
				bundleRoutingInFlight) return;
		if (mainComposeController != null && mainComposeController.isDialogVisible()) return;

		PendingInstallerRequest request = pendingInstallerRequests.peekFirst();
		if (request == null) return;
		// Do not dequeue here. Presentation is not consumption: process/activity recreation may happen
		// while the dialog is loading or converting. The dialog acknowledges only a terminal outcome.
		if (!request.bundle) {
			InstallerDialog installer = InstallerDialog.newExternalRequest(request.id, request.uri);
			installer.show(getSupportFragmentManager(), INSTALLER_TAG);
			return;
		}

		bundleRoutingInFlight = true;
		bundleRouting = Single.fromCallable(() ->
				LibraryAppBundleImporter.requiresBulkImport(getApplicationContext(), request.uri))
				.subscribeOn(Schedulers.io())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(requiresBulk -> {
					bundleRoutingInFlight = false;
					bundleRouting = null;
					if (isFinishing() || isDestroyed() ||
							getSupportFragmentManager().isStateSaved() ||
							pendingInstallerRequests.peekFirst() != request) {
						maybeShowPendingInstaller();
						return;
					}
					if (requiresBulk) {
						BulkInstallerDialog.newBundle(request.uri, request.id)
								.show(getSupportFragmentManager(), BulkInstallerDialog.TAG);
					} else {
						InstallerDialog.newExternalBundleRequest(request.id, request.uri)
								.show(getSupportFragmentManager(), INSTALLER_TAG);
					}
				}, error -> {
					bundleRoutingInFlight = false;
					bundleRouting = null;
					if (isFinishing() || isDestroyed() ||
							getSupportFragmentManager().isStateSaved() ||
							pendingInstallerRequests.peekFirst() != request) {
						maybeShowPendingInstaller();
						return;
					}
					// Keep the existing single-app error surface for malformed/legacy bundles; it owns
					// request acknowledgement and presents the validation failure consistently.
					InstallerDialog.newExternalBundleRequest(request.id, request.uri)
							.show(getSupportFragmentManager(), INSTALLER_TAG);
				});
	}

	private void recordAcknowledgedInstallerRequest(String requestId) {
		SharedPreferences preferences = installerPreferences();
		Set<String> existing = preferences.getStringSet(
				PREF_ACKED_INSTALLER_REQUEST_IDS,
				new HashSet<>());
		HashSet<String> updated = new HashSet<>(existing);
		updated.add(requestId);
		preferences.edit().putStringSet(PREF_ACKED_INSTALLER_REQUEST_IDS, updated).apply();
	}

	private SharedPreferences installerPreferences() {
		return PreferenceManager.getDefaultSharedPreferences(this);
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
			if (!FileUtils.initWorkDir(dir)) {
				alertDirCannotCreate(emulatorDir);
				return;
			}
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
