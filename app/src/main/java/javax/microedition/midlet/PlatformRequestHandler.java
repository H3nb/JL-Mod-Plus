/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.midlet;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import javax.microedition.shell.MicroActivity;

import io.github.h3nb.jlmodplus.MainActivity;
import io.github.h3nb.jlmodplus.R;

/** An ephemeral user choice. It never owns an installation or the guest lifecycle. */
public final class PlatformRequestHandler {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static long generation;
    private static Runnable pendingDismiss;

    public static void cancelPending() {
        UI.post(() -> {
            generation++;
            dismissPending();
        });
    }

    private static void dismissPending() {
        Runnable dismiss = pendingDismiss;
        pendingDismiss = null;
        if (dismiss != null) dismiss.run();
    }

    public static void offerPackage(MicroActivity host, Intent install, Intent external) {
        UI.post(() -> {
            if (host.isFinishing() || host.isDestroyed() || !host.isVisible()) return;
            generation++;
            dismissPending();
            final long request = generation;
            pendingDismiss = host.showPlatformPackageChoice(
                    () -> {
                        if (request != generation) return;
                        pendingDismiss = null;
                        launch(host, install);
                    },
                    () -> {
                        if (request != generation) return;
                        pendingDismiss = null;
                        Intent chooser = Intent.createChooser(external,
                                host.getString(R.string.platform_package_browser));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                                    new ComponentName[]{new ComponentName(host, MainActivity.class)});
                        }
                        launch(host, chooser);
                    },
                    () -> {
                        if (request == generation) pendingDismiss = null;
                    });
        });
    }

    private static void launch(MicroActivity host, Intent destination) {
        if (host.isFinishing() || host.isDestroyed()) return;
        host.beginExternalAndroidHandoff();
        try {
            host.startActivity(destination);
        } catch (ActivityNotFoundException | SecurityException error) {
            host.cancelExternalAndroidHandoff();
            Toast.makeText(host, R.string.platform_package_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private PlatformRequestHandler() { }
}
