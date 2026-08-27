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
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import javax.microedition.shell.MicroActivity;

/**
 * Debug-only :midlet bootstrap. Normal operation creates no editor UI in the target process.
 * A tiny one-time setup button exists only until SYSTEM_ALERT_WINDOW is granted; afterwards the
 * independent :memory_ui overlay service owns the bubble and full Memory Editor surface.
 */
public final class MemoryEditorBootstrapProvider extends ContentProvider {
    private WeakReference<MicroActivity> activeActivity = new WeakReference<>(null);
    private WeakReference<TextView> permissionTrigger = new WeakReference<>(null);

    private final Application.ActivityLifecycleCallbacks callbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
                    if (!(activity instanceof MicroActivity microActivity)) return;
                    activeActivity = new WeakReference<>(microActivity);
                    ensureOverlay(microActivity);
                }

                @Override public void onActivityStarted(@NonNull Activity activity) {}

                @Override
                public void onActivityResumed(@NonNull Activity activity) {
                    if (activity instanceof MicroActivity microActivity) ensureOverlay(microActivity);
                }

                @Override public void onActivityPaused(@NonNull Activity activity) {}
                @Override public void onActivityStopped(@NonNull Activity activity) {}
                @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
                        @NonNull Bundle outState) {}

                @Override
                public void onActivityDestroyed(@NonNull Activity activity) {
                    if (!(activity instanceof MicroActivity)) return;
                    removePermissionTrigger();
                    try {
                        activity.stopService(new Intent(activity, MemoryEditorOverlayService.class));
                    } catch (RuntimeException ignored) {
                    }
                    activeActivity.clear();
                }
            };

    @Override
    public boolean onCreate() {
        if (getContext() == null) return false;
        Application app = (Application) getContext().getApplicationContext();
        app.registerActivityLifecycleCallbacks(callbacks);
        return true;
    }

    private void ensureOverlay(MicroActivity activity) {
        if (Settings.canDrawOverlays(activity)) {
            removePermissionTrigger();
            Intent service = new Intent(activity, MemoryEditorOverlayService.class)
                    .setAction(MemoryEditorOverlayService.ACTION_ATTACH);
            try {
                activity.startService(service);
            } catch (RuntimeException ignored) {
            }
            return;
        }
        attachPermissionTrigger(activity);
    }

    private void attachPermissionTrigger(MicroActivity activity) {
        TextView existing = permissionTrigger.get();
        if (existing != null && existing.getParent() != null) return;
        if (!(activity.getWindow().getDecorView() instanceof ViewGroup parent)) return;

        TextView trigger = new TextView(activity);
        trigger.setText("MEM");
        trigger.setTextColor(Color.WHITE);
        trigger.setTextSize(12f);
        trigger.setGravity(Gravity.CENTER);
        int padding = Math.round(8f * activity.getResources().getDisplayMetrics().density);
        trigger.setPadding(padding, padding, padding, padding);
        trigger.setBackgroundColor(0xD0442222);
        trigger.setOnClickListener(v -> {
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.topMargin = padding;
        params.rightMargin = padding;
        parent.addView(trigger, params);
        permissionTrigger = new WeakReference<>(trigger);
    }

    private void removePermissionTrigger() {
        TextView trigger = permissionTrigger.get();
        if (trigger != null && trigger.getParent() instanceof ViewGroup parent) {
            parent.removeView(trigger);
        }
        permissionTrigger.clear();
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
