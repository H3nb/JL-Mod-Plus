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

package javax.microedition.shell;

import java.util.Arrays;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Parent-owned receiver for the sparse class-initialization evidence ABI.
 *
 * <p>The callback is intentionally limited to a loader-scoped indexed state update. It never
 * creates a Memory Editor session, reflects on the guest, logs, performs I/O, or invokes game
 * code. Full scanner state is created lazily by the runtime menu.</p>
 */
public final class MemoryProbe {
    public static final int ABI_VERSION = 1;
    private static final Object REGISTRY_LOCK = new Object();
    private static final WeakHashMap<ClassLoader, Ledger> LEDGERS = new WeakHashMap<>();

    private MemoryProbe() {
    }

    /** Binds the lightweight ledger before the guest main class is loaded. */
    public static Ledger bind(ClassLoader loader, int[] insertedProbeClassIds) {
        if (loader == null) throw new NullPointerException("loader");
        Ledger ledger = new Ledger(insertedProbeClassIds);
        synchronized (REGISTRY_LOCK) {
            Ledger previous = LEDGERS.put(loader, ledger);
            if (previous != null) previous.deactivate();
        }
        return ledger;
    }

    /** Removes the exact ledger owned by a loader; stale teardown cannot remove a replacement. */
    public static void unbind(ClassLoader loader, Ledger ledger) {
        if (loader == null || ledger == null) return;
        synchronized (REGISTRY_LOCK) {
            if (LEDGERS.get(loader) == ledger) {
                LEDGERS.remove(loader);
                ledger.deactivate();
            }
        }
    }

    /**
     * Sparse probe ABI referenced from converted guest bytecode. Keep this signature stable and
     * parent-visible; changing it requires a probe ABI/schema bump and atomic reconversion.
     */
    public static void classInitReturned(Class<?> owner, int sourceClassId) {
        if (owner == null) return;
        ClassLoader loader = owner.getClassLoader();
        if (loader == null) return;
        Ledger ledger;
        synchronized (REGISTRY_LOCK) {
            ledger = LEDGERS.get(loader);
        }
        if (ledger != null) ledger.mark(sourceClassId);
    }

    static int registeredLoaderCountForTests() {
        synchronized (REGISTRY_LOCK) {
            return LEDGERS.size();
        }
    }

    public static final class Ledger {
        private final int[] classIds;
        private final AtomicIntegerArray observed;
        private final AtomicInteger observedCount = new AtomicInteger();
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Ledger(int[] insertedProbeClassIds) {
            int[] ids = insertedProbeClassIds == null
                    ? new int[0] : insertedProbeClassIds.clone();
            Arrays.sort(ids);
            int uniqueCount = 0;
            for (int id : ids) {
                if (uniqueCount == 0 || ids[uniqueCount - 1] != id) ids[uniqueCount++] = id;
            }
            classIds = Arrays.copyOf(ids, uniqueCount);
            observed = new AtomicIntegerArray(uniqueCount);
        }

        private void mark(int sourceClassId) {
            if (!active.get()) return;
            int slot = Arrays.binarySearch(classIds, sourceClassId);
            if (slot < 0) return;
            if (observed.compareAndSet(slot, 0, 1)) observedCount.incrementAndGet();
        }

        private void deactivate() {
            active.set(false);
        }

        public boolean isActive() {
            return active.get();
        }

        public int probeClassCount() {
            return classIds.length;
        }

        public int observedCount() {
            return observedCount.get();
        }

        public boolean wasObserved(int sourceClassId) {
            int slot = Arrays.binarySearch(classIds, sourceClassId);
            return slot >= 0 && observed.get(slot) != 0;
        }

        /** Returns a copy suitable for a session snapshot; callbacks never call this method. */
        public int[] observedClassIds() {
            int count = observedCount.get();
            int[] result = new int[count];
            int index = 0;
            for (int i = 0; i < classIds.length && index < count; i++) {
                if (observed.get(i) != 0) result[index++] = classIds[i];
            }
            return index == result.length ? result : Arrays.copyOf(result, index);
        }
    }
}
