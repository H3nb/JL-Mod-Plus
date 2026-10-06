// SPDX-License-Identifier: Apache-2.0
package io.github.h3nb.jlmodplus.installer;

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

import com.android.dx.command.dexer.ConversionResult;

import java.io.File;
import java.io.IOException;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import io.github.h3nb.jlmodplus.R;
import io.github.h3nb.jlmodplus.config.Config;
import io.github.h3nb.jlmodplus.config.ConfigActivity;
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
    private volatile boolean cancelRequested;

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
                        requestClose();
                    }

                    @Override
                    public void onRunExisting() {
                    }

                    @Override
                    public void onLaunchInstalled() {
                        launchMidlet();
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
        cancelRequested = true;
        // Do not interrupt an in-flight DX conversion by disposing its subscription. The
        // converter owns a worker pool and is cancellation-aware at safe filesystem boundaries;
        // disposing here would make a late conversion failure undeliverable during Activity
        // teardown. The completed callback releases the subscription normally.
        if (!running) disposables.dispose();
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
        cancelRequested = false;
        controller.showConverting(appName, getString(R.string.reconverting_wait),
                getString(R.string.converting_wait));
        disposables.add(Single.fromCallable(() -> {
            ConversionResult result = AppReconverter.reconvert(appDir, () -> cancelRequested);
            if (AppReconverter.needsReconversion(appDir)) {
                throw new IllegalStateException("Reconversion completed without a compatible marker");
            }
            return result;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    running = false;
                    if (cancelRequested) {
                        if (isAdded()) dismissAllowingStateLoss();
                        return;
                    }
                    if (!isAdded() || controller == null) return;
                    ConversionWarningFormatter.Presentation warning =
                            ConversionWarningFormatter.forResult(requireContext(), result);
                    if (warning == null) {
                        launchMidlet();
                        return;
                    }
                    controller.showSuccess(
                            appName,
                            getString(R.string.reconversion_done_with_warning),
                            getString(R.string.START_CMD),
                            getString(R.string.close),
                            null,
                            warning.getSummary(),
                            warning.getDetails(),
                            warning.getCopyDetails());
                }, this::showError));
    }

    private void launchMidlet() {
        if (!isAdded()) return;
        FragmentActivity host = requireActivity();
        Intent intent = new Intent(Intent.ACTION_DEFAULT, appUri, requireContext(), MicroActivity.class);
        intent.putExtra(io.github.h3nb.jlmodplus.util.Constants.KEY_MIDLET_NAME, appName);
        startActivity(intent);
        dismissAllowingStateLoss();
        if (host instanceof ConfigActivity) host.finish();
    }

    private void showError(Throwable error) {
        running = false;
        if (cancelRequested) {
            if (isAdded()) dismissAllowingStateLoss();
            return;
        }
        Log.e(TAG, "Automatic MIDlet reconversion failed", error);
        if (!isAdded() || controller == null) return;
        String details;
        if (error instanceof ConversionFailureException) {
            details = ConversionWarningFormatter.technicalReport(
                    ((ConversionFailureException) error).getResult());
        } else {
            details = InstallerFailure.details(error);
        }
        controller.showError(
                appName,
                getString(R.string.reconversion_error_summary),
                getString(R.string.close),
                getString(R.string.library_retry),
                details);
    }

    private void requestClose() {
        if (!running) {
            dismissAllowingStateLoss();
            return;
        }
        cancelRequested = true;
        if (controller != null) {
            controller.showConverting(appName, getString(R.string.reconverting_wait),
                    getString(R.string.installer_cancel_pending));
        }
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
