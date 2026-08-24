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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.compose.ui.platform.ComposeView;

import java.io.File;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import ru.playsoftware.j2meloader.R;
import ru.playsoftware.j2meloader.config.Config;
import javax.microedition.shell.MicroActivity;

/** Shows a non-interactive progress dialog while an installed MIDlet is automatically reconverted. */
public final class AutoReconversionActivity extends AppCompatActivity {
    private static final String TAG = AutoReconversionActivity.class.getSimpleName();

    private final CompositeDisposable disposables = new CompositeDisposable();
    private InstallerComposeController controller;
    private String appName;
    private Uri appUri;
    private File appDir;

    public static void start(Context context, String name, String path) {
        Intent intent = new Intent(context, AutoReconversionActivity.class)
                .setAction(Intent.ACTION_DEFAULT)
                .setData(Uri.parse(path))
                .putExtra(ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME, name);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Conversion is atomic and intentionally has no confirmation/cancel action. The
                // user sees an error only if the conversion itself fails.
            }
        });

        appName = getIntent().getStringExtra(
                ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME);
        if (appName == null || appName.trim().isEmpty()) appName = getString(R.string.app_name);
        appUri = getIntent().getData();
        appDir = resolveLocalDirectory(appUri);

        ComposeView composeView = new ComposeView(this);
        controller = InstallerComposeBridge.install(
                composeView,
                new InstallerActions() {
                    @Override
                    public void onInstall() {
                    }

                    @Override
                    public void onClose() {
                        finish();
                    }

                    @Override
                    public void onRunExisting() {
                    }

                    @Override
                    public void onLaunchInstalled() {
                    }
                },
                new InstallerUiState.Converting(
                        appName,
                        getString(R.string.reconverting_wait),
                        getString(R.string.converting_wait)));
        setContentView(composeView);
        configureWindow();

        if (appDir == null) {
            showError(new IllegalArgumentException("MIDlet path is not a local installed directory"));
            return;
        }
        startReconversion();
    }

    @Override
    protected void onDestroy() {
        disposables.dispose();
        controller = null;
        super.onDestroy();
    }

    private void startReconversion() {
        disposables.add(Single.fromCallable(() -> {
            AppReconverter.reconvert(appDir);
            if (AppReconverter.needsReconversion(appDir)) {
                throw new IllegalStateException("Reconversion completed without a compatible marker");
            }
            return Boolean.TRUE;
        })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> launchMidlet(), this::showError));
    }

    private void launchMidlet() {
        if (isFinishing() || isDestroyed()) return;
        Intent intent = new Intent(Intent.ACTION_DEFAULT, appUri, this, MicroActivity.class);
        intent.putExtra(
                ru.playsoftware.j2meloader.util.Constants.KEY_MIDLET_NAME,
                appName);
        startActivity(intent);
        finish();
    }

    private void showError(Throwable error) {
        Log.e(TAG, "Automatic MIDlet reconversion failed", error);
        if (controller == null || isFinishing() || isDestroyed()) return;
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = error.getClass().getSimpleName();
        }
        controller.showError(
                appName,
                getString(R.string.reconversion_error, detail),
                getString(R.string.close));
    }

    private void configureWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int margin = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                32,
                getResources().getDisplayMetrics());
        int maxWidth = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                560,
                getResources().getDisplayMetrics());
        int width = Math.max(1, Math.min(
                maxWidth,
                getResources().getDisplayMetrics().widthPixels - margin));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
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
