/*
 * Copyright 2020-2026 Yury Kharchenko
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

package ru.woesss.j2me.installer;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.ComponentDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;

import org.acra.ACRA;
import org.acra.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.applist.AppItem;
import io.github.h3nb.jlmodplus.applist.AppListModel;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.util.Constants;
import io.github.h3nb.jlmodplus.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

public class InstallerDialog extends DialogFragment {
	private static final String ARG_URI = "InstallerDialog.uri";
	private static final String ARG_ID = "InstallerDialog.id";
	private final CompositeDisposable compositeDisposable = new CompositeDisposable();

	private InstallerComposeView composeView;
	private AppListModel appListModel;
	private AppInstaller installer;
	private Dialog dialog;

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
			dismissAllowingStateLoss();
		}
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
		composeView = new InstallerComposeView(requireContext());
		dialog = new ComponentDialog(requireActivity());
		dialog.setContentView(composeView.getComposeView());
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
		}
		dialog.setOnShowListener(ignored -> {
			if (dialog.getWindow() != null) {
				dialog.getWindow().setLayout(
						android.view.WindowManager.LayoutParams.MATCH_PARENT,
						android.view.WindowManager.LayoutParams.WRAP_CONTENT
				);
			}
		});
		return dialog;
	}

	@Override
	public void onDismiss(@NonNull DialogInterface dialog) {
		super.onDismiss(dialog);
		composeView = null;
	}

	@Override
	public void onDestroy() {
		compositeDisposable.dispose();
		super.onDestroy();
	}

	@Override
	public void onStart() {
		super.onStart();
		if (installer != null) {
			return;
		}
		hideButtons();
		Bundle args = requireArguments();
		Uri uri = args.getParcelable(ARG_URI);
		if (uri != null) {
			installApp(null, uri);
			return;
		}
		int id = args.getInt(ARG_ID);
		reinstallApp(id);
	}

	private void installApp(File jar, Uri uri) {
		installer = new AppInstaller(jar, uri, appListModel);
		composeView.setNegativeButton(getString(android.R.string.cancel), () -> {
			installer.deleteTemp();
			installer.clearCache();
			dismiss();
		});
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void reinstallApp(int id) {
		installer = new AppInstaller(id, appListModel);
		composeView.setNegativeButton(getString(android.R.string.cancel), () -> {
			installer.deleteTemp();
			installer.clearCache();
			dismiss();
		});
		Disposable disposable = Single.create(installer::loadInfo)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void hideProgress() {
		composeView.setProgressVisible(false);
		composeView.setStatusText("");
	}

	private void showProgress() {
		composeView.setProgressVisible(true);
	}

	private void hideButtons() {
		composeView.clearButtons();
	}

	private void showButtons() {
		// Compose renders every button whose state has been configured.
	}

	private void convert() {
		Descriptor nd = installer.getNewDescriptor();
		SpannableStringBuilder info = nd.getInfo(requireActivity());
		composeView.setMessage(info);
		composeView.setStatusText(getString(R.string.converting_wait));
		showProgress();
		hideButtons();
		Disposable disposable = Single.create(installer::install)
				.subscribeOn(Schedulers.computation())
				.observeOn(AndroidSchedulers.mainThread())
				.subscribe(this::onProgress, this::onError);
		compositeDisposable.add(disposable);
	}

	private void alertConfirm(SpannableStringBuilder message, Runnable positive) {
		hideProgress();
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
		composeView.setMessage(message);
		composeView.setPositiveButton(getString(R.string.install), positive);
		showButtons();
	}

	private void onProgress(@NonNull Integer status) {
		if (!isAdded()) {
			return;
		}
		if (status == AppInstaller.STATUS_SUCCESS) {
			composeView.setProgressVisible(false);
			composeView.setStatusText(getString(R.string.install_done));
			AppItem app = installer.getExistsApp();
			composeView.setPositiveButton(getString(R.string.START_CMD), () -> {
				Config.startApp(requireContext(), app.getTitle(), app.getPathExt());
				dismiss();
			});
			composeView.setNegativeButton(getString(R.string.close), () -> dismiss());
			composeView.setNeutralButton(null, null);
			showButtons();
			return;
		}
		Descriptor nd = installer.getNewDescriptor();
		SpannableStringBuilder message;
		composeView.setNeutralButton(null, null);
		switch (status) {
			case AppInstaller.STATUS_NEW -> {
				if (installer.getJar() != null) {
					convert();
					return;
				}
				message = nd.getInfo(requireActivity());
			}
			case AppInstaller.STATUS_OLDER -> message = new SpannableStringBuilder(getString(
					R.string.reinstall_older,
					nd.getVersion(),
					installer.getCurrentVersion()));
			case AppInstaller.STATUS_EQUAL -> {
				message = new SpannableStringBuilder(getString(R.string.reinstall));
				AppItem app = installer.getExistsApp();
				composeView.setNeutralButton(getString(R.string.START_CMD), () -> {
					installer.clearCache();
					installer.deleteTemp();
					Config.startApp(requireContext(), app.getTitle(), app.getPathExt());
					dismiss();
				});
			}
			case AppInstaller.STATUS_NEWER -> message = new SpannableStringBuilder(getString(
					R.string.reinstall_newest,
					nd.getVersion(),
					installer.getCurrentVersion()));
			case AppInstaller.STATUS_UNMATCHED -> {
				SpannableStringBuilder info = installer.getManifest().getInfo(requireActivity());
				info.append(getString(R.string.install_jar_non_matched_jad));
				alertConfirm(info, () -> installApp(installer.getJar(), null));
				return;
			}
			case AppInstaller.STATUS_SAME -> {
				installer.clearCache();
				installer.deleteTemp();
				AppItem app = installer.getExistsApp();
				Config.startApp(getContext(), app.getTitle(), app.getPathExt());
				dismiss();
				return;
			}
			default -> throw new IllegalStateException("Unexpected value: " + status);
		}
		if (installer.getJar() == null) {
			message.append('\n').append(getString(R.string.warn_install_from_net));
		}
		composeView.setTitle(nd.getName());
		dialog.setCancelable(false);
		dialog.setCanceledOnTouchOutside(false);
		composeView.setMessage(message);
		composeView.setPositiveButton(getString(R.string.install), this::convert);
		composeView.setNegativeButton(getString(android.R.string.cancel), () -> {
			installer.deleteTemp();
			installer.clearCache();
			dismiss();
		});
		hideProgress();
		showButtons();
	}

	private void onError(Throwable e) {
		Log.e("Installer", e.toString(), e);
		ErrorReporter errorReporter = ACRA.getErrorReporter();
		Bundle args = getArguments();
		if (args != null) {
			String report = errorReporter.getCustomData(Constants.KEY_CRASH_ATTACHMENT);
			StringBuilder sb = new StringBuilder();
			if (report != null) {
				sb.append(report);
			}
			sb.append("\n====================Installer==================\n");
			Uri uri = args.getParcelable(ARG_URI);
			if (uri != null) {
				sb.append("from uri: ").append(uri).append('\n');
			}
			Descriptor descriptor = installer.getNewDescriptor();
			if (descriptor != null) {
				sb.append(Descriptor.MIDLET_NAME).append(": ").append(descriptor.getName()).append("\n");
				sb.append(Descriptor.MIDLET_VENDOR).append(": ").append(descriptor.getVendor()).append("\n");
				sb.append(Descriptor.MIDLET_VERSION).append(": ").append(descriptor.getVersion()).append("\n");
			}
			File jar = installer.getJar();
			if (jar != null) {
				String jarSize = Long.toString(jar.length());
				sb.append(Descriptor.MIDLET_JAR_SIZE).append(": ").append(jarSize).append("\n");
				try {
					byte[] bytes = FileUtils.getBytes(jar);
					byte[] sum = MessageDigest.getInstance("md5").digest(bytes);
					BigInteger bi = new BigInteger(1, sum);
					String jarHash = bi.toString(16);
					sb.append("JAR_HASH_MD5").append(": ").append(jarHash);
				} catch (IOException ignored) {
				} catch (NoSuchAlgorithmException ignored) {
				}
			}
			errorReporter.putCustomData(Constants.KEY_CRASH_ATTACHMENT, sb.toString());
		}

		errorReporter.handleException(e);
		installer.clearCache();
		installer.deleteTemp();
		if (!isAdded()) {
			return;
		}
		hideProgress();
		dismissAllowingStateLoss();
	}
}
