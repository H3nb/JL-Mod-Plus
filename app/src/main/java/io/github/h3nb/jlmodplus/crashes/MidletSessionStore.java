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

package io.github.h3nb.jlmodplus.crashes;

import android.content.Context;
import android.util.AtomicFile;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Properties;

/**
 * Small cross-process routing record for the one MIDlet runtime owned by the isolated process.
 *
 * <p>The record is not lifecycle state and cannot prove that a Java heap still exists. Launcher
 * routing combines its opaque generation with the installed runtime's OS file lease. A process
 * death leaves the record behind, but the released lease makes that generation stale rather than
 * turning a later cold launch into a fake resume.</p>
 */
public final class MidletSessionStore {
    private static final Object LOCK = new Object();
    private static final String DIRECTORY = "runtime";
    private static final String FILE_NAME = "active-midlet.properties";
    private static final String LOCK_FILE_NAME = "active-midlet.lock";
    private static final String KEY_VERSION = "version";
    private static final String KEY_GENERATION = "generation";
    private static final String KEY_APP_PATH = "appPath";
    private static final String KEY_APP_NAME = "appName";
    private static final String KEY_MAIN_CLASS = "mainClass";
    private static final String KEY_APP_ID = "appId";
    private static final String KEY_RUNTIME_SELECTED = "runtimeSelected";
    private static final String LEGACY_VERSION = "1";
    private static final String ROUTING_VERSION = "2";
    private static final String VERSION = "3";

    private MidletSessionStore() {
    }

    /**
     * Legacy compatibility helper. A record without a generation is deliberately not resumable.
     * Production runtimes use the generation-bearing markStarted overload.
     */
    public static void markPending(@Nullable Context context, String appPath, String appName) {
        markPending(context, appPath, appName, 0L);
    }

    /**
     * Legacy compatibility helper. A record without a generation is deliberately not resumable.
     * Production runtimes use the generation-bearing markStarted overload.
     */
    public static void markPending(@Nullable Context context, String appPath, String appName,
            long appId) {
        write(context, null, appPath, appName, null, appId, false);
    }

    /** Legacy compatibility overload; records written through it are not considered live routes. */
    public static void markStarted(@Nullable Context context, String appPath, String appName,
            String mainClass) {
        markStarted(context, appPath, appName, mainClass, 0L);
    }

    /** Legacy compatibility overload; records written through it are not considered live routes. */
    public static void markStarted(@Nullable Context context, String appPath, String appName,
            String mainClass, long appId) {
        write(context, null, appPath, appName, mainClass, appId, false);
    }

    /** Records the immutable identity needed to route back to an already-live runtime. */
    public static void markStarted(@Nullable Context context, String appPath, String appName,
            String mainClass, long appId, String generation) {
        if (nonBlank(generation) == null) {
            return;
        }
        write(context, generation, appPath, appName, mainClass, appId, true);
    }

    /**
     * Updates only the emulator foreground selection for the matching live runtime generation.
     * A stale Activity/runtime can never overwrite a newer runtime record.
     */
    public static void setRuntimeSelected(@Nullable Context context, @Nullable String generation,
            boolean runtimeSelected) {
        String expectedGeneration = nonBlank(generation);
        if (context == null || expectedGeneration == null) {
            return;
        }
        synchronized (LOCK) {
            try (FileChannel channel = openStoreLock(context);
                    FileLock ignored = channel.lock()) {
                State current = readUnlocked(context);
                if (current == null || !expectedGeneration.equals(current.getGeneration())) {
                    return;
                }
                writeUnlocked(context, current.getGeneration(), current.getAppPath(),
                        current.getAppName(), current.getMainClass(), current.getAppId(),
                        runtimeSelected);
            } catch (IOException | RuntimeException ignored) {
                // Routing metadata is best effort; it must not break guest lifecycle execution.
            }
        }
    }

    @Nullable
    public static State read(@Nullable Context context) {
        if (context == null) {
            return null;
        }
        synchronized (LOCK) {
            try (FileChannel channel = openStoreLock(context);
                    FileLock ignored = channel.lock()) {
                return readUnlocked(context);
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        }
    }

    /**
     * Deletes a routing record only if it still belongs to {@code generation}.
     *
     * <p>The file lock is process-shared. The Java monitor only prevents overlapping lock attempts
     * inside one process; it is not relied on for cross-process serialization.</p>
     */
    public static void clear(@Nullable Context context, @Nullable String generation) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            try (FileChannel channel = openStoreLock(context);
                    FileLock ignored = channel.lock()) {
                State current = readUnlocked(context);
                String expected = nonBlank(generation);
                String actual = current == null ? null : current.getGeneration();
                if (current != null
                        && (expected == null ? actual == null : expected.equals(actual))) {
                    new AtomicFile(stateFile(context)).delete();
                }
            } catch (IOException | RuntimeException ignored) {
                // Conditional cleanup must never interfere with runtime teardown or launcher flow.
            }
        }
    }

    /**
     * Unconditionally clears the store. Intended for explicit reset/test cleanup, not runtime
     * terminal ownership or launcher stale-record cleanup.
     */
    public static void clear(@Nullable Context context) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            try (FileChannel channel = openStoreLock(context);
                    FileLock ignored = channel.lock()) {
                new AtomicFile(stateFile(context)).delete();
            } catch (IOException | RuntimeException ignored) {
                // Explicit cleanup remains best effort.
            }
        }
    }

    private static void write(@Nullable Context context, @Nullable String generation,
            String appPath, String appName, String mainClass, long appId, boolean runtimeSelected) {
        if (context == null || nonBlank(appPath) == null) {
            return;
        }
        synchronized (LOCK) {
            try (FileChannel channel = openStoreLock(context);
                    FileLock ignored = channel.lock()) {
                writeUnlocked(context, generation, appPath, appName, mainClass, appId,
                        runtimeSelected);
            } catch (IOException | RuntimeException ignored) {
                // Routing metadata is fail-open; the runtime can continue without launcher routing.
            }
        }
    }

    private static void writeUnlocked(Context context, @Nullable String generation,
            String appPath, @Nullable String appName, @Nullable String mainClass, long appId,
            boolean runtimeSelected) {
        AtomicFile atomic = new AtomicFile(stateFile(context));
        FileOutputStream output = null;
        try {
            Properties properties = new Properties();
            properties.setProperty(KEY_VERSION, VERSION);
            if (nonBlank(generation) != null) {
                properties.setProperty(KEY_GENERATION, generation);
            }
            properties.setProperty(KEY_APP_PATH, appPath);
            if (nonBlank(appName) != null) {
                properties.setProperty(KEY_APP_NAME, appName);
            }
            if (nonBlank(mainClass) != null) {
                properties.setProperty(KEY_MAIN_CLASS, mainClass);
            }
            if (appId > 0L) {
                properties.setProperty(KEY_APP_ID, Long.toString(appId));
            }
            properties.setProperty(KEY_RUNTIME_SELECTED, Boolean.toString(runtimeSelected));
            output = atomic.startWrite();
            properties.store(output, "JL-Mod Plus active MIDlet");
            output.flush();
            atomic.finishWrite(output);
            output = null;
        } catch (IOException | RuntimeException ignoredWrite) {
            if (output != null) {
                atomic.failWrite(output);
            }
        }
    }

    @Nullable
    private static State readUnlocked(Context context) {
        File file = stateFile(context);
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) {
            return null;
        }
        Properties properties = new Properties();
        try (FileInputStream input = new AtomicFile(file).openRead()) {
            properties.load(input);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
        String version = properties.getProperty(KEY_VERSION);
        if (!VERSION.equals(version) && !ROUTING_VERSION.equals(version)
                && !LEGACY_VERSION.equals(version)) {
            return null;
        }
        String appPath = nonBlank(properties.getProperty(KEY_APP_PATH));
        if (appPath == null) {
            return null;
        }
        boolean runtimeSelected = VERSION.equals(version)
                ? Boolean.parseBoolean(properties.getProperty(KEY_RUNTIME_SELECTED, "true"))
                : ROUTING_VERSION.equals(version);
        return new State(
                LEGACY_VERSION.equals(version)
                        ? null : nonBlank(properties.getProperty(KEY_GENERATION)),
                appPath,
                nonBlank(properties.getProperty(KEY_APP_NAME)),
                nonBlank(properties.getProperty(KEY_MAIN_CLASS)),
                parsePositiveLong(properties.getProperty(KEY_APP_ID)),
                runtimeSelected);
    }

    private static FileChannel openStoreLock(Context context) throws IOException {
        File directory = storeDirectory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create runtime routing directory: " + directory);
        }
        return new FileOutputStream(new File(directory, LOCK_FILE_NAME), true).getChannel();
    }

    private static File stateFile(Context context) {
        return new File(storeDirectory(context), FILE_NAME);
    }

    private static File storeDirectory(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), DIRECTORY);
    }

    @Nullable
    private static String nonBlank(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static long parsePositiveLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0L ? parsed : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public static final class State {
        private final String generation;
        private final String appPath;
        private final String appName;
        private final String mainClass;
        private final long appId;
        private final boolean runtimeSelected;

        private State(@Nullable String generation, String appPath, String appName,
                String mainClass, long appId, boolean runtimeSelected) {
            this.generation = generation;
            this.appPath = appPath;
            this.appName = appName;
            this.mainClass = mainClass;
            this.appId = appId;
            this.runtimeSelected = runtimeSelected;
        }

        /** Null denotes a legacy/non-routable record rather than a live runtime generation. */
        @Nullable
        public String getGeneration() {
            return generation;
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

        /** Zero denotes a record that predates or lacks the durable Library identity handshake. */
        public long getAppId() {
            return appId;
        }

        /** Whether this live runtime is the emulator foreground destination selected by the AMS. */
        public boolean isRuntimeSelected() {
            return runtimeSelected;
        }
    }
}
