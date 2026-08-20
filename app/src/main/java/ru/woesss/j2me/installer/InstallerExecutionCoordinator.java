/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package ru.woesss.j2me.installer;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import ru.playsoftware.j2meloader.librarydb.LibraryViewModel;

/**
 * Process-scoped guard for the physical AppInstaller conversion/publish lifetime.
 *
 * Source inspection intentionally stays outside this permit. Request-owned scratch keeps those
 * pending inspections isolated while DX, Library staging, filesystem publish, Room commit, and the
 * post-commit projection barrier remain strictly serialized.
 */
final class InstallerExecutionCoordinator {
    private static final long VISIBILITY_TIMEOUT_MILLIS = 10_000L;
    private static final Semaphore INSTALL_PERMIT = new Semaphore(1, true);
    private static final ExecutorService VISIBILITY_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jlmod-installer-visibility");
        thread.setDaemon(true);
        return thread;
    });

    interface VisibilityCallback {
        void complete(Throwable error);
    }

    static Permit acquire() throws IOException {
        try {
            INSTALL_PERMIT.acquire();
            return new Permit();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for installer execution permit", error);
        }
    }

    static void awaitVisible(
            LibraryViewModel library,
            long expectedGeneration,
            File expectedWorkdir,
            long appId,
            String storageKey,
            VisibilityCallback callback) {
        VISIBILITY_EXECUTOR.execute(() -> {
            Throwable failure = null;
            try {
                library.awaitInstalledAppVisible(
                        expectedGeneration,
                        expectedWorkdir,
                        appId,
                        storageKey,
                        VISIBILITY_TIMEOUT_MILLIS);
            } catch (Throwable error) {
                failure = error;
            }
            callback.complete(failure);
        });
    }

    static final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) INSTALL_PERMIT.release();
        }
    }

    private InstallerExecutionCoordinator() {
    }
}
