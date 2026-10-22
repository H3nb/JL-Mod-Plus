/* SPDX-License-Identifier: Apache-2.0 */
package javax.microedition.midlet;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import javax.microedition.shell.MicroActivity;

import io.github.h3nb.jlmodplus.MainActivity;
import io.github.h3nb.jlmodplus.R;

/** An ephemeral user choice. It never owns an installation or the guest lifecycle. */
public final class PlatformRequestHandler {
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static long generation;
    private static AlertDialog pending;

    public static void cancelPending() {
        UI.post(() -> {
            generation++;
            if (pending != null) pending.dismiss();
            pending = null;
        });
    }

    public static void offerPackage(MicroActivity host, Intent install, Intent external) {
        UI.post(() -> {
            if (host.isFinishing() || host.isDestroyed() || !host.isVisible()) return;
            generation++;
            if (pending != null) pending.dismiss();
            final long request = generation;
            AlertDialog dialog = new AlertDialog.Builder(host)
                    .setTitle(R.string.platform_package_choice_title)
                    .setMessage(R.string.platform_package_choice_message)
                    .setPositiveButton(R.string.platform_package_install, (ignored, which) -> {
                        if (request == generation) launch(host, install);
                    })
                    .setNeutralButton(R.string.platform_package_browser, (ignored, which) -> {
                        if (request != generation) return;
                        Intent chooser = Intent.createChooser(external,
                                host.getString(R.string.platform_package_browser));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                                    new ComponentName[]{new ComponentName(host, MainActivity.class)});
                        }
                        launch(host, chooser);
                    })
                    .setNegativeButton(android.R.string.cancel, (ignored, which) -> { })
                    .create();
            dialog.setOnDismissListener(ignored -> {
                if (pending == dialog) pending = null;
            });
            pending = dialog;
            dialog.show();
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
