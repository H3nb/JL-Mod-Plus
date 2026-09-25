/*
 * Copyright 2026 JL-Mod Plus contributors
 * Modified for JL-Mod Plus.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

import io.github.h3nb.jlmodplus.crashes.MidletSessionStore;
import io.github.h3nb.jlmodplus.runtime.RuntimeStorageLease;
import io.github.h3nb.jlmodplus.util.Constants;

/**
 * Launcher-only dispatcher.
 *
 * <p>A routing record identifies a candidate live runtime; the installed app's OS file lease is
 * the liveness proof. A released lease means the old Java heap is gone, so launcher returns to the
 * library instead of silently cold-relaunching the previous MIDlet.</p>
 */
public final class LauncherActivity extends Activity {
    private static final String TAG = "LauncherActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dispatch();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        dispatch();
    }

    private void dispatch() {
        MidletSessionStore.State state = MidletSessionStore.read(getApplicationContext());
        boolean routeToRuntime = false;
        boolean staleRecord = false;

        if (state != null) {
            File installedDir = installedDirectory(state);
            if (state.getGeneration() == null || installedDir == null || !installedDir.isDirectory()) {
                staleRecord = true;
            } else {
                try {
                    routeToRuntime = RuntimeStorageLease.isActive(
                            getApplicationContext().getFilesDir(), installedDir);
                    staleRecord = !routeToRuntime;
                } catch (IOException probeFailure) {
                    // Unknown liveness is not evidence of death. Open the library without deleting
                    // the generation so a later launcher attempt can retry the OS-lock probe.
                    Log.w(TAG, "Unable to probe active MIDlet runtime lease", probeFailure);
                }
            }
        }

        if (routeToRuntime) {
            startActivity(runtimeIntent(state));
        } else {
            if (staleRecord) {
                MidletSessionStore.clear(getApplicationContext(), state.getGeneration());
            }
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        finish();
    }

    private Intent runtimeIntent(MidletSessionStore.State state) {
        Intent intent = new Intent(this, javax.microedition.shell.MicroActivity.class)
                .setAction(Intent.ACTION_DEFAULT)
                .setData(Uri.parse(state.getAppPath()))
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (state.getAppName() != null) {
            intent.putExtra(Constants.KEY_MIDLET_NAME, state.getAppName());
        }
        if (state.getMainClass() != null) {
            intent.putExtra(Constants.KEY_MIDLET_CLASS, state.getMainClass());
        }
        if (state.getAppId() > 0L) {
            intent.putExtra(Constants.KEY_LIBRARY_APP_ID, state.getAppId());
        }
        return intent;
    }

    @Nullable
    private static File installedDirectory(MidletSessionStore.State state) {
        String path = state.getAppPath();
        Uri uri = Uri.parse(path);
        if ("file".equals(uri.getScheme())) {
            String filePath = uri.getPath();
            return filePath == null ? null : new File(filePath);
        }
        return uri.getScheme() == null ? new File(path) : null;
    }
}
