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

package ru.playsoftware.j2meloader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import java.io.File;

import ru.playsoftware.j2meloader.crashes.MidletSessionStore;
import ru.playsoftware.j2meloader.util.Constants;

/**
 * Launcher-only dispatcher.
 *
 * <p>MainActivity remains a single-task destination for imports and deep links. Routing the
 * launcher icon through this short-lived activity prevents Android from delivering a launcher
 * intent to MainActivity and moving it above an active isolated MIDlet task, which used to tear
 * down the running MIDlet when the user simply tapped the app icon again.</p>
 */
public final class LauncherActivity extends Activity {
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
        if (state != null && isLaunchable(state)) {
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
            startActivity(intent);
        } else {
            if (state != null) {
                MidletSessionStore.clear(getApplicationContext());
            }
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
        finish();
    }

    private static boolean isLaunchable(MidletSessionStore.State state) {
        String path = state.getAppPath();
        // Config.startApp currently persists filesystem paths. A file:// value is accepted for
        // compatibility with older shortcuts, while content providers are deliberately rejected:
        // MicroLoader needs a stable converted-app directory and cannot reopen a transient grant.
        if (path.startsWith("file://")) {
            String filePath = Uri.parse(path).getPath();
            return filePath != null && new File(filePath).isDirectory();
        }
        return new File(path).isDirectory();
    }
}
