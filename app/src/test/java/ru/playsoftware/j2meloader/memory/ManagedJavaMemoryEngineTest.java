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

import java.util.Hashtable;
import java.util.Arrays;
import java.util.Stack;
import java.util.Vector;

import javax.microedition.shell.MemoryDiscoveryBridge;

public class ManagedJavaMemoryEngineTest {
	private static final long TOKEN = 0x4d414e414745444aL;

	private ManagedJavaMemoryEngine engine;
	private FixtureRoot root;

	@Before
	public void setUp() {
		root = new FixtureRoot();
		engine = new ManagedJavaMemoryEngine();
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(FixtureRoot.class);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, root);
	}

	@After
	public void tearDown() {
		MemoryDiscoveryBridge.close(TOKEN);
	}

	@Test
	public void exactScanFindsIntFieldsArraysAndExactContainersButStopsSubclasses() {
		ManagedJavaMemoryEngine.ManagedOperationResult result = startExact();
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);

		assertTrue(hasAddress(page, "rootMatch"));
		assertTrue(hasAddress(page, "inheritedMatch"));
		assertTrue(hasAddress(page, "vectorOnly"));
		assertTrue(hasAddress(page, "stackOnly"));
		assertTrue(hasAddress(page, "tableOnly"));
		assertTrue(hasAddress(page, "staticMatch [static]"));
		assertTrue(hasAddressPrefix(page, "int[]#"));
		assertFalse(hasAddress(page, "finalMatch"));
		assertFalse(hasAddress(page, "customOnly"));
		assertTrue(allIdsUseManagedNamespace(page));
	}

	@Test
	public void refineKeepsCommittedRevisionAtomicAndSupportsPreviousAndInitialBaselines() {
		ManagedJavaMemoryEngine.ManagedOperationResult first = startExact();
		long changedId = idForAddress(first.revision, "rootChanged");
		root.rootChanged = 9;

		ManagedJavaMemoryEngine.ManagedOperationResult changed = engine.refineInt(TOKEN,
				first.revision, MemoryEngineContract.PREDICATE_CHANGED,
				MemoryEngineContract.COMPARE_PREVIOUS, 0, 0);
		assertEquals(MemoryEngineContract.RESULT_OK, changed.code);
		assertTrue(hasId(engine.resultPage(TOKEN, changed.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), changedId));

		ManagedJavaMemoryEngine.ManagedOperationResult unchanged = engine.refineInt(TOKEN,
				changed.revision, MemoryEngineContract.PREDICATE_UNCHANGED,
				MemoryEngineContract.COMPARE_PREVIOUS, 0, 0);
		assertEquals(MemoryEngineContract.RESULT_OK, unchanged.code);
		assertTrue(hasId(engine.resultPage(TOKEN, unchanged.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), changedId));

		ManagedJavaMemoryEngine.ManagedOperationResult stale = engine.refineInt(TOKEN,
				first.revision, MemoryEngineContract.PREDICATE_EQUAL,
				MemoryEngineContract.COMPARE_PREVIOUS, 9, 0);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, stale.code);
		assertEquals(unchanged.revision, engine.session(TOKEN).revision);

		root.rootChanged = 7;
		ManagedJavaMemoryEngine.ManagedOperationResult fresh = startExact();
		root.rootChanged = 11;
		ManagedJavaMemoryEngine.ManagedOperationResult initial = engine.refineInt(TOKEN,
				fresh.revision, MemoryEngineContract.PREDICATE_CHANGED,
				MemoryEngineContract.COMPARE_INITIAL, 0, 0);
		assertEquals(MemoryEngineContract.RESULT_OK, initial.code);
		assertTrue(hasAddress(engine.resultPage(TOKEN, initial.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), "rootChanged"));
	}

	@Test
	public void editWatchFreezeAndClearPreserveLogicalIdentity() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");

		ManagedJavaMemoryEngine.ManagedOperationResult watch = engine.addWatch(TOKEN,
				search.revision, new long[]{id}, 0);
		assertEquals(MemoryEngineContract.RESULT_OK, watch.code);
		assertEquals(1, watch.watchCount);

		root.rootMatch = 10;
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.refresh(TOKEN, new long[]{id}, search.revision, 0).code);
		ManagedJavaMemoryEngine.ManagedPage watchPage = engine.watchPage(TOKEN);
		assertEquals("10", watchPage.values[0]);
		assertEquals("7", watchPage.initialValues[0]);
		assertEquals("10", watchPage.previousValues[0]);

		ManagedJavaMemoryEngine.ManagedOperationResult edit = engine.edit(TOKEN, search.revision,
				new long[]{id}, 12, 0);
		assertEquals(MemoryEngineContract.RESULT_OK, edit.code);
		assertEquals(12, root.rootMatch);

		assertEquals(MemoryEngineContract.RESULT_OK, engine.setFreezeLock(TOKEN,
				search.revision, new long[]{id}, 13, 0).code);
		root.rootMatch = 1;
		assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(TOKEN, 0).code);
		assertEquals(13, root.rootMatch);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearFreeze(TOKEN, new long[]{id}, 0).code);

		engine.clearSearch(TOKEN, 0);
		assertEquals(1, engine.watchPage(TOKEN).ids.length);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.edit(TOKEN, 0L, new long[]{id}, 20, 0).code);
		assertEquals(20, root.rootMatch);
	}

	@Test
	public void watchPageDoesNotChangeCommittedSearchRevision() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		assertTrue(search.revision > 0L);
		assertEquals(0L, engine.watchPage(TOKEN).revision);
		assertEquals(search.revision, engine.session(TOKEN).revision);
		assertEquals(search.resultCount, engine.session(TOKEN).resultCount);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.refineInt(TOKEN, search.revision, MemoryEngineContract.PREDICATE_EQUAL,
						MemoryEngineContract.COMPARE_PREVIOUS, 7, 0).code);
	}

	@Test
	public void candidateBufferGrowsToNonPowerOfTwoLimitAndRejectsTheNextRow() {
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(CapacityRoot.class);
		CapacityRoot capacityRoot = new CapacityRoot(30);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, capacityRoot);
		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, 30, 100, 100));

		ManagedJavaMemoryEngine.ManagedOperationResult accepted = engine.startExactInt(TOKEN, 7, 0);
		assertEquals(MemoryEngineContract.RESULT_OK, accepted.code);
		assertEquals(30L, accepted.resultCount);

		capacityRoot.values = new int[31];
		Arrays.fill(capacityRoot.values, 7);
		ManagedJavaMemoryEngine.ManagedOperationResult rejected = engine.startExactInt(TOKEN, 7, 0);
		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT, rejected.code);
		assertEquals(accepted.revision, engine.session(TOKEN).revision);
	}

	@Test
	public void fabricatedAndRawIdsFailClosed() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long fabricated = ManagedJavaMemoryIds.encode(ManagedJavaMemoryEngine.KIND_OBJECT_FIELD,
				999L, 0);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				engine.edit(TOKEN, search.revision, new long[]{fabricated}, 1, 0).code);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				engine.edit(TOKEN, search.revision, new long[]{1L}, 1, 0).code);
	}

	private ManagedJavaMemoryEngine.ManagedOperationResult startExact() {
		return engine.startExactInt(TOKEN, 7, 0);
	}

	private long idForAddress(long revision, String fragment) {
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		for (int index = 0; index < page.ids.length; index++) {
			if (page.addresses[index].contains(fragment)) return page.ids[index];
		}
		throw new AssertionError("Missing managed row containing " + fragment);
	}

	private static boolean hasAddress(ManagedJavaMemoryEngine.ManagedPage page, String fragment) {
		for (String address : page.addresses) if (address.contains(fragment)) return true;
		return false;
	}

	private static boolean hasAddressPrefix(ManagedJavaMemoryEngine.ManagedPage page, String prefix) {
		for (String address : page.addresses) if (address.startsWith(prefix)) return true;
		return false;
	}

	private static boolean hasId(ManagedJavaMemoryEngine.ManagedPage page, long id) {
		for (long candidate : page.ids) if (candidate == id) return true;
		return false;
	}

	private static boolean allIdsUseManagedNamespace(ManagedJavaMemoryEngine.ManagedPage page) {
		for (long id : page.ids) if (!ManagedJavaMemoryIds.hasValidNamespace(id)) return false;
		return true;
	}

	private static class FixtureBase {
		private int inheritedMatch = 7;
		private int inheritedUnchanged = 7;
        }

	private static final class FixtureRoot extends FixtureBase {
		static int staticMatch = 7;
		static int[] staticArray = {7, 3, 7};
		static FixtureChild staticChild = new FixtureChild("static", 7);

		int rootMatch = 7;
		int rootChanged = 7;
		final int finalMatch = 7;
		FixtureChild child = new FixtureChild("direct", 7);
		int[] array = {7, 4, 7};
		Object[] cycle = new Object[2];
		Vector<Object> vector = new Vector<>();
		Stack<Object> stack = new Stack<>();
		Hashtable<Object, Object> table = new Hashtable<>();
		CustomVector custom = new CustomVector();

		FixtureRoot() {
			FixtureChild vectorChild = new VectorChild();
			FixtureChild stackChild = new StackChild();
			FixtureChild tableChild = new TableChild();
			FixtureChild customChild = new CustomOnlyChild();
			vector.add(vectorChild);
			stack.push(stackChild);
			table.put("table-key", tableChild);
			custom.add(customChild);
			cycle[0] = cycle;
			cycle[1] = child;
		}
	}

	private static class FixtureChild {
		private final String name;
		private int childMatch;

		FixtureChild(String name, int childMatch) {
			this.name = name;
			this.childMatch = childMatch;
		}
        }

	private static final class VectorChild extends FixtureChild {
		private int vectorOnly = 7;

		VectorChild() {
			super("vector", 7);
		}
	}

	private static final class StackChild extends FixtureChild {
		private int stackOnly = 7;

		StackChild() {
			super("stack", 7);
		}
	}

	private static final class TableChild extends FixtureChild {
		private int tableOnly = 7;

		TableChild() {
			super("table", 7);
		}
	}

	private static final class CustomOnlyChild extends FixtureChild {
		private int customOnly = 7;

		CustomOnlyChild() {
			super("custom", 7);
		}
	}

	private static final class CustomVector extends Vector<FixtureChild> {
	}

	private static final class CapacityRoot {
		int[] values;

		CapacityRoot(int count) {
			values = new int[count];
			Arrays.fill(values, 7);
		}
	}
}
