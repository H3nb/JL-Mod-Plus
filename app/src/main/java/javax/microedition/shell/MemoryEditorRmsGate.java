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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.microedition.rms.impl.RecordStoreImpl;

/**
 * Host-only RMS semantic bridge. It deliberately sits in the normal RecordStore set pipeline:
 * input is already cloned when {@link #beforeSetRecord} is called, and the returned bytes are then
 * committed by the existing version, persistence, and listener code. No RMS file is edited here.
 */
public final class MemoryEditorRmsGate {
    private static final Object LOCK = new Object();
    private static final WeakHashMap<RecordStoreImpl, Boolean> OPEN_STORES = new WeakHashMap<>();
    private static final WeakHashMap<RecordStoreImpl, Map<Integer, byte[]>> OVERLAYS =
            new WeakHashMap<>();

    private MemoryEditorRmsGate() {
    }

    public static void storeOpened(RecordStoreImpl store) {
        if (store == null) return;
        synchronized (LOCK) {
            OPEN_STORES.put(store, Boolean.TRUE);
        }
    }

    public static void storeClosed(RecordStoreImpl store) {
        if (store == null) return;
        synchronized (LOCK) {
            OPEN_STORES.remove(store);
            OVERLAYS.remove(store);
        }
    }

    /** Returns a detached snapshot; it never exposes the weak registry or store internals. */
    public static List<RecordStoreImpl> snapshotOpenStores() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(OPEN_STORES.keySet()));
        }
    }

    /** Stages a same-size payload for the next semantic set of one record. */
    public static void stageOverlay(RecordStoreImpl store, int recordId, byte[] data) {
        if (store == null || data == null) throw new NullPointerException();
        synchronized (LOCK) {
            Map<Integer, byte[]> records = OVERLAYS.get(store);
            if (records == null) {
                records = new java.util.HashMap<>();
                OVERLAYS.put(store, records);
            }
            records.put(recordId, data.clone());
        }
    }

    /**
     * Called by RecordStoreImpl after its caller-owned input has been cloned and before the normal
     * replacement/version/persistence/listener pipeline. The overlay is one-shot and detached.
     */
    public static byte[] beforeSetRecord(RecordStoreImpl store, int recordId, byte[] clonedData) {
        if (clonedData == null) throw new NullPointerException("clonedData");
        synchronized (LOCK) {
            Map<Integer, byte[]> records = OVERLAYS.get(store);
            if (records == null) return clonedData;
            byte[] staged = records.remove(recordId);
            if (records.isEmpty()) OVERLAYS.remove(store);
            if (staged == null || staged.length != clonedData.length) return clonedData;
            return staged.clone();
        }
    }
}
