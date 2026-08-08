/*
 * Copyright 2026 H3NB
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

package io.github.h3nb.jlmodplus.crashes.runtime;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Small file-backed breadcrumb trail for events immediately preceding a MIDlet process death.
 *
 * <p>Events are intentionally sparse and bounded. Each call closes the file before returning so
 * useful context survives a subsequent native process crash instead of living only in memory.</p>
 */
public final class CrashBreadcrumbStore {
    private static final String TAG = CrashBreadcrumbStore.class.getSimpleName();
    private static final String DIRECTORY_NAME = "crash-runtime";
    private static final String FILE_NAME = "midlet-breadcrumbs.log";
    private static final int MAX_FILE_BYTES = 16 * 1024;
    private static final int RETAIN_BYTES = 8 * 1024;
    private static final int MAX_EVENT_CHARS = 512;
    private static final Object LOCK = new Object();

    private CrashBreadcrumbStore() {
    }

    public static void reset(Context context) {
        synchronized (LOCK) {
            File file = getFile(context);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Unable to reset MIDlet breadcrumbs");
            }
        }
    }

    public static void record(Context context, String event) {
        if (context == null) {
            return;
        }
        String safeEvent = sanitize(event);
        String line = System.currentTimeMillis()
                + " wall_ms, " + SystemClock.elapsedRealtime()
                + " elapsed_ms: " + safeEvent + '\n';
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);

        synchronized (LOCK) {
            try {
                File file = getFile(context);
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory()
                        && !parent.mkdirs() && !parent.isDirectory()) {
                    throw new IOException("Unable to create crash runtime directory");
                }
                if (file.length() + bytes.length > MAX_FILE_BYTES) {
                    trimToTail(file);
                }
                try (FileOutputStream output = new FileOutputStream(file, true)) {
                    output.write(bytes);
                }
            } catch (IOException | RuntimeException error) {
                // Diagnostics must never become a new crash source.
                Log.w(TAG, "Unable to persist crash breadcrumb", error);
            }
        }
    }

    public static String read(Context context) {
        synchronized (LOCK) {
            File file = getFile(context);
            if (!file.isFile()) {
                return "";
            }
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] bytes = input.readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Unable to read crash breadcrumbs", error);
                return "";
            }
        }
    }

    private static void trimToTail(File file) throws IOException {
        if (!file.isFile() || file.length() <= RETAIN_BYTES) {
            return;
        }
        long skip = file.length() - RETAIN_BYTES;
        byte[] tail;
        try (FileInputStream input = new FileInputStream(file)) {
            long remaining = skip;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped <= 0) {
                    break;
                }
                remaining -= skipped;
            }
            tail = input.readAllBytes();
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write("[older breadcrumbs truncated]\n".getBytes(StandardCharsets.UTF_8));
            output.write(tail);
        }
    }

    private static String sanitize(String value) {
        String safe = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
        if (safe.length() > MAX_EVENT_CHARS) {
            return safe.substring(0, MAX_EVENT_CHARS) + "…";
        }
        return safe;
    }

    private static File getFile(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY_NAME), FILE_NAME);
    }
}
