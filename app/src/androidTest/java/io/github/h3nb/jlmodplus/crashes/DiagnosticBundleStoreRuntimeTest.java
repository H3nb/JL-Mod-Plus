/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Exercises real MediaStore ownership, not a filesystem mock. Does not touch
 * unrelated user files or generate a diagnostic report automatically.
 */
@RunWith(AndroidJUnit4.class)
public class DiagnosticBundleStoreRuntimeTest {
    private static final String PREFS = "diagnostic_bundle_ownership";
    private static final String SLOT = "record.diagnostic_ci_ownership";

    @Test
    public void deletedSourceCannotBeRepublishedFromStaleRecord() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = new File(context.getFilesDir(), "diagnostics/java/ci-stale-"
                + UUID.randomUUID() + ".java.properties");
        JavaDiagnosticStore.Snapshot evidence = new JavaDiagnosticStore.Snapshot(
                source, JavaDiagnosticStore.Kind.FATAL_UNCAUGHT,
                System.currentTimeMillis(), context.getPackageName(), "main",
                android.os.Process.myPid(), "instrumentation", 1L, 5,
                null, null, null, null, null, "test", Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT, null, null, null, null,
                "java.lang.IllegalStateException: test-only",
                Collections.emptyList(), -1, null, null, null);
        try {
            JavaDiagnosticStore.write(source, evidence);
            LocalDiagnosticRepository.Record retained = LocalDiagnosticRepository.findStored(
                    context, "java:" + source.getName());
            assertNotNull("The test source must be visible before deletion", retained);
            assertTrue(LocalDiagnosticRepository.deleteWithArtifacts(context, retained).sourceDeleted);

            try {
                DiagnosticBundleStore.ensure(context, retained);
                fail("Deleted evidence must not be republished from a stale selection");
            } catch (IOException expected) {
                assertEquals("Diagnostic source no longer exists", expected.getMessage());
            }
        } finally {
            JavaDiagnosticStore.delete(evidence);
        }
    }

    @Test
    public void cleanupOnlyRemovesOwnedCandidateAndRetiredUris() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Uri active = null;
        Uri candidate = null;
        Uri retired = null;
        try {
            active = createOwned(resolver);
            candidate = createOwned(resolver);
            retired = createOwned(resolver);
            store(preferences, SLOT, resolver, active);
            store(preferences, SLOT + ".candidate", resolver, candidate);
            store(preferences, SLOT + ".retired", resolver, retired);

            DiagnosticBundleStore.cleanupPending(context);

            assertTrue("Current public ZIP must survive maintenance", exists(resolver, active));
            assertFalse("Candidate should be deleted", exists(resolver, candidate));
            assertFalse("Retired ZIP should be deleted", exists(resolver, retired));
            assertNotNull(preferences.getString(SLOT + ".uri", null));
            assertNull(preferences.getString(SLOT + ".candidate.uri", null));
            assertNull(preferences.getString(SLOT + ".retired.uri", null));
        } finally {
            remove(resolver, active);
            remove(resolver, candidate);
            remove(resolver, retired);
            clear(preferences, SLOT);
            clear(preferences, SLOT + ".candidate");
            clear(preferences, SLOT + ".retired");
        }
    }

    @Test
    public void temporaryAliasCannotDeleteActiveOwnedUri() throws Exception {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Uri active = null;
        try {
            active = createOwned(resolver);
            store(preferences, SLOT, resolver, active);
            store(preferences, SLOT + ".candidate", resolver, active);

            DiagnosticBundleStore.cleanupPending(context);

            assertTrue("Active URI must survive alias cleanup", exists(resolver, active));
            assertNotNull(preferences.getString(SLOT + ".uri", null));
            assertNull(preferences.getString(SLOT + ".candidate.uri", null));
        } finally {
            remove(resolver, active);
            clear(preferences, SLOT);
            clear(preferences, SLOT + ".candidate");
        }
    }

    private static Uri createOwned(ContentResolver resolver) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                "jlp-test-" + UUID.randomUUID() + ".zip");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/" + DiagnosticBundleStore.PUBLIC_DIRECTORY);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Unable to create owned test row");
        try {
            try (OutputStream output = resolver.openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Unable to write owned test row");
                output.write("test-only".getBytes(StandardCharsets.UTF_8));
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, publish, null, null) != 1) {
                throw new IOException("Unable to publish owned test row");
            }
            return uri;
        } catch (Exception failure) {
            remove(resolver, uri);
            throw failure;
        }
    }

    private static void store(SharedPreferences preferences, String slot,
            ContentResolver resolver, Uri uri) throws IOException {
        String actual = displayName(resolver, uri);
        if (actual == null) throw new IOException("Owned row has no display name");
        assertTrue(preferences.edit()
                .putString(slot + ".uri", uri.toString())
                .putString(slot + ".file", actual)
                .putString(slot + ".logical", actual)
                .putString(slot + ".content", "test-only")
                .putInt(slot + ".format", 2)
                .commit());
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static boolean exists(ContentResolver resolver, Uri uri) {
        if (uri == null) return false;
        try (Cursor cursor = resolver.query(uri, new String[]{"_id"}, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException missing) {
            return false;
        }
    }

    private static void remove(ContentResolver resolver, Uri uri) {
        if (uri == null) return;
        try {
            resolver.delete(uri, null, null);
        } catch (RuntimeException ignored) {
            // The test must not erase unrelated public files if permission was revoked.
        }
    }

    private static void clear(SharedPreferences preferences, String slot) {
        preferences.edit().remove(slot + ".uri").remove(slot + ".file")
                .remove(slot + ".logical").remove(slot + ".content")
                .remove(slot + ".format").commit();
    }
}
