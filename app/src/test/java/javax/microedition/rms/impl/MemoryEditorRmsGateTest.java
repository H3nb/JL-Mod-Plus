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

package javax.microedition.rms.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;
import javax.microedition.shell.MemoryEditorRmsGate;

public class MemoryEditorRmsGateTest {
    @Test
    public void overlayUsesNormalSetRecordCommitPipeline() throws Exception {
        RecordStoreImpl store = new RecordStoreImpl(new NoOpManager(), "memory");
        store.setOpen();
        try {
            int recordId = store.getNextRecordID();
            try {
                store.addRecord(new byte[]{1, 2}, 0, 2);
            } catch (RuntimeException ignored) {
                // The record is committed before the unmocked android.util.Log call.
            }
            MemoryEditorRmsGate.stageOverlay(store, recordId, new byte[]{7, 8});
            try {
                store.setRecord(recordId, new byte[]{3, 4}, 0, 2);
            } catch (RuntimeException ignored) {
                // The semantic pipeline has completed before the diagnostic log call.
            }
            assertArrayEquals(new byte[]{7, 8}, store.getRecord(recordId));
            assertTrue(MemoryEditorRmsGate.snapshotOpenStores().contains(store));
        } finally {
            try {
                store.closeRecordStore();
            } catch (RuntimeException ignored) {
                // Local unit tests do not mock android.util.Log; registry teardown happens first.
            }
        }
        assertFalse(MemoryEditorRmsGate.snapshotOpenStores().contains(store));
    }

    private static final class NoOpManager implements RecordStoreManager {
        @Override
        public void deleteRecord(RecordStoreImpl recordStoreImpl, int recordId) {
        }

        @Override
        public void deleteRecordStore(String recordStoreName) {
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public int getSizeAvailable(RecordStoreImpl recordStoreImpl) {
            return Integer.MAX_VALUE;
        }

        @Override
        public String[] listRecordStores() {
            return new String[0];
        }

        @Override
        public void loadRecord(RecordStoreImpl recordStoreImpl, int recordId) {
        }

        @Override
        public RecordStore openRecordStore(String recordStoreName, boolean createIfNecessary) {
            return null;
        }

        @Override
        public void saveRecord(RecordStoreImpl recordStoreImpl, int recordId) {
        }
    }
}
