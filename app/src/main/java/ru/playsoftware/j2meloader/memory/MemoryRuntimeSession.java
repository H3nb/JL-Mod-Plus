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

import android.os.Process;
import android.os.SystemClock;

import androidx.lifecycle.Lifecycle;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;

import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

/** Process-local identity for the currently visible MIDlet runtime Activity. */
public final class MemoryRuntimeSession {
    private static final AtomicLong NEXT = new AtomicLong(
            Math.max(1L, SystemClock.elapsedRealtimeNanos() ^ ((long) Process.myPid() << 32)));
    private static final AtomicLong ACTIVE = new AtomicLong(0L);
    private static WeakReference<MicroActivity> activeActivity = new WeakReference<>(null);

    private MemoryRuntimeSession() {
    }

    /**
     * Returns a stable generation only while the target remains at least STARTED/visible.
     * A translucent editor may pause MicroActivity, but it must not STOP it. If an OEM/window
     * configuration does stop the target, raw operations are rejected instead of refining stale
     * addresses after a MIDlet pause/resume lifecycle transition.
     */
    public static synchronized long currentGeneration() {
        MicroActivity activity;
        try {
            activity = ContextHolder.getActivity();
        } catch (NullPointerException ignored) {
            activity = null;
        }
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            activeActivity.clear();
            ACTIVE.set(0L);
            return 0L;
        }

        if (activeActivity.get() != activity || ACTIVE.get() == 0L) {
            long generation = NEXT.incrementAndGet();
            if (generation == 0L) generation = NEXT.incrementAndGet();
            activeActivity = new WeakReference<>(activity);
            ACTIVE.set(generation);
        }
        return ACTIVE.get();
    }

    public static boolean isActive(long generation) {
        return generation != 0L && currentGeneration() == generation;
    }
}
