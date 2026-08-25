/*
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MemoryProbeTest {
    @Test
    public void callbackUpdatesOnlyTheLoaderBoundLedger() {
        ClassLoader loader = MemoryProbeTest.class.getClassLoader();
        MemoryProbe.Ledger ledger = MemoryProbe.bind(loader, new int[] {17, 23});
        try {
            assertTrue(ledger.isActive());
            assertEquals(0, ledger.observedCount());
            MemoryProbe.classInitReturned(MemoryProbeTest.class, 17);
            assertTrue(ledger.wasObserved(17));
            assertFalse(ledger.wasObserved(23));
            assertEquals(1, ledger.observedCount());

            // Bootstrap-loaded String.class cannot route to the application ledger.
            MemoryProbe.classInitReturned(String.class, 23);
            assertFalse(ledger.wasObserved(23));
        } finally {
            MemoryProbe.unbind(loader, ledger);
        }
        assertFalse(ledger.isActive());
        assertFalse(ledger.wasObserved(23));
    }

    @Test
    public void staleUnbindCannotRemoveAReplacementLedger() {
        ClassLoader loader = MemoryProbeTest.class.getClassLoader();
        MemoryProbe.Ledger first = MemoryProbe.bind(loader, new int[] {1});
        MemoryProbe.Ledger second = MemoryProbe.bind(loader, new int[] {2});
        try {
            MemoryProbe.unbind(loader, first);
            MemoryProbe.classInitReturned(MemoryProbeTest.class, 2);
            assertTrue(second.wasObserved(2));
            assertTrue(second.isActive());
        } finally {
            MemoryProbe.unbind(loader, second);
        }
        assertFalse(first.isActive());
        assertFalse(second.isActive());
    }
}
