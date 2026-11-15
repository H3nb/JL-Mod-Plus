/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.h3nb.jlmodplus.installer;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.io.InterruptedIOException;

/**
 * Process-scoped guard for physical installed-app conversion, publish, and deletion.
 *
 * Source inspection intentionally stays outside this permit. Request-owned scratch keeps those
 * pending inspections isolated while DX, Library staging, filesystem publish, Room commit, and the
 * post-commit projection barrier remain strictly serialized.
 */
public final class InstallerExecutionCoordinator {
    private static final Semaphore INSTALL_PERMIT = new Semaphore(1, true);

    /**
     * Acquires the process-wide lease for any mutation of an installed converted app directory.
     *
     * <p>Deletion uses the same lease as conversion so an automatic reconversion cannot publish a
     * directory after a concurrent user delete has already removed it.</p>
     */
    public static Permit acquire() throws IOException {
        return acquire(() -> false);
    }

    public interface Cancellation { boolean isCancelled(); }

    public static Permit acquire(Cancellation cancellation) throws IOException {
        try {
            while (!cancellation.isCancelled()) {
                if (INSTALL_PERMIT.tryAcquire(250, TimeUnit.MILLISECONDS)) return new Permit();
            }
            throw new InterruptedIOException("Cancelled while waiting for installer execution permit");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for installer execution permit", error);
        }
    }

    public static final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) INSTALL_PERMIT.release();
        }
    }

    private InstallerExecutionCoordinator() {
    }
}
