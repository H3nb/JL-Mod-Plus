/*
 * Copyright 2026 JL-Mod Plus contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ru.playsoftware.j2meloader.memory.debug;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import javax.microedition.shell.MicroActivity;

/**
 * Debug-only :midlet bootstrap. The Memory Editor is an ordinary classic View attached directly to
 * MicroActivity, so no SYSTEM_ALERT_WINDOW permission or second UI process is required. The
 * isolated :memory_engine is probed independently and owns no UI.
 */
public final class MemoryEditorBootstrapProvider extends ContentProvider {
    private WeakReference<MemoryEditorOverlayController> controller = new WeakReference<>(null);
    private WeakReference<RemoteMemoryEngineClient> engineClient = new WeakReference<>(null);

    private final Application.ActivityLifecycleCallbacks callbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
                    if (!(activity instanceof MicroActivity microActivity)) return;
                    detachCurrent();
                    MemoryEditorOverlayController next = new MemoryEditorOverlayController(microActivity);
                    controller = new WeakReference<>(next);
                    next.attach();

                    RemoteMemoryEngineClient remote = new RemoteMemoryEngineClient(
                            microActivity,
                            (supported, diagnostics) -> Toast.makeText(
                                    microActivity,
                                    supported
                                            ? "Memory engine remote access: PASS"
                                            : "Memory engine remote access: unavailable; using in-process fallback",
                                    Toast.LENGTH_SHORT).show());
                    engineClient = new WeakReference<>(remote);
                    remote.start();
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
                    if (current != null && current.owns(activity)) detachCurrent();
                }
            };

    @Override
    public boolean onCreate() {
        if (getContext() == null) return false;
        Application app = (Application) getContext().getApplicationContext();
        app.registerActivityLifecycleCallbacks(callbacks);
        return true;
    }

    private void detachCurrent() {
        MemoryEditorOverlayController current = controller.get();
        if (current != null) current.destroy();
        controller.clear();
        RemoteMemoryEngineClient remote = engineClient.get();
        if (remote != null) remote.destroy();
        engineClient.clear();
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
