/*
 * Modified for JL-Mod Plus.
 * Copyright 2012 Kulikov Dmitriy
 * Copyright 2015-2016 Nickolay Savchenko
 * Copyright 2017-2018 Nikita Shakarun
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
package javax.microedition.midlet;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.microemu.cldc.file.FileSystemFileConnection;

import java.io.IOException;
import java.util.Map;

import javax.microedition.io.ConnectionNotFoundException;
import javax.microedition.io.Connector;
import javax.microedition.lcdui.Display;
import javax.microedition.shell.MicroActivity;
import javax.microedition.shell.MidletThread;
import javax.microedition.util.ContextHolder;

public abstract class MIDlet {
	private static final String TAG = "MIDlet";

	private static Map<String, String> properties;

	protected MIDlet() {
		Display.getDisplay(this); // init display for this instance
	}

	public static void initProps(Map<String, String> p) {
		properties = p;
	}

	public String getAppProperty(String key) {
		String value = properties.get(key);
		Log.d(TAG, "getAppProperty: " + key + "=" + value);
		return value;
	}

	/**
	 * Report the shell that the MIDlet is ready to go into a pause.
	 */
	public final void notifyPaused() {
		MidletThread.notifyPaused();
	}

	/**
	 * Report the shell that the MIDlet has completed its work. In this case, the shell will be
	 * closed.
	 * <p>
	 * Calls to this method from destroyApp() are ignored.
	 */
	public final void notifyDestroyed() {
		MidletThread.notifyDestroyed();
	}

	/**
	 * Called every time the MIDlet becomes active: at startup
	 * after initApp(), when recovering from a minimized state.
	 */
	public abstract void startApp() throws MIDletStateChangeException;

	/**
	 * Called every time the MIDlet pauses: when minimized
	 * to background.
	 */
	public abstract void pauseApp();

	/**
	 * Called when the application terminates.
	 *
	 * @param unconditional unconditional completion flag, has no particular
	 *                      sense for Android.
	 */
	public abstract void destroyApp(boolean unconditional) throws MIDletStateChangeException;

    public boolean platformRequest(String url) throws ConnectionNotFoundException {
        if (url == null) throw new NullPointerException("URL");
        // There is no deferred platform-request queue in this runtime. An empty URL
        // cancels nothing and must not produce an empty ACTION_VIEW launch.
        if (url.isEmpty()) return false;

        // Android 10+ can silently block launches made from the background. There
        // is no durable queue here, so do not claim delivery without a visible host.
        MicroActivity activity = ContextHolder.getActivity();
        if (activity == null || !activity.isVisible()) {
            throw new ConnectionNotFoundException("No foreground host for platform request");
        }

        try {
            Uri target = Uri.parse(url);
            String scheme = target.getScheme();
            Intent intent;
            boolean suiteInstall = isSuiteInstallUrl(target);
            if (suiteInstall) {
                // Resolve virtual-file suites through the existing FileProvider, rather
                // than handing the installer an emulator-private file:// location.
                Uri installTarget = target;
                if ("file".equalsIgnoreCase(scheme)) {
                    FileSystemFileConnection connection =
                            (FileSystemFileConnection) Connector.open(url, Connector.READ);
                    try {
                        installTarget = connection.getURI();
                    } finally {
                        connection.close();
                    }
                }
                // The existing installer owns download, confirmation and cancellation.
                intent = new Intent(Intent.ACTION_VIEW, installTarget);
                intent.setClass(activity, io.github.h3nb.jlmodplus.MainActivity.class);
                if ("file".equalsIgnoreCase(scheme)) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } else if ("tel".equalsIgnoreCase(scheme)) {
                // ACTION_DIAL requires the user to place the call explicitly.
                intent = new Intent(Intent.ACTION_DIAL, target);
            } else if ("file".equalsIgnoreCase(scheme)) {
                FileSystemFileConnection connection =
                        (FileSystemFileConnection) Connector.open(url, Connector.READ);
                try {
                    Uri shared = connection.getURI();
                    intent = new Intent(Intent.ACTION_VIEW, shared);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setClipData(android.content.ClipData.newRawUri("MIDlet file", shared));
                } finally {
                    connection.close();
                }
            } else {
                intent = new Intent(Intent.ACTION_VIEW, target);
            }

            // For generic URL/file handoffs, Android's chooser is the explicit
            // per-request user acknowledgement required by MIDP 2.0. Phone and
            // suite-install flows already require confirmation in their target UI.
            Intent launch = suiteInstall || "tel".equalsIgnoreCase(scheme)
                    ? intent : Intent.createChooser(intent, null);
            activity.beginExternalAndroidHandoff();
            try {
                activity.startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException failure) {
                activity.cancelExternalAndroidHandoff();
                throw failure;
            }
        } catch (ActivityNotFoundException | SecurityException | IOException | IllegalArgumentException failure) {
            ConnectionNotFoundException exception =
                    new ConnectionNotFoundException("Unable to handle platform request");
            exception.initCause(failure);
            throw exception;
        }
        // Opening the target does not require the requesting MIDlet suite to exit.
        return false;
    }

    private static boolean isSuiteInstallUrl(Uri target) {
        String scheme = target.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)
                && !"file".equalsIgnoreCase(scheme)) return false;
        String path = target.getPath();
        if (path == null) return false;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".jad") || lower.endsWith(".jar");
    }

	public final int checkPermission(String permission) {
		return javax.microedition.shell.MidletPermissionManager.checkPermission(permission);
	}

	public final void resumeRequest() {
		MidletThread.resumeRequest();
	}
}
