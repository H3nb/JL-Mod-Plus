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

import androidx.annotation.Nullable;

/**
 * Thin target-process bridge for libjlmem's live candidate tracker.
 *
 * <p>The UI lives in a different process. This service owns only two reusable primitive arrays;
 * candidate identity, value reads, address validation and relocation recovery remain native.</p>
 */
public final class MemoryLiveService extends Service {
    private static final int MAX_PAGE_SIZE = 100;
    private static final long[] EMPTY_RESULTS = new long[0];

    private final Object pageLock = new Object();
    private final long[] rawPage = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.RAW_RESULT_STRIDE];
    private final long[] livePage = new long[1
            + MAX_PAGE_SIZE * MemoryScanContract.LIVE_RESULT_STRIDE];

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
                int liveCount = NativeMemoryAgent.nativeRefreshVisibleCandidates(
                        rawPage, count, livePage);
                if (liveCount < 0 || liveCount > count) return EMPTY_RESULTS;
                livePage[0] = liveCount;
                return livePage;
            }
        }

        @Override
        public void resetVisibleTracking(long generation) {
            if (validGeneration(generation)) NativeMemoryAgent.nativeResetVisibleTracking();
        }

        @Override
        public String getTrackingDiagnostics(long generation) {
            if (!validGeneration(generation)) return "liveTracking=NO_TARGET";
            return NativeMemoryAgent.nativeGetVisibleTrackingDiagnostics();
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        NativeMemoryAgent.nativeResetVisibleTracking();
        super.onDestroy();
    }

    private boolean validGeneration(long generation) {
        return generation != 0L && MemoryRuntimeSession.isActive(generation);
    }
}
