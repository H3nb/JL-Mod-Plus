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

package ru.playsoftware.j2meloader.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import javax.microedition.shell.MemoryDiscoveryBridge;

public class ManagedJavaMemoryCorrectnessTest {
	private static final long TOKEN = 0x434f52524543544cL;

	private ManagedJavaMemoryEngine engine;
	private Root root;

	@Before
	public void setUp() {
		root = new Root();
		engine = new ManagedJavaMemoryEngine();
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(Root.class);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, root);
	}

	@After
	public void tearDown() {
		MemoryDiscoveryBridge.close(TOKEN);
	}

	@Test
	public void cancelBeforeCommitKeepsPreviousRevision() {
		ManagedJavaMemoryEngine.ManagedOperationResult first = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, first.code);
		engine.cancel(TOKEN, 1L);

		ManagedJavaMemoryEngine.ManagedOperationResult cancelled =
				engine.startExact(TOKEN, MemoryEngineContract.TYPE_INT,
						MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_CANCELLED, cancelled.code);
		assertEquals(first.revision, engine.session(TOKEN).revision);
	}

	@Test
	public void cancelAfterCommitDoesNotUndoRevision() {
		ManagedJavaMemoryEngine.ManagedOperationResult committed = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, committed.code);
		engine.cancel(TOKEN, 1L);
		assertEquals(committed.revision, engine.session(TOKEN).revision);
		assertEquals(committed.resultCount, engine.session(TOKEN).resultCount);
	}

	@Test
	public void finalRefineCommitRequiresSnapshottedRevision() {
		assertTrue(ManagedJavaMemoryEngine.revisionCanCommit(7L, 7L));
		assertFalse(ManagedJavaMemoryEngine.revisionCanCommit(7L, 8L));
		assertTrue(ManagedJavaMemoryEngine.revisionCanCommit(0L, 8L));
	}

	@Test
	public void searchEqualityKeepsNumericSignedZeroButWriteConfirmationIsExactBits() {
		long positiveFloatZero = Float.floatToRawIntBits(+0.0f) & 0xffffffffL;
		long negativeFloatZero = Float.floatToRawIntBits(-0.0f) & 0xffffffffL;
		long positiveDoubleZero = Double.doubleToRawLongBits(+0.0d);
		long negativeDoubleZero = Double.doubleToRawLongBits(-0.0d);

		assertTrue(ManagedJavaValue.matchesKnown(MemoryEngineContract.TYPE_FLOAT,
				MemoryEngineContract.PREDICATE_EQUAL, positiveFloatZero, negativeFloatZero, 0L));
		assertTrue(ManagedJavaValue.matchesKnown(MemoryEngineContract.TYPE_DOUBLE,
				MemoryEngineContract.PREDICATE_EQUAL, positiveDoubleZero, negativeDoubleZero, 0L));
		assertFalse(ManagedJavaMemoryEngine.writeConfirmed(positiveFloatZero, negativeFloatZero));
		assertFalse(ManagedJavaMemoryEngine.writeConfirmed(positiveDoubleZero, negativeDoubleZero));
		assertTrue(ManagedJavaMemoryEngine.writeConfirmed(negativeFloatZero, negativeFloatZero));
		assertTrue(ManagedJavaMemoryEngine.writeConfirmed(negativeDoubleZero, negativeDoubleZero));
	}

	@Test
	public void thirtySecondManagedFreezeFitsButThirtyThirdFailsClosed() {
		CapacityRoot capacityRoot = new CapacityRoot(33);
		MemoryDiscoveryBridge.tailSeen(CapacityRoot.class);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, capacityRoot);

		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertTrue(page.ids.length >= 33);

		long[] firstThirtyTwo = Arrays.copyOf(page.ids, 32);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.setFreezeLockTyped(TOKEN, search.revision, firstThirtyTwo,
						"7", false, 0L).code);
		assertEquals(32, engine.session(TOKEN).freezeCount);
		assertEquals(32, engine.session(TOKEN).watchCount);

		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
				engine.setFreezeLockTyped(TOKEN, search.revision,
						new long[]{page.ids[32]}, "7", false, 0L).code);
		assertEquals(32, engine.session(TOKEN).freezeCount);
	}

	private static final class Root {
		int value = 7;
		float floatZero = -0.0f;
		double doubleZero = -0.0d;
	}

	private static final class CapacityRoot {
		final int[] values;

		CapacityRoot(int size) {
			values = new int[size];
			Arrays.fill(values, 7);
		}
	}
}
