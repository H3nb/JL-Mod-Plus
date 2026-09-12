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

package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.microedition.shell.MemoryDiscoveryBridge;

/** Regression coverage for AUTO query-domain handling and concrete batch edit semantics. */
public class ManagedJavaMemoryAutoQueryRegressionTest {
    private static final long TOKEN = 0x4155544f51555259L;

    private ManagedJavaMemoryEngine engine;
    private AutoRoot root;

    @Before
    public void setUp() {
        root = new AutoRoot();
        engine = new ManagedJavaMemoryEngine();
        MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
        MemoryDiscoveryBridge.tailSeen(AutoRoot.class);
        MemoryDiscoveryBridge.setMidletRoot(TOKEN, root);
    }

    @After
    public void tearDown() {
        MemoryDiscoveryBridge.close(TOKEN);
    }

    @Test
    public void autoBetweenKeepsNarrowIntegralPlanesWhenBoundsOverlapTheirDomain() {
        ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_BETWEEN,
                "0", "1000", 0L);
        assertEquals(MemoryEngineContract.RESULT_OK, result.code);

        ManagedJavaMemoryEngine.ManagedPage page = page(result.revision);
        assertTrue(hasAddress(page, "byteValue"));
        assertTrue(hasAddress(page, "shortValue"));
        assertTrue(hasAddress(page, "charValue"));
        assertTrue(hasAddress(page, "intValue"));
        assertTrue(hasAddress(page, "longValue"));
    }

    @Test
    public void autoStrictComparisonsDoNotLoseBoundaryValuesWhenThresholdIsOutsideDomain() {
        root.byteValue = Byte.MAX_VALUE;
        ManagedJavaMemoryEngine.ManagedOperationResult less = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_LESS,
                "1000", "", 0L);
        assertEquals(MemoryEngineContract.RESULT_OK, less.code);
        assertTrue(hasAddress(page(less.revision), "byteValue"));

        root.byteValue = Byte.MIN_VALUE;
        ManagedJavaMemoryEngine.ManagedOperationResult greater = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_GREATER,
                "-1000", "", 0L);
        assertEquals(MemoryEngineContract.RESULT_OK, greater.code);
        assertTrue(hasAddress(page(greater.revision), "byteValue"));
    }

    @Test
    public void explicitNarrowTypeRemainsStrictWhenQueryBoundIsOutOfRange() {
        ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.PREDICATE_BETWEEN,
                "0", "1000", 0L);
        assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, result.code);
    }

    @Test
    public void concreteBatchEditWritesOnlySelectedTypeAndSkipsOtherRows() {
        root.byteValue = 7;
        root.intValue = 7;
        ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_EQUAL,
                "7", "", 0L);
        long byteId = idForAddress(search.revision, "byteValue");
        long intId = idForAddress(search.revision, "intValue");

        ManagedJavaMemoryEngine.ManagedOperationResult edited = engine.editTyped(TOKEN,
                search.revision, new long[]{byteId, intId}, MemoryEngineContract.TYPE_INT,
                "1000", false, 0L);

        assertEquals(MemoryEngineContract.RESULT_OK, edited.code);
        assertEquals(1, edited.attempted);
        assertEquals(1, edited.written);
        assertEquals(1, edited.skippedByType);
        assertEquals(7, root.byteValue);
        assertEquals(1000, root.intValue);
    }

    @Test
    public void invalidReplacementForSelectedTypeFailsBeforeAnyWrite() {
        root.byteValue = 7;
        root.intValue = 7;
        ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
                MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_EQUAL,
                "7", "", 0L);
        long byteId = idForAddress(search.revision, "byteValue");
        long intId = idForAddress(search.revision, "intValue");

        ManagedJavaMemoryEngine.ManagedOperationResult edited = engine.editTyped(TOKEN,
                search.revision, new long[]{byteId, intId}, MemoryEngineContract.TYPE_BYTE,
                "1000", false, 0L);

        assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, edited.code);
        assertEquals(7, root.byteValue);
        assertEquals(7, root.intValue);
    }

    private ManagedJavaMemoryEngine.ManagedPage page(long revision) {
        return engine.resultPage(TOKEN, revision, 0, MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
    }

    private long idForAddress(long revision, String suffix) {
        ManagedJavaMemoryEngine.ManagedPage page = page(revision);
        for (int index = 0; index < page.ids.length; index++) {
            if (page.addresses[index] != null && page.addresses[index].endsWith("." + suffix)) {
                return page.ids[index];
            }
        }
        throw new AssertionError("Missing managed row: " + suffix);
    }

    private static boolean hasAddress(ManagedJavaMemoryEngine.ManagedPage page, String suffix) {
        for (String address : page.addresses) {
            if (address != null && address.endsWith("." + suffix)) return true;
        }
        return false;
    }

    static final class AutoRoot {
        byte byteValue = 42;
        short shortValue = 42;
        char charValue = 42;
        int intValue = 42;
        long longValue = 42L;
    }
}
