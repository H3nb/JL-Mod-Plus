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

package ru.playsoftware.j2meloader.memory.debug;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import javax.microedition.shell.MicroActivity;

/**
 * Debug-only process bootstrap. It installs a lightweight trigger on MicroActivity without
 * changing MicroActivity itself or starting a second Activity. The heavy editor view is built
 * lazily on first use, before a search can bind addresses.
 */
public final class MemoryEditorBootstrapProvider extends ContentProvider {
    private WeakReference<MemoryEditorOverlayController> controller = new WeakReference<>(null);

    private final Application.ActivityLifecycleCallbacks callbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
                    if (!(activity instanceof MicroActivity microActivity)) return;
                    MemoryEditorOverlayController previous = controller.get();
                    if (previous != null) previous.destroy();
                    MemoryEditorOverlayController next = new MemoryEditorOverlayController(microActivity);
                    controller = new WeakReference<>(next);
                    next.attach();
                }

                @Override public void onActivityStarted(@NonNull Activity activity) {}
                @Override public void onActivityResumed(@NonNull Activity activity) {}
                @Override public void onActivityPaused(@NonNull Activity activity) {}
                @Override public void onActivityStopped(@NonNull Activity activity) {}
                @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                        @NonNull Bundle outState) {}

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    MemoryEditorOverlayController current = controller.get();
                    if (current != null && current.owns(activity)) {
                        current.destroy();
                        controller.clear();
                    }
                }
            };

    @Override
    public boolean onCreate() {
        if (getContext() == null) return false;
        Application app = (Application) getContext().getApplicationContext();
        app.registerActivityLifecycleCallbacks(callbacks);
        return true;
    }

    @Nullable @Override public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) { return null; }
    @Nullable @Override public String getType(@NonNull Uri uri) { return null; }
    @Nullable @Override public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("Debug bootstrap is not a data provider");
    }
    @Override public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) { return 0; }
    @Override public int update(@NonNull Uri uri, @Nullable ContentValues values,
            @Nullable String selection, @Nullable String[] selectionArgs) { return 0; }
}
