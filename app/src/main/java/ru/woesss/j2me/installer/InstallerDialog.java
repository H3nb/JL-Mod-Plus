/*
 * Copyright 2020-2026 Yury Kharchenko
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

package ru.woesss.j2me.installer;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.util.Log;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import java.io.File;
import java.io.IOException;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.MainActivity;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.crashes.CrashReporter;
import ru.playsoftware.j2meloader.librarydb.LibraryAppBundleImporter;
import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;
import ru.woesss.j2me.jar.Descriptor;

public class InstallerDialog extends DialogFragment {
	private static final String ARG_URI = "InstallerDialog.uri";
	private static final String ARG_REQUEST_ID = "InstallerDialog.requestId";
	private static final String ARG_BUNDLE = "InstallerDialog.bundle";
	private static final String ARG_ID = "InstallerDialog.id";
	private static final String ARG_GENERATION = "InstallerDialog.generation";
	private static final String ARG_WORKDIR = "InstallerDialog.workdir";
	private static final String ARG_STORAGE_KEY = "InstallerDialog.storageKey";
	private static final long NO_GENERATION = Long.MIN_VALUE;
	private final CompositeDisposable compositeDisposable = new CompositeDisposable();

	private LibraryViewModel libraryViewModel;
	private AppInstaller installer;
	private InstallerComposeController composeController;
	private String installerTitle;
	private String currentTitle;
	private Runnable primaryAction;
	private LibraryAppBundleImporter.PreparedImport bundleImport;
	private boolean bundleWorkerInFlight;
	private boolean restoredInstance;

	/** Compatibility entry point for callers that do not participate in MainActivity request restore. */
	public static InstallerDialog newInstance(Uri uri) {
		return newExternalRequest(null, uri);
	}

	public static InstallerDialog newExternalRequest(@Nullable String requestId, Uri uri) {
		return newExternalRequest(requestId, uri, false);
	}

	public static InstallerDialog newExternalBundleRequest(@Nullable String requestId, Uri uri) {
		return newExternalRequest(requestId, uri, true);
	}

	private static InstallerDialog newExternalRequest(@Nullable String requestId, Uri uri, boolean bundle) {
		InstallerDialog fragment = new InstallerDialog();
		Bundle args = new Bundle();
		args.putParcelable(ARG_URI, uri);
		args.putBoolean(ARG_BUNDLE, bundle);
		if (requestId != null) args.putString(ARG_REQUEST_ID, requestId);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	public static InstallerDialog newInstance(long id, long expectedGeneration,
			String expectedWorkdirPath, String storageKey) {
		InstallerDialog fragment = new InstallerDialog();
		Bundle args = new Bundle();
		args.putLong(ARG_ID, id);
		args.putLong(ARG_GENERATION, expectedGeneration);
		args.putString(ARG_WORKDIR, expectedWorkdirPath);
		args.putString(ARG_STORAGE_KEY, storageKey);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		libraryViewModel = new ViewModelProvider(requireActivity()).get(LibraryViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		restoredInstance = savedInstanceState != null;
		if (restoredInstance) {
			// MainActivity owns durable external-request state and the Library READY gate. Never resume
			// an installer Fragment directly after Activity/process recreation: a pending request remains
			// in MainActivity's queue and will be presented again only after the active generation is READY.
			// A request that already committed was ACKed at STATUS_SUCCESS, so it will not replay.
			dismissAllowingStateLoss();
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		installerTitle = getString(R.string.installer_title);
		currentTitle = installerTitle;
		ComposeView composeView = new ComposeView(requireContext());
		composeController = InstallerComposeBridge.install(
				composeView,
				createActions(),
				new InstallerUiState.Loading(installerTitle, getString(R.string.loading_info)));
		Dialog dialog = new Dialog(requireContext(), getTheme());
		dialog.setContentView(composeView);
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
		dialog.setOnShowListener(ignored -> {
			Window window = dialog.getWindow();
			if (window == null) return;
			window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
			int margin = (int) TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());
			int maxWidthDp = getResources().getConfiguration().orientation
					== Configuration.ORIENTATION_LANDSCAPE ? 760 : 480;
			int maxWidth = (int) TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_DIP, maxWidthDp, getResources().getDisplayMetrics());
			int width = Math.min(maxWidth, getResources().getDisplayMetrics().widthPixels - margin);
			window.setLayout(Math.max(width, 1), WindowManager.LayoutParams.WRAP_CONTENT);
		});
		return dialog;
	}

	@Override
	public void onDismiss(@NonNull DialogInterface dialog) {
		super.onDismiss(dialog);
		composeController = null;
		Activity activity = getActivity();
		if (activity instanceof MainActivity) {
			((MainActivity) activity).onInstallerDialogDismissed();
		}
	}

	@Override
	public void onDestroy() {
		compositeDisposable.dispose();
		if (!bundleWorkerInFlight) cleanupBundleImport();
		super.onDestroy();
	}

	@Override
	public void onStart() {
		super.onStart();
		if (restoredInstance || installer != null || composeController == null) return;
		Bundle args = requireArguments();
		Uri uri = args.getParcelable(ARG_URI);
		if (uri != null) {
			if (isBundleRequest()) {
				prepareBundle(uri);
			} else {
				installApp(null, uri);
			}
			return;
		}
		long generation = args.getLong(ARG_GENERATION, NO_GENERATION);
		String workdir = args.getString(ARG_WORKDIR);
		String storageKey = args.getString(ARG_STORAGE_KEY);
		if (generation == NO_GENERATION || workdir == null || storageKey == null) {
			onError(new IllegalStateException("Explicit reinstall target is incomplete"));
			return;
		}
		reinstallApp(args.getLong(ARG_ID), generation, new File(workdir), storageKey);
	}

	private InstallerActions createActions() {
		return new InstallerActions() {
			@Override
			public void onInstall() {
				if (primaryAction != null) primaryAction.run();
			}

			@Override
			public void onClose() {
				closeInstaller();
			}

			@Override
			public void onRunExisting() {
				launchExistingApp(true);
			}

			@Override
			public void onLaunchInstalled() {
				launchExistingApp(false);
			}
		};
	}

	private void prepareBundle(Uri uri) {
		if (composeController != null) {
			composeController.showLoading(installerTitle, getString(R.string.library_import_preparing));
		}
		Context applicationContext = requireContext().getApplicationContext();
		bundleWorkerInFlight = true;
		Disposable disposable = Single.<LibraryAppBundleImporter.PreparedImport>create(emitter -> {
			LibraryAppBundleImporter.PreparedImport prepared = LibraryAppBundleImporter.prepare(
					applicationContext, uri);
			if (emitter.isDisposed()) {
				LibraryAppBundleImporter.cleanup(prepared);
				return;
			}
			emitter.onSuccess(prepared);
		})
				.subscribeOn(Schedulers.io())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(prepared -> {
				bundleImport = prepared;
				installApp(prepared.getJarFile(), null);
			}, this::onError);
		compositeDisposable.add(disposable);
	}

	private void installApp(File jar, Uri uri) {
		installer = new AppInstaller(jar, uri, libraryViewModel);
		primaryAction = this::convert;
		if (isBundleRequest()) bundleWorkerInFlight = true;
		showLoading();
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void reinstallApp(long id, long expectedGeneration, File expectedWorkdir,
			String storageKey) {
		installer = new AppInstaller(
				id,
				expectedGeneration,
				expectedWorkdir,
				storageKey,
				libraryViewModel);
		primaryAction = this::convert;
		showLoading();
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void showLoading() {
		if (composeController != null) {
			composeController.showLoading(installerTitle, getString(R.string.loading_info));
		}
	}

	private void convert() {
		if (installer == null || composeController == null || !isAdded()) return;
		Descriptor nd = installer.getNewDescriptor();
		if (isBundleRequest()) bundleWorkerInFlight = true;
		composeController.showConverting(
				currentTitle,
				nd.getInfo(requireActivity()).toString(),
				getString(R.string.converting_wait));
		Disposable disposable = Single.create(installer::install)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void onProgress(@NonNull Integer status) {
		if (!isAdded() || composeController == null) return;
		if (isBundleRequest()) bundleWorkerInFlight = false;
		if (status == AppInstaller.STATUS_SUCCESS) {
			if (isBundleRequest()) {
				restoreBundleToInstalled();
				return;
			}
			// The filesystem + Room commit is the durable consumption point. A process death while the
			// success screen is visible must not replay the same external install request.
			acknowledgeExternalRequest();
			composeController.showSuccess(
					currentTitle,
					getString(R.string.install_done),
					getString(R.string.START_CMD),
					getString(R.string.close),
					installer.getIconPath());
			return;
		}

		Descriptor nd = installer.getNewDescriptor();
		if (isBundleRequest()) {
			LibraryAppBundleImporter.PreparedImport prepared = bundleImport;
			if (prepared == null) {
				onError(new IllegalStateException("Prepared app bundle is unavailable"));
				return;
			}
			try {
				LibraryAppBundleImporter.validateSourceIdentity(prepared, nd.getName(), nd.getVendor());
			} catch (IOException error) {
				onError(error);
				return;
			}
		}

		String message;
		String runLabel = null;
		switch (status) {
			case AppInstaller.STATUS_NEW -> {
				if (installer.getJar() != null) {
					convert();
					return;
				}
				message = nd.getInfo(requireActivity()).toString();
			}
			case AppInstaller.STATUS_OLDER -> message = getString(
					R.string.reinstall_older, nd.getVersion(), installer.getCurrentVersion());
			case AppInstaller.STATUS_EQUAL -> {
				message = getString(R.string.reinstall);
				runLabel = isBundleRequest() ? null : getString(R.string.START_CMD);
			}
			case AppInstaller.STATUS_NEWER -> message = getString(
					R.string.reinstall_newest, nd.getVersion(), installer.getCurrentVersion());
			case AppInstaller.STATUS_UNMATCHED -> {
				SpannableStringBuilder info = installer.getManifest().getInfo(requireActivity());
				info.append(getString(R.string.install_jar_non_matched_jad));
				File jar = installer.getJar();
				primaryAction = () -> installApp(jar, null);
				showConfirmation(installerTitle, info.toString(), null);
				return;
			}
			case AppInstaller.STATUS_SAME -> {
				if (isBundleRequest()) {
					currentTitle = nd.getName();
					showBundleRestoreConfirmation();
				} else {
					launchExistingApp(true);
				}
				return;
			}
			default -> throw new IllegalStateException("Unexpected value: " + status);
		}
		if (installer.getJar() == null) {
			message = message + "\n" + getString(R.string.warn_install_from_net);
		}
		currentTitle = nd.getName();
		primaryAction = this::convert;
		showConfirmation(currentTitle, message, runLabel);
	}

	private void showConfirmation(String title, String message, String runLabel) {
		if (composeController != null) {
			composeController.showConfirmation(
					title,
					message,
					getString(R.string.install),
					getString(android.R.string.cancel),
					runLabel,
					installer == null ? null : installer.getIconPath());
		}
	}

	private boolean isBundleRequest() {
		Bundle args = getArguments();
		return args != null && args.getBoolean(ARG_BUNDLE, false);
	}

	private void showBundleRestoreConfirmation() {
		if (composeController == null || installer == null) return;
		primaryAction = this::restoreBundleToInstalled;
		composeController.showConfirmation(
				currentTitle,
				getString(R.string.library_import_restore_existing),
				getString(R.string.library_action_import_bundle),
				getString(android.R.string.cancel),
				null,
				installer.getIconPath());
	}

	private void restoreBundleToInstalled() {
		if (installer == null || bundleImport == null || composeController == null || !isAdded()) return;
		long installedId = installer.getInstalledId();
		if (installedId < 0L) {
			onBundleRestoreError(new IllegalStateException("Imported app identity is unavailable"));
			return;
		}
		bundleWorkerInFlight = true;
		composeController.showLoading(currentTitle, getString(R.string.library_import_restoring));
		libraryViewModel.restoreImportedBundle(installedId, bundleImport, (ignored, error) -> {
			bundleWorkerInFlight = false;
			if (error != null) {
				if (!isAdded() || composeController == null) {
					cleanupBundleImport();
					return;
				}
				onBundleRestoreError(error);
				return;
			}
			cleanupBundleImport();
			if (!isAdded() || composeController == null) return;
			acknowledgeExternalRequest();
			composeController.showSuccess(
					currentTitle,
					getString(R.string.library_import_done),
					getString(R.string.START_CMD),
					getString(R.string.close),
					installer.getIconPath());
		});
	}

	private void onBundleRestoreError(Throwable error) {
		Log.e("Installer", "Bundle restore failed", error);
		if (composeController == null) return;
		primaryAction = this::restoreBundleToInstalled;
		composeController.showConfirmation(
				currentTitle,
				getString(R.string.library_import_restore_failed),
				getString(R.string.library_retry),
				getString(R.string.close),
				null,
				installer == null ? null : installer.getIconPath());
	}

	private void cleanupBundleImport() {
		LibraryAppBundleImporter.PreparedImport prepared = bundleImport;
		bundleImport = null;
		if (prepared != null) {
			Schedulers.io().scheduleDirect(() -> LibraryAppBundleImporter.cleanup(prepared));
		}
	}

	private void closeInstaller() {
		if (installer != null) {
			installer.deleteTemp();
			installer.clearCache();
		}
		cleanupBundleImport();
		acknowledgeExternalRequest();
		if (isAdded()) dismiss();
	}

	private void launchExistingApp(boolean cleanUp) {
		if (!isAdded() || installer == null) return;
		String title = installer.getInstalledTitle();
		String path = installer.getInstalledPath();
		if (title == null || path == null) return;
		if (cleanUp) {
			installer.clearCache();
			installer.deleteTemp();
		}
		cleanupBundleImport();
		acknowledgeExternalRequest();
		Config.startApp(requireContext(), title, path);
		dismiss();
	}

	private void acknowledgeExternalRequest() {
		Bundle args = getArguments();
		Uri uri = args == null ? null : args.getParcelable(ARG_URI);
		if (uri == null) return;
		String requestId = args.getString(ARG_REQUEST_ID);
		Activity activity = getActivity();
		if (activity instanceof MainActivity) {
			((MainActivity) activity).completeInstallerRequest(requestId, uri);
		}
	}

	private void onError(Throwable e) {
		bundleWorkerInFlight = false;
		Log.e("Installer", e.toString(), e);
		Bundle args = getArguments();
		Uri uri = args == null ? null : args.getParcelable(ARG_URI);
		Descriptor descriptor = installer == null ? null : installer.getNewDescriptor();
		File jar = installer == null ? null : installer.getJar();
		CrashReporter.reportInstallerFailure(
				e,
				uri == null ? null : uri.getScheme(),
				descriptor == null ? null : descriptor.getName(),
				descriptor == null ? null : descriptor.getVendor(),
				descriptor == null ? null : descriptor.getVersion(),
				jar == null ? null : Long.toString(jar.length())
		);
		if (!isAdded() || composeController == null) {
			cleanupInstallerResources();
			acknowledgeExternalRequest();
			if (isAdded()) dismissAllowingStateLoss();
			return;
		}

		// Keep the dialog visible so a malformed or damaged bundle is reported to the user instead
		// of silently disappearing. Release temporary installer files now; closing the error surface
		// remains the acknowledgement point for the pending external request.
		cleanupInstallerResources();
		primaryAction = null;
		composeController.showError(
			isBundleRequest() ? getString(R.string.library_import_error_title) : getString(R.string.error),
			isBundleRequest()
					? getString(R.string.library_import_invalid_bundle)
					: getString(R.string.installer_error_message),
			getString(R.string.close));
	}

	private void cleanupInstallerResources() {
		if (installer != null) {
			installer.clearCache();
			installer.deleteTemp();
		}
		cleanupBundleImport();
	}
}
