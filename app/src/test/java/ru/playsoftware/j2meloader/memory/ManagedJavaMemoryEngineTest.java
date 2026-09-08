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
import java.util.ArrayList;
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
	public void autoKnownSearchUsesOneLogicalTraversalAcrossAllPrimitiveTypes() {
		root.longMatch = 7L;
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_EQUAL,
				"7", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertTrue(hasAddress(page, "rootMatch"));
		assertTrue(hasAddress(page, "byteMatch"));
		assertTrue(hasAddress(page, "shortMatch"));
		assertTrue(hasAddress(page, "longMatch"));
		assertEquals(MemoryEngineContract.TYPE_AUTO, engine.session(TOKEN).requestedType);
	}

	@Test
	public void autoUnknownPublishesHiddenBaselineCountAndRefinesAllCurrentTypes() {
		ManagedJavaMemoryEngine.ManagedOperationResult unknown = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_AUTO, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, unknown.code);
		assertEquals(0L, unknown.resultCount);
		assertTrue(unknown.baselineCount > 0L);
		assertEquals(unknown.baselineCount, engine.session(TOKEN).baselineCount);
		assertEquals(0, engine.resultPage(TOKEN, unknown.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE).ids.length);

		root.rootMatch = 8;
		ManagedJavaMemoryEngine.ManagedOperationResult refined = engine.refine(TOKEN, unknown.revision,
				MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_CHANGED,
				MemoryEngineContract.COMPARE_PREVIOUS, "", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, refined.code);
		assertTrue(refined.resultCount > 0L);
		assertEquals(MemoryEngineContract.TYPE_AUTO, engine.session(TOKEN).requestedType);
	}

	@Test
	public void concreteRefineCannotReinterpretRowsFromAnotherPrimitivePlane() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		ManagedJavaMemoryEngine.ManagedOperationResult refined = engine.refine(TOKEN, search.revision,
				MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.PREDICATE_EQUAL,
				MemoryEngineContract.COMPARE_PREVIOUS, "7", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, refined.code);
		assertEquals(0L, refined.resultCount);
	}

	@Test
	public void betweenQueryValidatesTheSecondValueBeforeReplacingTheRevision() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		ManagedJavaMemoryEngine.ManagedOperationResult invalid = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_BETWEEN,
				"1", "not-a-number", 0L);
		assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, invalid.code);
		assertEquals(search.revision, engine.session(TOKEN).revision);
	}

	@Test
	public void betweenQueryUsesBothBoundsBeforeCommittingCandidates() {
		root.rootMatch = 15;
		root.rootChanged = 25;
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_BETWEEN,
				"10", "20", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertTrue(hasAddress(page, "rootMatch"));
		assertFalse(hasAddress(page, "rootChanged"));
	}

	@Test
	public void concreteBatchEditUsesUnionAndReportsTypeSkipsSeparately() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_EQUAL,
				"7", "", 0L);
		long intId = idForAddress(search.revision, "rootMatch");
		long byteId = idForAddress(search.revision, "byteMatch");
		ManagedJavaMemoryEngine.ManagedOperationResult edited = engine.editTyped(TOKEN,
				search.revision, new long[]{intId, byteId}, MemoryEngineContract.TYPE_INT,
				"8", false, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, edited.code);
		assertEquals(1, edited.attempted);
		assertEquals(1, edited.written);
		assertEquals(0, edited.unconfirmed);
		assertEquals(0, edited.notAttempted);
		assertEquals(1, edited.skippedByType);
		assertEquals(8, root.rootMatch);
		assertEquals(7, root.byteMatch);
	}

	@Test
	public void managedInspectorMarksFinalSiblingsReadOnly() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long anchorId = idForAddress(search.revision, "rootMatch");
		ManagedJavaMemoryEngine.ManagedInspection inspection = engine.inspect(TOKEN,
				search.revision, anchorId, MemoryEngineContract.MAX_INSPECT_RADIUS);
		assertEquals(MemoryEngineContract.RESULT_OK, inspection.code);
		int mutableIndex = indexForLabel(inspection, "rootMatch");
		int finalIndex = indexForLabel(inspection, "finalMatch");
		assertTrue(inspection.editable[mutableIndex]);
		assertFalse(inspection.editable[finalIndex]);
	}

	@Test
	public void managedInspectorRetainsWatchAnchorAfterSearchClear() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long anchorId = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.addWatch(TOKEN, search.revision, new long[]{anchorId}, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearSearchResult(TOKEN, 0L, 1L).code);
		ManagedJavaMemoryEngine.ManagedInspection inspection = engine.inspect(TOKEN, 0L,
				anchorId, MemoryEngineContract.MAX_INSPECT_RADIUS);
		assertEquals(MemoryEngineContract.RESULT_OK, inspection.code);
		assertTrue(inspection.ids.length > 0);
	}

	// Modified: regressions for bounded inspection and fail-closed sibling writes.
	@Test
	public void managedInspectorArrayWindowIsPageBoundedAndContainsAnchor() {
		root.array = new int[1000];
		root.array[500] = 71;
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExactInt(TOKEN, 71, 0L);
		assertEquals(1L, search.resultCount);
		long anchor = engine.resultPage(TOKEN, search.revision, 0, 1).ids[0];
		ManagedJavaMemoryEngine.ManagedInspection view = engine.inspect(TOKEN, search.revision,
				anchor, MemoryEngineContract.MAX_INSPECT_RADIUS, false);
		assertEquals(MemoryEngineContract.RESULT_OK, view.code);
		assertTrue(view.ids.length <= MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertTrue(view.ids.length > 1);
		assertTrue(Arrays.stream(view.ids).anyMatch(id -> id == anchor));
	}

	@Test
	public void managedInspectorRejectsFinalAndStaleRevisionBeforeWriting() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long anchor = idForAddress(search.revision, "rootMatch");
		ManagedJavaMemoryEngine.ManagedInspection view = engine.inspect(TOKEN, search.revision,
				anchor, MemoryEngineContract.MAX_INSPECT_RADIUS, false);
		int finalIndex = indexForLabel(view, "finalMatch");
		ManagedJavaMemoryEngine.ManagedOperationResult rejected = engine.editInspector(TOKEN,
				search.revision, anchor, false, view.relativeOffsets[finalIndex],
				MemoryEngineContract.TYPE_INT, 7L, "9", 0L);
		assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, rejected.code);
		assertEquals(0, rejected.attempted);
		assertEquals(7, root.finalMatch);
		assertEquals(MemoryEngineContract.RESULT_OK, engine.refineInt(TOKEN, search.revision,
				MemoryEngineContract.PREDICATE_EQUAL, MemoryEngineContract.COMPARE_PREVIOUS, 7, 0L).code);
		rejected = engine.editInspector(TOKEN, search.revision, anchor, false, 0,
				MemoryEngineContract.TYPE_INT, 7L, "9", 0L);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, rejected.code);
		assertEquals(0, rejected.attempted);
		assertEquals(7, root.rootMatch);
	}

	@Test
	public void typedUnknownCapturesHiddenBaselineThenRefinesInitialAndPrevious() {
		ManagedJavaMemoryEngine.ManagedOperationResult unknown = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_INT, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, unknown.code);
		assertTrue(unknown.revision > 0L);
		assertEquals(0L, unknown.resultCount);
		assertEquals(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
				engine.session(TOKEN).stage);
		assertEquals(MemoryEngineContract.SEARCH_MODE_UNKNOWN, engine.session(TOKEN).mode);
		assertEquals(0, engine.resultPage(TOKEN, unknown.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE).ids.length);

		root.rootChanged = 8;
		ManagedJavaMemoryEngine.ManagedOperationResult previous = engine.refine(TOKEN, unknown.revision,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_INCREASED_BY,
				MemoryEngineContract.COMPARE_PREVIOUS, 1L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, previous.code);
		long changedId = idForAddress(previous.revision, "rootChanged");

		root.rootChanged = 9;
		ManagedJavaMemoryEngine.ManagedOperationResult initial = engine.refine(TOKEN, previous.revision,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_INCREASED_BY,
				MemoryEngineContract.COMPARE_INITIAL, 2L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, initial.code);
		assertTrue(hasId(engine.resultPage(TOKEN, initial.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), changedId));
	}

	@Test
	public void managedFilterIsRevisionAwareAndPreservesBaselineBits() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		long keepId = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK, engine.filter(TOKEN, search.revision,
				new long[]{keepId, keepId}, true, 0L).code);
		ManagedJavaMemoryEngine.ManagedPage kept = engine.resultPage(TOKEN,
				engine.session(TOKEN).revision, 0, MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertEquals(1, kept.ids.length);
		assertEquals(keepId, kept.ids[0]);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				engine.filter(TOKEN, search.revision, new long[]{keepId}, true, 0L).code);
		assertTrue(page.ids.length > kept.ids.length);
	}

	@Test
	public void managedEditChunksUpToRequestLimitAndPrevalidatesAllTypes() {
		MemoryDiscoveryBridge.tailSeen(CapacityRoot.class);
		CapacityRoot capacityRoot = new CapacityRoot(100);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, capacityRoot);
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExactInt(TOKEN, 7, 0L);
		ArrayList<Long> arrayIds = new ArrayList<>();
		for (int offset = 0; offset < search.resultCount; offset += MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
			ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision,
					offset, MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
			for (int index = 0; index < page.ids.length; index++) {
				if (page.addresses[index].startsWith("int[]#")) arrayIds.add(page.ids[index]);
			}
		}
		assertTrue(arrayIds.size() >= 100);
		long[] ids = new long[100];
		for (int index = 0; index < ids.length; index++) ids[index] = arrayIds.get(index);
		ManagedJavaMemoryEngine.ManagedOperationResult edited = engine.editTyped(TOKEN,
				search.revision, ids, "8", false, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, edited.code);
		assertEquals(100, edited.written);
		assertTrue(edited.message.contains("not attempted 0"));

		MemoryDiscoveryBridge.tailSeen(FixtureRoot.class);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, root);
		ManagedJavaMemoryEngine.ManagedOperationResult byteSearch = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_BYTE, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		long byteId = idForAddress(byteSearch.revision, "byteMatch");
		assertEquals(MemoryEngineContract.RESULT_OK, engine.addWatch(TOKEN, byteSearch.revision,
				new long[]{byteId}, 0L).code);
		ManagedJavaMemoryEngine.ManagedOperationResult longSearch = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_LONG, MemoryEngineContract.PREDICATE_EQUAL,
				9_007_199_254_740_993L, 0L, 0L);
		long longId = idForAddress(longSearch.revision, "longMatch");
		assertEquals(MemoryEngineContract.RESULT_OK, engine.addWatch(TOKEN, longSearch.revision,
				new long[]{longId}, 0L).code);
		ManagedJavaMemoryEngine.ManagedOperationResult rejected = engine.editTyped(TOKEN, 0L,
				new long[]{byteId, longId}, "200", true, 0L);
		assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST, rejected.code);
		assertEquals(7, root.byteMatch);
		assertEquals(9_007_199_254_740_993L, root.longMatch);
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
	public void candidateBufferHandlesZeroAndBelowInitialCapacityLimits() {
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(CapacityRoot.class);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, new CapacityRoot(1));
		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, 1, 100, 100));
		assertEquals(MemoryEngineContract.RESULT_OK, engine.startExactInt(TOKEN, 7, 0).code);

		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, 0, 100, 100));
		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
				engine.startExactInt(TOKEN, 7, 0).code);

		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, -1, 100, 100));
		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
				engine.startExactInt(TOKEN, 7, 0).code);
	}

	@Test
	public void resultStorageBudgetCountsRetainedStagedAndFinishCopies() {
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(CapacityRoot.class);
		CapacityRoot capacityRoot = new CapacityRoot(30);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, capacityRoot);
		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, 30, 100, 100, 2_000L));

		ManagedJavaMemoryEngine.ManagedOperationResult first = engine.startExactInt(TOKEN, 7, 0);
		assertEquals(MemoryEngineContract.RESULT_OK, first.code);
		ManagedJavaMemoryEngine.ManagedOperationResult rejected = engine.startExactInt(TOKEN, 7, 0);
		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT, rejected.code);
		assertEquals(first.revision, engine.session(TOKEN).revision);
		assertEquals(first.resultCount, engine.session(TOKEN).resultCount);
	}

	@Test
	public void exactSearchUsesTheActualPrimitiveTypeForFieldsAndArrays() {
		assertTypedMatch(MemoryEngineContract.TYPE_BYTE, 7L, "byteMatch", "7");
		assertTypedMatch(MemoryEngineContract.TYPE_SHORT, 7L, "shortMatch", "7");
		assertTypedMatch(MemoryEngineContract.TYPE_CHAR, 0xffffL, "charMatch", "65535");
		assertTypedMatch(MemoryEngineContract.TYPE_INT, 7L, "rootMatch", "7");
		long longValue = 9_007_199_254_740_993L;
		assertTypedMatch(MemoryEngineContract.TYPE_LONG, longValue, "longMatch",
				Long.toString(longValue));
		assertTypedMatch(MemoryEngineContract.TYPE_FLOAT,
				Float.floatToRawIntBits(1.5f) & 0xffffffffL, "floatMatch", "1.5");
		assertTypedMatch(MemoryEngineContract.TYPE_DOUBLE,
				Double.doubleToRawLongBits(1.5d), "doubleMatch", "1.5");
	}

	@Test
	public void typedEditWatchAndRelativeRefinePreserveLongAndFloatingBits() {
		long longValue = 9_007_199_254_740_993L;
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_LONG, MemoryEngineContract.PREDICATE_EQUAL, longValue, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		long longId = idForAddress(search.revision, "longMatch");
		assertEquals(MemoryEngineContract.RESULT_OK, engine.editTyped(TOKEN, search.revision,
				new long[]{longId}, "9007199254740994", false, 0L).code);
		assertEquals(9_007_199_254_740_994L, root.longMatch);

		ManagedJavaMemoryEngine.ManagedOperationResult floatSearch = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_FLOAT, MemoryEngineContract.PREDICATE_EQUAL,
				Float.floatToRawIntBits(1.5f) & 0xffffffffL, 0L, 0L);
		long floatId = idForAddress(floatSearch.revision, "floatMatch");
		assertEquals(MemoryEngineContract.RESULT_OK, engine.addWatch(TOKEN,
				floatSearch.revision, new long[]{floatId}, 0L).code);
		root.floatMatch = 2.5f;
		long delta = Float.floatToRawIntBits(1.0f) & 0xffffffffL;
		ManagedJavaMemoryEngine.ManagedOperationResult relative = engine.refine(TOKEN,
				floatSearch.revision, MemoryEngineContract.TYPE_FLOAT,
				MemoryEngineContract.PREDICATE_INCREASED_BY,
				MemoryEngineContract.COMPARE_PREVIOUS, delta, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, relative.code);
		assertTrue(hasAddress(engine.resultPage(TOKEN, relative.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), "floatMatch"));

		ManagedJavaMemoryEngine.ManagedOperationResult doubleSearch = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_DOUBLE, MemoryEngineContract.PREDICATE_EQUAL,
				Double.doubleToRawLongBits(1.5d), 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, doubleSearch.code);
		long doubleId = idForAddress(doubleSearch.revision, "doubleMatch");
		assertEquals(MemoryEngineContract.RESULT_OK, engine.setFreezeLockTyped(TOKEN,
				doubleSearch.revision, new long[]{doubleId}, "2.5", false, 0L).code);
		root.doubleMatch = 0.5d;
		assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(TOKEN, 0L).code);
		assertEquals(2.5d, root.doubleMatch, 0.0d);
		ManagedJavaMemoryEngine.ManagedPage watches = engine.watchPage(TOKEN);
		for (int index = 0; index < watches.ids.length; index++) {
			if (watches.ids[index] == doubleId) {
				assertEquals("2.5", watches.values[index]);
				assertEquals(MemoryEngineContract.FREEZE_LOCK, watches.freezeModes[index]);
				break;
			}
		}
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearFreeze(TOKEN, new long[]{doubleId}, 0L).code);
	}

	@Test
	public void knownPredicatesFilterTypedCandidatesBeforeRelativeRefine() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_GREATER,
				2L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		long changedId = idForAddress(search.revision, "rootMatch");
		root.rootMatch = 8;
		ManagedJavaMemoryEngine.ManagedOperationResult changed = engine.refine(TOKEN,
				search.revision, MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_CHANGED, MemoryEngineContract.COMPARE_PREVIOUS,
				0L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, changed.code);
		assertTrue(hasId(engine.resultPage(TOKEN, changed.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), changedId));
	}

	private void assertTypedMatch(int type, long valueBits, String address, String text) {
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN, type,
				MemoryEngineContract.PREDICATE_EQUAL, valueBits, 0L, 0L);
		assertEquals("type=" + type, MemoryEngineContract.RESULT_OK, result.code);
		long id = idForAddress(result.revision, address);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		for (int index = 0; index < page.ids.length; index++) {
			if (page.ids[index] == id) {
				assertEquals(text, page.values[index]);
				assertEquals(type, page.types[index]);
				return;
			}
		}
		throw new AssertionError("Missing typed row " + address);
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

	private static int indexForLabel(ManagedJavaMemoryEngine.ManagedInspection inspection,
	                                String fragment) {
		for (int index = 0; index < inspection.labels.length; index++) {
			if (inspection.labels[index].contains(fragment)) return index;
		}
		throw new AssertionError("Missing managed Inspector row containing " + fragment);
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
		byte byteMatch = 7;
		short shortMatch = 7;
		char charMatch = 0xffff;
		long longMatch = 9_007_199_254_740_993L;
		float floatMatch = 1.5f;
		double doubleMatch = 1.5d;
		final int finalMatch = 7;
		FixtureChild child = new FixtureChild("direct", 7);
		int[] array = {7, 4, 7};
		byte[] byteArray = {7, 4};
		short[] shortArray = {7, 4};
		char[] charArray = {0xffff, 4};
		long[] longArray = {9_007_199_254_740_993L, 4L};
		float[] floatArray = {1.5f, 4f};
		double[] doubleArray = {1.5d, 4d};
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
