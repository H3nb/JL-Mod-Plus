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

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.AtomicFile;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

import io.github.h3nb.jlmodplus.EmulatorApplication;

/**
 * Persists the currently running MIDlet process session outside process memory.
 *
 * <p>The MIDlet runs in a separate Android process, so this deliberately uses
 * an app-private atomic file instead of SharedPreferences as cross-process
 * state. The store is intentionally small: only one MIDlet can be active at a
 * time.</p>
 */
public final class CrashSessionStore {
    private static final String TAG = CrashSessionStore.class.getSimpleName();
    private static final String DIRECTORY_NAME = "crash-runtime";
    private static final String MIDLET_SESSION_FILE = "midlet-session.properties";

    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_EXPECTED_EXIT = "EXPECTED_EXIT";

    private CrashSessionStore() {
    }

    /** Called by the launcher immediately before starting the :midlet Activity. */
    public static void startMidletSession(Context context, String appName, String appPath) {
        Session session = new Session();
        session.sessionId = UUID.randomUUID().toString();
        session.state = STATE_RUNNING;
        session.processName = context.getPackageName() + ":midlet";
        session.pid = 0;
        session.startedAt = System.currentTimeMillis();
        session.appName = safe(appName);
        session.appPath = safe(appPath);
        session.exitKind = "";
        writeQuietly(context, session);
    }

    /** Called from :midlet once Android has created the actual emulator process. */
    public static void markMidletProcessStarted(Context context) {
        Session session = read(context);
        if (session == null || !STATE_RUNNING.equals(session.state)) {
            return;
        }
        session.processName = EmulatorApplication.getProcessName();
        session.pid = Process.myPid();
        writeQuietly(context, session);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager != null) {
                byte[] summary = ("jlmod-midlet:" + session.sessionId)
                        .getBytes(StandardCharsets.UTF_8);
                if (summary.length > 128) {
                    byte[] bounded = new byte[128];
                    System.arraycopy(summary, 0, bounded, 0, bounded.length);
                    summary = bounded;
                }
                try {
                    manager.setProcessStateSummary(summary);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Unable to set process state summary", error);
                }
            }
        }
    }

    /** Marks an intentional emulator shutdown before Process.killProcess(). */
    public static void markExpectedMidletExit(Context context, String exitKind) {
        Session session = read(context);
        if (session == null) {
            return;
        }
        session.state = STATE_EXPECTED_EXIT;
        session.exitKind = safe(exitKind);
        writeQuietly(context, session);
    }

    public static Session read(Context context) {
        AtomicFile file = getAtomicFile(context);
        if (!file.getBaseFile().isFile()) {
            return null;
        }
        Properties properties = new Properties();
        try (FileInputStream input = file.openRead()) {
            properties.load(input);
            Session session = new Session();
            session.sessionId = properties.getProperty("sessionId", "");
            session.state = properties.getProperty("state", STATE_RUNNING);
            session.processName = properties.getProperty("processName", context.getPackageName() + ":midlet");
            session.pid = parseInt(properties.getProperty("pid"), 0);
            session.startedAt = parseLong(properties.getProperty("startedAt"), 0L);
            session.appName = properties.getProperty("appName", "");
            session.appPath = properties.getProperty("appPath", "");
            session.exitKind = properties.getProperty("exitKind", "");
            if (session.sessionId.isEmpty() || session.startedAt <= 0L) {
                Log.w(TAG, "Ignoring invalid MIDlet crash session");
                return null;
            }
            return session;
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "Unable to read MIDlet crash session", error);
            return null;
        }
    }

    public static void clearMidletSession(Context context) {
        AtomicFile file = getAtomicFile(context);
        if (file.getBaseFile().exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete MIDlet crash session");
        }
    }

    private static void writeQuietly(Context context, Session session) {
        try {
            write(context, session);
        } catch (IOException | RuntimeException error) {
            // Crash diagnostics must never become a new crash source.
            Log.w(TAG, "Unable to persist MIDlet crash session", error);
        }
    }

    private static void write(Context context, Session session) throws IOException {
        AtomicFile file = getAtomicFile(context);
        File parent = file.getBaseFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Unable to create crash runtime directory");
        }

        Properties properties = new Properties();
        properties.setProperty("sessionId", safe(session.sessionId));
        properties.setProperty("state", safe(session.state));
        properties.setProperty("processName", safe(session.processName));
        properties.setProperty("pid", Integer.toString(session.pid));
        properties.setProperty("startedAt", Long.toString(session.startedAt));
        properties.setProperty("appName", safe(session.appName));
        properties.setProperty("appPath", safe(session.appPath));
        properties.setProperty("exitKind", safe(session.exitKind));

        FileOutputStream output = null;
        try {
            output = file.startWrite();
            properties.store(output, "JL-Mod Plus MIDlet crash session");
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            if (output != null) {
                file.failWrite(output);
            }
            throw error;
        }
    }

    private static AtomicFile getAtomicFile(Context context) {
        File directory = new File(context.getFilesDir(), DIRECTORY_NAME);
        return new AtomicFile(new File(directory, MIDLET_SESSION_FILE));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static final class Session {
        public String sessionId;
        public String state;
        public String processName;
        public int pid;
        public long startedAt;
        public String appName;
        public String appPath;
        public String exitKind;
    }
}
