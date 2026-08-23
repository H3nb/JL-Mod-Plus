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

package ru.playsoftware.j2meloader.crashes;

import android.content.Context;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Small cross-process marker for the MIDlet that was last running.
 *
 * <p>The marker is deliberately separate from preferences: the main process and the isolated
 * {@code :midlet} process must be able to observe the same committed value even when Android
 * kills the isolated process while it is cached. It is removed only after an intentional runtime
 * termination or a fatal runtime failure. A system kill therefore leaves enough information for
 * the launcher dispatcher to restore the MIDlet on the next app launch.</p>
 */
public final class MidletSessionStore {
    private static final Object LOCK = new Object();
    private static final String DIRECTORY = "runtime";
    private static final String FILE_NAME = "active-midlet.properties";
    private static final String KEY_VERSION = "version";
    private static final String KEY_APP_PATH = "appPath";
    private static final String KEY_APP_NAME = "appName";
    private static final String KEY_MAIN_CLASS = "mainClass";
    private static final String VERSION = "1";

    private MidletSessionStore() {
    }

    /** Records a launch before the MIDlet class is selected (including multi-MIDlet jars). */
    public static void markPending(@Nullable Context context, String appPath, String appName) {
        write(context, appPath, appName, null);
    }

    /** Records the concrete MIDlet class once the runtime has selected it. */
    public static void markStarted(@Nullable Context context, String appPath, String appName,
            String mainClass) {
        write(context, appPath, appName, mainClass);
    }

    @Nullable
    public static State read(@Nullable Context context) {
        if (context == null) {
            return null;
        }
        synchronized (LOCK) {
            File file = stateFile(context);
            if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
                return null;
            }
            Properties properties = new Properties();
            try (FileInputStream input = new AtomicFile(file).openRead()) {
                properties.load(input);
            } catch (IOException | RuntimeException e) {
                return null;
            }
            if (!VERSION.equals(properties.getProperty(KEY_VERSION))) {
                return null;
            }
            String appPath = nonBlank(properties.getProperty(KEY_APP_PATH));
            if (appPath == null) {
                return null;
            }
            return new State(
                    appPath,
                    nonBlank(properties.getProperty(KEY_APP_NAME)),
                    nonBlank(properties.getProperty(KEY_MAIN_CLASS)));
        }
    }

    public static void clear(@Nullable Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                new AtomicFile(stateFile(context)).delete();
            } catch (RuntimeException ignored) {
                // Session cleanup must never interfere with MIDlet teardown.
            }
        }
    }

    private static void write(@Nullable Context context, String appPath, String appName,
            String mainClass) {
        if (context == null || nonBlank(appPath) == null) {
            return;
        }
        synchronized (LOCK) {
            AtomicFile atomic = new AtomicFile(stateFile(context));
            FileOutputStream output = null;
            try {
                Properties properties = new Properties();
                properties.setProperty(KEY_VERSION, VERSION);
                properties.setProperty(KEY_APP_PATH, appPath);
                if (nonBlank(appName) != null) {
                    properties.setProperty(KEY_APP_NAME, appName);
                }
                if (nonBlank(mainClass) != null) {
                    properties.setProperty(KEY_MAIN_CLASS, mainClass);
                }
                output = atomic.startWrite();
                properties.store(output, "JL-Mod Plus active MIDlet");
                output.flush();
                atomic.finishWrite(output);
                output = null;
            } catch (IOException | RuntimeException ignored) {
                if (output != null) {
                    atomic.failWrite(output);
                }
            }
        }
    }

    private static File stateFile(Context context) {
        File directory = new File(context.getApplicationContext().getFilesDir(), DIRECTORY);
        if (!directory.isDirectory()) {
            // mkdirs() is intentionally best effort; AtomicFile reports the write failure if the
            // directory cannot be created and the runtime can still continue without resumption.
            directory.mkdirs();
        }
        return new File(directory, FILE_NAME);
    }

    @Nullable
    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class State {
        private final String appPath;
        private final String appName;
        private final String mainClass;

        private State(String appPath, String appName, String mainClass) {
            this.appPath = appPath;
            this.appName = appName;
            this.mainClass = mainClass;
        }

        public String getAppPath() {
            return appPath;
        }

        @Nullable
        public String getAppName() {
            return appName;
        }

        @Nullable
        public String getMainClass() {
            return mainClass;
        }
    }
}
