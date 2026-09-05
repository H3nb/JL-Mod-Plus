/*
 * Copyright 2026 JL-Mod Plus contributors
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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import java.io.File;
import java.io.IOException;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ConfigActivity;
import javax.microedition.shell.MicroActivity;

/** Compatibility conversion shown over the current screen using the installer popup host. */
public final class AutoReconversionDialog extends DialogFragment {
    private static final String TAG = AutoReconversionDialog.class.getSimpleName();
    private static final String FRAGMENT_TAG = "AutoReconversionDialog";
    private static final String ARG_NAME = "name";
    private static final String ARG_PATH = "path";

    private final CompositeDisposable disposables = new CompositeDisposable();
    private InstallerComposeController controller;
    private String appName;
    private Uri appUri;
    private File appDir;
    private boolean started;
    private boolean running;

    public static boolean show(FragmentActivity activity, String name, String path) {
        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.isStateSaved()) return false;
        if (manager.findFragmentByTag(FRAGMENT_TAG) != null) return true;

        AutoReconversionDialog fragment = new AutoReconversionDialog();
        Bundle args = new Bundle();
        args.putString(ARG_NAME, name);
        args.putString(ARG_PATH, path);
        fragment.setArguments(args);
        fragment.setCancelable(false);
        fragment.show(manager, FRAGMENT_TAG);
        return true;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        appName = args.getString(ARG_NAME);
        if (appName == null || appName.trim().isEmpty()) appName = getString(R.string.app_name);
        String path = args.getString(ARG_PATH);
        appUri = path == null ? null : Uri.parse(path);
        appDir = resolveLocalDirectory(appUri);

        ComposeView composeView = new ComposeView(requireContext());
        controller = InstallerComposeBridge.install(
                composeView,
                new InstallerActions() {
                    @Override
                    public void onInstall() {
                        startReconversion();
                    }

                    @Override
                    public void onClose() {
                        dismissAllowingStateLoss();
                    }

                    @Override
                    public void onRunExisting() {
                    }

                    @Override
                    public void onLaunchInstalled() {
                    }
                },
                // Reinstall uses this same renderer while converting; only the explanation differs.
                new InstallerUiState.Converting(
                        appName,
                        getString(R.string.reconverting_wait),
                        getString(R.string.converting_wait)));

        Dialog dialog = new Dialog(requireContext(), getTheme());
        dialog.setContentView(composeView);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        return dialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        Window window = dialog == null ? null : dialog.getWindow();
        InstallerWindowCompat.configure(window);
        if (started) return;
        started = true;
        if (appDir == null) {
            showError(new IllegalArgumentException("MIDlet path is not a local installed directory"));
            return;
        }
        startReconversion();
    }

    @Override
    public void onDestroy() {
        disposables.dispose();
        controller = null;
        super.onDestroy();
    }

    private void startReconversion() {
        if (running || appDir == null) return;
        if (!AppReconverter.hasRetainedSource(appDir)) {
            if (AppReconverter.hasUsableConvertedPayload(appDir)) {
                launchMidlet();
            } else {
                showError(new IOException("Retained MIDlet JAR and converted payload are unavailable"));
            }
            return;
        }
        running = true;
        controller.showConverting(appName, getString(R.string.reconverting_wait),
                getString(R.string.converting_wait));
        disposables.add(Single.fromCallable(() -> {
            AppReconverter.reconvert(appDir);
            if (AppReconverter.needsReconversion(appDir)) {
                throw new IllegalStateException("Reconversion completed without a compatible marker");
            }
            return Boolean.TRUE;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> launchMidlet(), this::showError));
    }

    private void launchMidlet() {
        if (!isAdded()) return;
        FragmentActivity host = requireActivity();
        Intent intent = new Intent(Intent.ACTION_DEFAULT, appUri, requireContext(), MicroActivity.class);
        intent.putExtra(ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME, appName);
        startActivity(intent);
        dismissAllowingStateLoss();
        if (host instanceof ConfigActivity) host.finish();
    }

    private void showError(Throwable error) {
        running = false;
        Log.e(TAG, "Automatic MIDlet reconversion failed", error);
        if (!isAdded() || controller == null) return;
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = error.getClass().getSimpleName();
        }
        controller.showConfirmation(
                appName,
                getString(R.string.reconversion_error, detail),
                getString(R.string.library_retry),
                getString(R.string.close), null, null);
    }

    @Nullable
    private static File resolveLocalDirectory(@Nullable Uri uri) {
        if (uri == null) return null;
        if (uri.getScheme() == null) return new File(uri.toString());
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath());
        }
        return null;
    }
}
