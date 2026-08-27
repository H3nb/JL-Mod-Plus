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

package ru.playsoftware.j2meloader.memory;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin target-process bridge for libjlmem's live candidate tracker.
 *
 * <p>Binder calls must stay bounded. They only snapshot the scanner's retained addresses and return
 * the most recently published live-address result. Potentially expensive temporal validation /
 * resident-ART relocation recovery runs asynchronously on one background-priority worker. This
 * keeps Android input, MIDlet paint, and Binder dispatch from sharing a long synchronous recovery
 * critical path.</p>
 */
public final class MemoryLiveService extends Service {
    private static final int MAX_PAGE_SIZE = 100;
    private static final long[] EMPTY_RESULTS = new long[0];

    private final Object pageLock = new Object();
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);
    private final ExecutorService refreshWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            runnable.run();
        }, "memory-live-worker");
        thread.setDaemon(true);
        return thread;
    });

    // Binder-side reusable snapshots.
    private final long[] rawPage = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.RAW_RESULT_STRIDE];
    private final long[] responsePage = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.LIVE_RESULT_STRIDE];

    // Worker-owned input/output buffers. They are copied under pageLock before/after native work,
    // but the native refresh itself never holds pageLock and never runs on a Binder thread.
    private final long[] workerRawPage = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.RAW_RESULT_STRIDE];
    private final long[] workerLivePage = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.LIVE_RESULT_STRIDE];

    // Last completed live snapshot plus the raw address/type rows from which it was derived.
    private final long[] publishedSource = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.RAW_RESULT_STRIDE];
    private final long[] publishedLive = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.LIVE_RESULT_STRIDE];

    private long trackingVersion;
    private long workerVersion;
    private long workerGeneration;
    private int workerOffset;
    private int workerCount;
    private long publishedGeneration;
    private int publishedOffset = -1;
    private int publishedCount = -1;
    private boolean destroyed;

    private final IMemoryLiveService.Stub binder = new IMemoryLiveService.Stub() {
        @Override
        public long[] getLiveResultsPage(long generation, int offset, int limit) {
            if (!validGeneration(generation) || offset < 0 || limit <= 0 || limit > MAX_PAGE_SIZE) {
                return EMPTY_RESULTS;
            }

            synchronized (pageLock) {
                int count = NativeMemoryAgent.nativeFillResultsPage(rawPage, offset, limit);
                if (count < 0 || count > limit) return EMPTY_RESULTS;
                rawPage[0] = count;

                if (publishedMatches(generation, offset, count)) {
                    int liveLength = 1 + count * MemoryScanContract.LIVE_RESULT_STRIDE;
                    System.arraycopy(publishedLive, 0, responsePage, 0, liveLength);
                } else {
                    buildUntrackedSnapshot(count);
                }

                scheduleRefreshLocked(generation, offset, count);
                return responsePage;
            }
        }

        @Override
        public void resetVisibleTracking(long generation) {
            if (!validGeneration(generation)) return;
            synchronized (pageLock) {
                ++trackingVersion;
                clearPublishedLocked();
            }
            NativeMemoryAgent.nativeResetVisibleTracking();
        }

        @Override
        public String getTrackingDiagnostics(long generation) {
            if (!validGeneration(generation)) return "liveTracking=NO_TARGET";
            return NativeMemoryAgent.nativeGetVisibleTrackingDiagnostics()
                    + "\nliveRefreshAsync=true"
                    + "\nliveRefreshRunning=" + refreshRunning.get();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        synchronized (pageLock) {
            ++trackingVersion;
            clearPublishedLocked();
        }
        NativeMemoryAgent.nativeResetVisibleTracking();
        refreshWorker.shutdownNow();
        super.onDestroy();
    }

    private void scheduleRefreshLocked(long generation, int offset, int count) {
        if (destroyed || count <= 0 || refreshRunning.get()) return;
        if (!refreshRunning.compareAndSet(false, true)) return;

        int rawLength = 1 + count * MemoryScanContract.RAW_RESULT_STRIDE;
        System.arraycopy(rawPage, 0, workerRawPage, 0, rawLength);
        workerRawPage[0] = count;
        workerGeneration = generation;
        workerOffset = offset;
        workerCount = count;
        workerVersion = trackingVersion;

        try {
            refreshWorker.execute(this::runAsyncRefresh);
        } catch (RuntimeException error) {
            refreshRunning.set(false);
        }
    }

    private void runAsyncRefresh() {
        final long generation = workerGeneration;
        final int offset = workerOffset;
        final int count = workerCount;
        final long version = workerVersion;
        try {
            if (destroyed || count <= 0 || !validGeneration(generation)) return;

            int liveCount = NativeMemoryAgent.nativeRefreshVisibleCandidates(
                    workerRawPage, count, workerLivePage);
            if (liveCount < 0 || liveCount > count || destroyed || !validGeneration(generation)) {
                return;
            }
            workerLivePage[0] = liveCount;

            synchronized (pageLock) {
                if (destroyed || version != trackingVersion) return;
                int rawLength = 1 + count * MemoryScanContract.RAW_RESULT_STRIDE;
                int liveLength = 1 + liveCount * MemoryScanContract.LIVE_RESULT_STRIDE;
                System.arraycopy(workerRawPage, 0, publishedSource, 0, rawLength);
                System.arraycopy(workerLivePage, 0, publishedLive, 0, liveLength);
                publishedGeneration = generation;
                publishedOffset = offset;
                publishedCount = liveCount;
            }
        } finally {
            refreshRunning.set(false);
        }
    }

    private boolean publishedMatches(long generation, int offset, int count) {
        if (publishedGeneration != generation || publishedOffset != offset || publishedCount != count) {
            return false;
        }
        if (publishedSource[0] != count) return false;
        for (int i = 0; i < count; ++i) {
            int rawIndex = 1 + i * MemoryScanContract.RAW_RESULT_STRIDE;
            // Values/readability are expected to change live. Identity of the retained search row is
            // only its source address + primitive type.
            if (publishedSource[rawIndex] != rawPage[rawIndex]
                    || publishedSource[rawIndex + 1] != rawPage[rawIndex + 1]) {
                return false;
            }
        }
        return true;
    }

    private void buildUntrackedSnapshot(int count) {
        responsePage[0] = count;
        for (int i = 0; i < count; ++i) {
            int rawIndex = 1 + i * MemoryScanContract.RAW_RESULT_STRIDE;
            int liveIndex = 1 + i * MemoryScanContract.LIVE_RESULT_STRIDE;
            responsePage[liveIndex] = rawPage[rawIndex];
            responsePage[liveIndex + 1] = rawPage[rawIndex + 1];
            responsePage[liveIndex + 2] = rawPage[rawIndex + 2];
            responsePage[liveIndex + 3] = rawPage[rawIndex + 3];
            responsePage[liveIndex + 4] = MemoryScanContract.TRACK_UNTRACKED;
            responsePage[liveIndex + 5] = 0L;
            responsePage[liveIndex + 6] = 0L;
            responsePage[liveIndex + 7] = 0L;
        }
    }

    private void clearPublishedLocked() {
        publishedGeneration = 0L;
        publishedOffset = -1;
        publishedCount = -1;
        publishedSource[0] = 0L;
        publishedLive[0] = 0L;
    }

    private boolean validGeneration(long generation) {
        return generation != 0L && MemoryRuntimeSession.isActive(generation);
    }
}
