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

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import org.acra.ACRA;
import org.acra.ErrorReporter;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.applist.AppItem;
import io.github.h3nb.jlmodplus.applist.AppListModel;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.util.Constants;
import io.github.h3nb.jlmodplus.util.FileUtils;
import ru.woesss.j2me.jar.Descriptor;

/** Direct Compose Activity for MIDlet installation and conversion. */
public class InstallerActivity extends AppCompatActivity {
	public static final String EXTRA_URI = "InstallerActivity.uri";
	public static final String EXTRA_ID = "InstallerActivity.id";

	private final ThreadPoolExecutor operationExecutor = new ThreadPoolExecutor(
			1,
			1,
			0L,
			TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(1),
			Executors.defaultThreadFactory(),
			new ThreadPoolExecutor.AbortPolicy()
	);
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private Future<?> operation;
	private volatile long operationGeneration;
	private InstallerUiState composeState;
	private AppListModel appListModel;
	private AppInstaller installer;

	public static Intent newIntent(@NonNull android.content.Context context, @NonNull Uri uri) {
		return new Intent(context, InstallerActivity.class).putExtra(EXTRA_URI, uri);
	}

	public static Intent newIntent(@NonNull android.content.Context context, int id) {
		return new Intent(context, InstallerActivity.class).putExtra(EXTRA_ID, id);
	}

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (savedInstanceState != null) {
			finish();
			return;
		}
		appListModel = new ViewModelProvider(this).get(AppListModel.class);
		appListModel.setEmulatorDirectory(Config.getEmulatorDir());
		composeState = new InstallerUiState(this);
		InstallerComposeHost.install(this, composeState);
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (installer != null) {
			return;
		}
		Uri uri = getIntent().getParcelableExtra(EXTRA_URI);
		if (uri != null) {
			installApp(null, uri);
			return;
		}
		reinstallApp(getIntent().getIntExtra(EXTRA_ID, -1));
	}

	private void installApp(File jar, Uri uri) {
		installer = new AppInstaller(jar, uri, appListModel);
		composeState.setNegativeButton(getString(android.R.string.cancel), this::cancelAndFinish);
		startInstallerOperation(installer::loadInfo);
	}

	private void reinstallApp(int id) {
		installer = new AppInstaller(id, appListModel);
		composeState.setNegativeButton(getString(android.R.string.cancel), this::cancelAndFinish);
		startInstallerOperation(installer::loadInfo);
	}

	@FunctionalInterface
	private interface InstallerOperation {
		void run(AppInstaller.StatusCallback callback) throws Exception;
	}

	private void startInstallerOperation(InstallerOperation installerOperation) {
		cancelInstallerOperation();
		long generation = ++operationGeneration;
		try {
			operation = operationExecutor.submit(() -> {
				try {
					installerOperation.run(status -> postStatus(generation, status));
				} catch (Throwable error) {
					postError(generation, error);
				}
			});
		} catch (RejectedExecutionException error) {
			postError(generation, error);
		}
	}

	private void postStatus(long generation, int status) {
		if (generation != operationGeneration) {
			return;
		}
		mainHandler.post(() -> {
			if (generation == operationGeneration && !isFinishing() && !isDestroyed()) {
				onProgress(status);
			}
		});
	}

	private void postError(long generation, Throwable error) {
		if (generation != operationGeneration) {
			return;
		}
		mainHandler.post(() -> {
			if (generation == operationGeneration) {
				onError(error);
			}
		});
	}

	private void cancelInstallerOperation() {
		++operationGeneration;
		if (operation != null) {
			operation.cancel(true);
			operationExecutor.getQueue().remove(operation);
			operationExecutor.purge();
			operation = null;
		}
	}

	private void cancelAndFinish() {
		cancelInstallerOperation();
		if (installer != null) {
			installer.deleteTemp();
			installer.clearCache();
		}
		finish();
	}

	private void hideProgress() {
		composeState.setProgressVisible(false);
		composeState.setStatusText("");
	}

	private void showProgress() {
		composeState.setProgressVisible(true);
	}

	private void hideButtons() {
		composeState.clearButtons();
	}

	private void showButtons() {
		// Compose renders every button whose state has been configured.
	}

	private void convert() {
		Descriptor descriptor = installer.getNewDescriptor();
		SpannableStringBuilder info = descriptor.getInfo(this);
		composeState.setMessage(info);
		composeState.setStatusText(getString(R.string.converting_wait));
		showProgress();
		hideButtons();
		startInstallerOperation(installer::install);
	}

	private void chooseTransformMode() {
		String[] labels = {
				getString(R.string.conversion_mode_normal),
				getString(R.string.conversion_mode_speedhack),
				getString(R.string.conversion_mode_memory_editor),
				getString(R.string.conversion_mode_speedhack_memory_editor)
		};
		composeState.showTransformModeDialog(
				labels,
				DexTransformMode.SPEEDHACK.ordinal(),
				getString(R.string.install),
				getString(android.R.string.cancel),
				index -> {
					if (index >= 0 && index < DexTransformMode.values().length) {
						installer.setTransformMode(DexTransformMode.values()[index]);
						convert();
					}
				}
		);
	}

	private void alertConfirm(SpannableStringBuilder message, Runnable positive) {
		hideProgress();
		composeState.setMessage(message);
		composeState.setPositiveButton(getString(R.string.install), positive);
		showButtons();
	}

	private void onProgress(@NonNull Integer status) {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		if (status == AppInstaller.STATUS_SUCCESS) {
			composeState.setProgressVisible(false);
			composeState.setStatusText(getString(R.string.install_done));
			AppItem app = installer.getExistsApp();
			composeState.setPositiveButton(getString(R.string.START_CMD), () -> {
				Config.startApp(this, app.getTitle(), app.getPathExt());
				finish();
			});
			composeState.setNegativeButton(getString(R.string.close), this::finish);
			composeState.setNeutralButton(null, null);
			showButtons();
			return;
		}
		Descriptor descriptor = installer.getNewDescriptor();
		SpannableStringBuilder message;
		composeState.setNeutralButton(null, null);
		switch (status) {
			case AppInstaller.STATUS_NEW -> {
				if (installer.getJar() != null) {
					chooseTransformMode();
					return;
				}
				message = descriptor.getInfo(this);
			}
			case AppInstaller.STATUS_OLDER -> message = new SpannableStringBuilder(getString(
					R.string.reinstall_older, descriptor.getVersion(), installer.getCurrentVersion()));
			case AppInstaller.STATUS_EQUAL -> {
				message = new SpannableStringBuilder(getString(R.string.reinstall));
				AppItem app = installer.getExistsApp();
				composeState.setNeutralButton(getString(R.string.START_CMD), () -> {
					installer.clearCache();
					installer.deleteTemp();
					Config.startApp(this, app.getTitle(), app.getPathExt());
					finish();
				});
			}
			case AppInstaller.STATUS_NEWER -> message = new SpannableStringBuilder(getString(
					R.string.reinstall_newest, descriptor.getVersion(), installer.getCurrentVersion()));
			case AppInstaller.STATUS_UNMATCHED -> {
				SpannableStringBuilder info = installer.getManifest().getInfo(this);
				info.append(getString(R.string.install_jar_non_matched_jad));
				alertConfirm(info, () -> installApp(installer.getJar(), null));
				return;
			}
			case AppInstaller.STATUS_SAME -> {
				installer.clearCache();
				installer.deleteTemp();
				AppItem app = installer.getExistsApp();
				Config.startApp(this, app.getTitle(), app.getPathExt());
				finish();
				return;
			}
			default -> throw new IllegalStateException("Unexpected value: " + status);
		}
		if (installer.getJar() == null) {
			message.append('\n').append(getString(R.string.warn_install_from_net));
		}
		composeState.setTitle(descriptor.getName());
		composeState.setMessage(message);
		composeState.setPositiveButton(getString(R.string.install), this::chooseTransformMode);
		composeState.setNegativeButton(getString(android.R.string.cancel), this::cancelAndFinish);
		hideProgress();
		showButtons();
	}

	private void onError(Throwable error) {
		Log.e("Installer", error.toString(), error);
		ErrorReporter errorReporter = ACRA.getErrorReporter();
		String report = errorReporter.getCustomData(Constants.KEY_CRASH_ATTACHMENT);
		StringBuilder builder = new StringBuilder();
		if (report != null) {
			builder.append(report);
		}
		builder.append("\n====================Installer==================\n");
		Uri uri = getIntent().getParcelableExtra(EXTRA_URI);
		if (uri != null) {
			builder.append("from uri: ").append(uri).append('\n');
		}
		Descriptor descriptor = installer == null ? null : installer.getNewDescriptor();
		if (descriptor != null) {
			builder.append(Descriptor.MIDLET_NAME).append(": ").append(descriptor.getName()).append("\n");
			builder.append(Descriptor.MIDLET_VENDOR).append(": ").append(descriptor.getVendor()).append("\n");
			builder.append(Descriptor.MIDLET_VERSION).append(": ").append(descriptor.getVersion()).append("\n");
		}
		File jar = installer == null ? null : installer.getJar();
		if (jar != null) {
			builder.append(Descriptor.MIDLET_JAR_SIZE).append(": ").append(jar.length()).append("\n");
			try {
				byte[] bytes = FileUtils.getBytes(jar);
				byte[] sum = MessageDigest.getInstance("md5").digest(bytes);
				BigInteger value = new BigInteger(1, sum);
				builder.append("JAR_HASH_MD5").append(": ").append(value.toString(16));
			} catch (IOException | NoSuchAlgorithmException ignored) {
			}
		}
		errorReporter.putCustomData(Constants.KEY_CRASH_ATTACHMENT, builder.toString());
		errorReporter.handleException(error);
		if (installer != null) {
			installer.clearCache();
			installer.deleteTemp();
		}
		finish();
	}

	@Override
	protected void onDestroy() {
		cancelInstallerOperation();
		operationExecutor.shutdownNow();
		if (installer != null) {
			installer.deleteTemp();
			installer.clearCache();
		}
		super.onDestroy();
	}
}
