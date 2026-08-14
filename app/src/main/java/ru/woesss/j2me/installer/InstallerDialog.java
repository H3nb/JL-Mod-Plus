/*
 * Copyright 2020-2026 Yury Kharchenko
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

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
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
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.applist.AppItem;
import ru.playsoftware.j2meloader.applist.AppListModel;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.crashes.CrashReporter;
import ru.woesss.j2me.jar.Descriptor;

public class InstallerDialog extends DialogFragment {
	private static final String ARG_URI = "InstallerDialog.uri";
	private static final String ARG_ID = "InstallerDialog.id";
	private final CompositeDisposable compositeDisposable = new CompositeDisposable();

	private AppListModel appListModel;
	private AppInstaller installer;
	private InstallerComposeController composeController;
	private String installerTitle;
	private String currentTitle;
	private Runnable primaryAction;

	/**
	 * @param uri original uri from intent.
	 * @return A new instance of fragment InstallerDialog.
	 */
	public static InstallerDialog newInstance(Uri uri) {
		InstallerDialog fragment = new InstallerDialog();
		Bundle args = new Bundle();
		args.putParcelable(ARG_URI, uri);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	public static InstallerDialog newInstance(int id) {
		InstallerDialog fragment = new InstallerDialog();
		Bundle args = new Bundle();
		args.putInt(ARG_ID, id);
		fragment.setArguments(args);
		fragment.setCancelable(false);
		return fragment;
	}

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		appListModel = new ViewModelProvider(requireActivity()).get(AppListModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			// Preserve the legacy contract: an in-flight installer is not restored after recreation.
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
		return new AlertDialog.Builder(requireActivity(), getTheme())
				.setView(composeView)
				.setCancelable(false)
				.create();
	}

	@Override
	public void onDismiss(@NonNull DialogInterface dialog) {
		super.onDismiss(dialog);
		composeController = null;
	}

	@Override
	public void onDestroy() {
		compositeDisposable.dispose();
		super.onDestroy();
	}

	@Override
	public void onStart() {
		super.onStart();
		if (installer != null || composeController == null) {
			return;
		}
		Bundle args = requireArguments();
		Uri uri = args.getParcelable(ARG_URI);
		if (uri != null) {
			installApp(null, uri);
			return;
		}
		reinstallApp(args.getInt(ARG_ID));
	}

	private InstallerActions createActions() {
		return new InstallerActions() {
			@Override
			public void onInstall() {
				if (primaryAction != null) {
					primaryAction.run();
				}
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

	private void installApp(File jar, Uri uri) {
		installer = new AppInstaller(jar, uri, appListModel);
		primaryAction = this::convert;
		showLoading();
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void reinstallApp(int id) {
		installer = new AppInstaller(id, appListModel);
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
		if (installer == null || composeController == null || !isAdded()) {
			return;
		}
		Descriptor nd = installer.getNewDescriptor();
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
		if (!isAdded() || composeController == null) {
			return;
		}
		if (status == AppInstaller.STATUS_SUCCESS) {
			AppItem app = installer.getExistsApp();
			composeController.showSuccess(
					currentTitle,
					getString(R.string.install_done),
					getString(R.string.START_CMD),
					getString(R.string.close),
					app.getImagePathExt());
			return;
		}
		Descriptor nd = installer.getNewDescriptor();
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
					R.string.reinstall_older,
					nd.getVersion(),
					installer.getCurrentVersion());
			case AppInstaller.STATUS_EQUAL -> {
				message = getString(R.string.reinstall);
				runLabel = getString(R.string.START_CMD);
			}
			case AppInstaller.STATUS_NEWER -> message = getString(
					R.string.reinstall_newest,
					nd.getVersion(),
					installer.getCurrentVersion());
			case AppInstaller.STATUS_UNMATCHED -> {
				SpannableStringBuilder info = installer.getManifest().getInfo(requireActivity());
				info.append(getString(R.string.install_jar_non_matched_jad));
				File jar = installer.getJar();
				primaryAction = () -> installApp(jar, null);
				showConfirmation(installerTitle, info.toString(), null);
				return;
			}
			case AppInstaller.STATUS_SAME -> {
				launchExistingApp(true);
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

	private void closeInstaller() {
		if (installer != null) {
			installer.deleteTemp();
			installer.clearCache();
		}
		if (isAdded()) {
			dismiss();
		}
	}

	private void launchExistingApp(boolean cleanUp) {
		if (!isAdded() || installer == null) {
			return;
		}
		AppItem app = installer.getExistsApp();
		if (app == null) {
			return;
		}
		if (cleanUp) {
			installer.clearCache();
			installer.deleteTemp();
		}
		Config.startApp(requireContext(), app.getTitle(), app.getPathExt());
		dismiss();
	}

	private void onError(Throwable e) {
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
		if (installer != null) {
			installer.clearCache();
			installer.deleteTemp();
		}
		if (!isAdded()) {
			return;
		}
		dismissAllowingStateLoss();
	}
}
