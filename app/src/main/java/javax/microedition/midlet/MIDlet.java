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
import android.content.Context;
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
import io.github.h3nb.jlmodplus.MainActivity;

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
        if (url == null) throw new NullPointerException("url");
        if (url.isEmpty()) {
            PlatformRequestHandler.cancelPending();
            return false;
        }
        MicroActivity activity = ContextHolder.getActivity();
        // Android may silently reject background Activity launches. Never claim a delivered
        // request when no visible host can hand it to the platform.
        if (activity == null || !activity.isVisible()) {
            throw new ConnectionNotFoundException("No foreground MIDlet host");
        }
        final Intent external;
        final android.net.Uri target;
        try {
            if (url.startsWith("file://")) {
                FileSystemFileConnection file = (FileSystemFileConnection)
                        Connector.open(url, Connector.READ);
                try {
                    target = file.getURI();
                } finally {
                    file.close();
                }
                external = new Intent(Intent.ACTION_VIEW, target)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                target = Uri.parse(url);
                String scheme = target.getScheme();
                external = new Intent("tel".equalsIgnoreCase(scheme)
                        ? Intent.ACTION_DIAL : Intent.ACTION_VIEW, target);
            }
        } catch (IOException | IllegalArgumentException error) {
            throw new ConnectionNotFoundException("Invalid or inaccessible platform request");
        }

        if (PlatformPackageLink.isPackageLink(url)) {
            Intent installer = new Intent(activity, MainActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(target)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            PlatformRequestHandler.offerPackage(activity, installer, external);
            return false; // The runtime can remain paused while Library owns the installer.
        }

        activity.beginExternalAndroidHandoff();
        try {
            activity.startActivity(external);
        } catch (ActivityNotFoundException | SecurityException error) {
            activity.cancelExternalAndroidHandoff();
            throw new ConnectionNotFoundException("No handler for platform request");
        }
        return false; // MIDP: this means the suite does NOT have to exit first.
    }

	public final int checkPermission(String permission) {
		return javax.microedition.shell.MidletPermissionManager.checkPermission(permission);
	}

	public final void resumeRequest() {
		MidletThread.resumeRequest();
	}
}
