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

import java.util.Hashtable;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
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
		assertTrue(hasAddress(page, "int[]#"));
		assertFalse(hasAddress(page, "finalMatch"));
		assertFalse(hasAddress(page, "customOnly"));
		assertTrue(allIdsUseManagedNamespace(page));
	}

	@Test
	public void exactScanFindsGuestStateOutsideTheMidletGraph() {
		DetachedDisplayRoot displayRoot = new DetachedDisplayRoot();
		MemoryDiscoveryBridge.setCurrentDisplayable(displayRoot);

		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				424_242L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertEquals(1L, result.resultCount);
		assertTrue(hasAddress(page, "escapedMatch"));
	}

	@Test
	public void auxiliaryDisplayRootDoesNotDuplicateReachableObjects() {
		root.child.childMatch = 707_070;
		MemoryDiscoveryBridge.setCurrentDisplayable(root.child);

		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				707_070L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		assertEquals(1L, result.resultCount);
		assertTrue(hasAddress(engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), "childMatch"));
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
	public void managedGroupMatchesTermsWithinOneObjectAndPublishesObservedValues() {
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startGroup(TOKEN,
				MemoryEngineContract.TYPE_INT, new String[]{"101", "202"}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertTrue(hasAddress(page, "groupFirst"));
		assertTrue(hasAddress(page, "groupSecond"));
		assertEquals(2, page.ids.length);
		assertEquals("101", valueForAddress(page, "groupFirst"));
		assertEquals("202", valueForAddress(page, "groupSecond"));
		assertEquals(ManagedJavaMemoryIds.ownerHandle(page.ids[0]),
				ManagedJavaMemoryIds.ownerHandle(page.ids[1]));
	}

	@Test
	public void managedGroupDuplicateTermsUseDistinctSlots() {
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startGroup(TOKEN,
				MemoryEngineContract.TYPE_INT, new String[]{"7", "7"}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertTrue(page.ids.length >= 2);
		for (int index = 0; index < page.ids.length; index++) {
			for (int other = index + 1; other < page.ids.length; other++) {
				assertFalse("group published the same logical slot twice",
						page.ids[index] == page.ids[other]);
			}
		}
	}

	@Test
	public void managedGroupDoesNotCombineTermsFromDifferentObjects() {
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startGroup(TOKEN,
				MemoryEngineContract.TYPE_INT, new String[]{"505", "606"}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		assertEquals(0L, result.resultCount);
	}

	@Test
	public void managedGroupMatchesIntArraySlotsWithTheSameOwner() {
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startGroup(TOKEN,
				MemoryEngineContract.TYPE_INT, new String[]{"7", "4"}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		long first = 0L;
		long second = 0L;
		for (int index = 0; index < page.ids.length; index++) {
			if (!page.addresses[index].contains("int[]#")) continue;
			if (first == 0L) first = page.ids[index];
			else if (second == 0L) second = page.ids[index];
		}
		assertTrue(first != 0L && second != 0L);
		assertEquals(ManagedJavaMemoryIds.ownerHandle(first),
				ManagedJavaMemoryIds.ownerHandle(second));
	}

	@Test
	public void managedGroupSkipsHugeIrrelevantPrimitiveArraysBeforeApplyingLimits() {
		root.byteArray = new byte[101];
		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				1_000, 1_000, 1_000, 10_000, 1_000, 100));
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startGroup(TOKEN,
				MemoryEngineContract.TYPE_INT, new String[]{"7", "4"}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		assertTrue(result.resultCount > 0L);
	}

	@Test
	public void managedGroupMatchesStaticFieldsOnlyWithinTheDeclaringClassOwner() {
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.startGroup(TOKEN,
				MemoryEngineContract.TYPE_INT, new String[]{"303", "404"}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, result.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, result.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		long first = 0L;
		long second = 0L;
		for (int index = 0; index < page.ids.length; index++) {
			if (page.addresses[index].contains("staticGroupFirst")) first = page.ids[index];
			if (page.addresses[index].contains("staticGroupSecond")) second = page.ids[index];
		}
		assertTrue(first != 0L && second != 0L);
		assertEquals(ManagedJavaMemoryIds.ownerHandle(first),
				ManagedJavaMemoryIds.ownerHandle(second));
	}

	@Test
	public void managedUndoPublishesFreshRevisionAndRejectsStaleMutation() {
		ManagedJavaMemoryEngine.ManagedOperationResult first = startExact();
		long staleId = idForAddress(first.revision, "rootMatch");
		root.rootMatch = 8;
		ManagedJavaMemoryEngine.ManagedOperationResult refined = engine.refine(TOKEN, first.revision,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_CHANGED,
				MemoryEngineContract.COMPARE_PREVIOUS, "", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, refined.code);
		ManagedJavaMemoryEngine.ManagedOperationResult undone = engine.undo(TOKEN, refined.revision, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, undone.code);
		assertTrue(undone.revision > refined.revision);
		assertEquals(first.resultCount, undone.resultCount);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				engine.edit(TOKEN, refined.revision,
						new long[]{staleId}, 9, false, 0L).code);
		assertEquals(8, root.rootMatch);
	}

	@Test
	public void managedUnknownChangedUndoRestoresTheBaselineStage() {
		ManagedJavaMemoryEngine.ManagedOperationResult baseline = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_INT, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, baseline.code);
		root.rootChanged = 8;
		ManagedJavaMemoryEngine.ManagedOperationResult changed = engine.refine(TOKEN,
				baseline.revision, MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_CHANGED, MemoryEngineContract.COMPARE_PREVIOUS,
				"", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, changed.code);
		ManagedJavaMemoryEngine.ManagedOperationResult undone = engine.undo(TOKEN,
				changed.revision, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, undone.code);
		assertEquals(baseline.resultCount, undone.resultCount);
		assertEquals(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
				engine.session(TOKEN).stage);
	}

	@Test
	public void managedFilterUndoRestoresThePreviousCandidateMembership() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long keepId = idForAddress(search.revision, "rootMatch");
		ManagedJavaMemoryEngine.ManagedOperationResult filtered = engine.filter(TOKEN,
				search.revision, new long[]{keepId}, true, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, filtered.code);
		assertEquals(1L, filtered.resultCount);
		ManagedJavaMemoryEngine.ManagedOperationResult undone = engine.undo(TOKEN,
				filtered.revision, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, undone.code);
		assertEquals(search.resultCount, undone.resultCount);
		assertEquals(MemoryEngineContract.SEARCH_SESSION_CANDIDATES,
				engine.session(TOKEN).stage);
	}

	@Test
	public void managedClearSearchDropsHistoryDepth() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		assertEquals(MemoryEngineContract.RESULT_OK, engine.refine(TOKEN, search.revision,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				MemoryEngineContract.COMPARE_PREVIOUS, 7L, 0L, 0L).code);
		assertTrue(engine.session(TOKEN).historyDepth > 0);
		assertEquals(MemoryEngineContract.RESULT_OK, engine.clearSearchResult(TOKEN, 0L, 1L).code);
		assertEquals(0, engine.session(TOKEN).historyDepth);
		assertEquals(MemoryEngineContract.SEARCH_SESSION_EMPTY, engine.session(TOKEN).stage);
	}

	@Test
	public void managedWatchSurvivesUndoAndClearSearch() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.addWatch(TOKEN, search.revision, new long[]{id}, 0L).code);
		root.rootMatch = 8;
		ManagedJavaMemoryEngine.ManagedOperationResult refined = engine.refine(TOKEN,
				search.revision, MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_CHANGED, MemoryEngineContract.COMPARE_PREVIOUS,
				"", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, refined.code);
		assertEquals(MemoryEngineContract.RESULT_OK, engine.undo(TOKEN, refined.revision, 0L).code);
		assertEquals(1, engine.watchPage(TOKEN).ids.length);
		assertEquals(MemoryEngineContract.RESULT_OK, engine.clearSearchResult(TOKEN, 0L, 1L).code);
		assertEquals(1, engine.watchPage(TOKEN).ids.length);
	}

	@Test
	public void autoUnknownPublishesBaselineCountAndRefinesAllCurrentTypes() {
		ManagedJavaMemoryEngine.ManagedOperationResult unknown = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_AUTO, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, unknown.code);
		assertTrue(unknown.baselineCount > 0L);
		assertEquals(unknown.baselineCount, unknown.resultCount);
		assertEquals(unknown.baselineCount, engine.session(TOKEN).baselineCount);
		assertTrue(engine.resultPage(TOKEN, unknown.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE).ids.length > 0);

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
	public void managedUndoRestoresRequestedTypeMetadataAfterRefine() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_EQUAL,
				"7", "", 0L);
		assertEquals(MemoryEngineContract.TYPE_AUTO, engine.session(TOKEN).requestedType);

		ManagedJavaMemoryEngine.ManagedOperationResult refined = engine.refine(TOKEN,
				search.revision, MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_EQUAL, MemoryEngineContract.COMPARE_PREVIOUS,
				"7", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, refined.code);
		assertEquals(MemoryEngineContract.TYPE_INT, engine.session(TOKEN).requestedType);

		ManagedJavaMemoryEngine.ManagedOperationResult undone = engine.undo(TOKEN,
				refined.revision, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, undone.code);
		assertEquals(MemoryEngineContract.TYPE_AUTO, engine.session(TOKEN).requestedType);
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
	public void managedUnknownResultsSupportInspectorMutation() {
		ManagedJavaMemoryEngine.ManagedOperationResult baseline = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_INT, 0L);
		assertTrue(baseline.resultCount > 0L);
		long anchor = idForAddress(baseline.revision, "rootMatch");
		ManagedJavaMemoryEngine.ManagedInspection inspection = engine.inspect(TOKEN,
				baseline.revision, anchor, MemoryEngineContract.MAX_INSPECT_RADIUS, false);
		assertEquals(MemoryEngineContract.RESULT_OK, inspection.code);
		int mutableIndex = indexForLabel(inspection, "rootMatch");
		assertTrue(inspection.editable[mutableIndex]);
		ManagedJavaMemoryEngine.ManagedOperationResult edited = engine.editInspector(TOKEN,
				baseline.revision, anchor, false, inspection.relativeOffsets[mutableIndex],
				MemoryEngineContract.TYPE_INT, inspection.expectedBits[mutableIndex], "8", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, edited.code);
		assertEquals(8, root.rootMatch);
	}

	@Test
	public void managedUnknownResultsSupportDirectEditAndFreeze() {
		ManagedJavaMemoryEngine.ManagedOperationResult baseline = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_INT, 0L);
		long id = idForAddress(baseline.revision, "rootMatch");
		ManagedJavaMemoryEngine.ManagedOperationResult edited = engine.editTyped(TOKEN,
				baseline.revision, new long[]{id}, MemoryEngineContract.TYPE_INT, "8", false, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, edited.code);
		assertEquals(8, root.rootMatch);
		assertEquals(MemoryEngineContract.RESULT_OK, engine.setFreezeLockTyped(TOKEN,
				baseline.revision, new long[]{id}, "9", false, 0L).code);
		root.rootMatch = 1;
		assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(TOKEN, 0L).code);
		assertEquals(9, root.rootMatch);
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

	// Regressions for bounded inspection and fail-closed sibling writes.
	@Test
	public void managedInspectorArrayWindowIsPageBoundedAndContainsAnchor() {
		root.array = new int[1000];
		root.array[500] = 71;
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 71L, 0L, 0L);
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
		assertEquals(MemoryEngineContract.RESULT_OK, engine.refine(TOKEN, search.revision,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				MemoryEngineContract.COMPARE_PREVIOUS, 7L, 0L, 0L).code);
		rejected = engine.editInspector(TOKEN, search.revision, anchor, false, 0,
				MemoryEngineContract.TYPE_INT, 7L, "9", 0L);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, rejected.code);
		assertEquals(0, rejected.attempted);
		assertEquals(7, root.rootMatch);
	}

	@Test
	public void typedUnknownCapturesBaselineThenRefinesInitialAndPrevious() {
		ManagedJavaMemoryEngine.ManagedOperationResult unknown = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_INT, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, unknown.code);
		assertTrue(unknown.revision > 0L);
		assertTrue(unknown.resultCount > 0L);
		assertEquals(MemoryEngineContract.SEARCH_SESSION_UNKNOWN_BASELINE,
				engine.session(TOKEN).stage);
		assertEquals(MemoryEngineContract.SEARCH_MODE_UNKNOWN, engine.session(TOKEN).mode);
		assertTrue(engine.resultPage(TOKEN, unknown.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE).ids.length > 0);

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
	public void firstUnknownBaselineResultCanBeAddedToWatchBeforeRefine() {
		ManagedJavaMemoryEngine.ManagedOperationResult unknown = engine.startUnknown(TOKEN,
				MemoryEngineContract.TYPE_INT, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, unknown.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, unknown.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		assertTrue(page.ids.length > 0);

		long firstId = page.ids[0];
		ManagedJavaMemoryEngine.ManagedOperationResult watched = engine.addWatch(TOKEN,
				unknown.revision, new long[]{firstId}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, watched.code);
		assertEquals(1, watched.watchCount);

		ManagedJavaMemoryEngine.ManagedPage watchPage = engine.watchPage(TOKEN);
		assertEquals(1, watchPage.ids.length);
		assertEquals(firstId, watchPage.ids[0]);
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
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		ArrayList<Long> arrayIds = new ArrayList<>();
		for (int offset = 0; offset < search.resultCount; offset += MemoryEngineContract.MAX_RESULT_PAGE_SIZE) {
			ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision,
					offset, MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
			for (int index = 0; index < page.ids.length; index++) {
				if (page.addresses[index].contains("int[]#")) arrayIds.add(page.ids[index]);
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

		ManagedJavaMemoryEngine.ManagedOperationResult changed = engine.refine(TOKEN,
				first.revision, MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_CHANGED,
				MemoryEngineContract.COMPARE_PREVIOUS, 0L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, changed.code);
		assertTrue(hasId(engine.resultPage(TOKEN, changed.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), changedId));

		ManagedJavaMemoryEngine.ManagedOperationResult unchanged = engine.refine(TOKEN,
				changed.revision, MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_UNCHANGED,
				MemoryEngineContract.COMPARE_PREVIOUS, 0L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, unchanged.code);
		assertTrue(hasId(engine.resultPage(TOKEN, unchanged.revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE), changedId));

		ManagedJavaMemoryEngine.ManagedOperationResult stale = engine.refine(TOKEN,
				first.revision, MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				MemoryEngineContract.COMPARE_PREVIOUS, 9L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, stale.code);
		assertEquals(unchanged.revision, engine.session(TOKEN).revision);

		root.rootChanged = 7;
		ManagedJavaMemoryEngine.ManagedOperationResult fresh = startExact();
		root.rootChanged = 11;
		ManagedJavaMemoryEngine.ManagedOperationResult initial = engine.refine(TOKEN,
				fresh.revision, MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_CHANGED,
				MemoryEngineContract.COMPARE_INITIAL, 0L, 0L, 0L);
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
				new long[]{id}, 12, false, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, edit.code);
		assertEquals(12, root.rootMatch);

		assertEquals(MemoryEngineContract.RESULT_OK, engine.setFreezeLock(TOKEN,
				search.revision, new long[]{id}, 13, 0).code);
		root.rootMatch = 1;
		assertEquals(MemoryEngineContract.RESULT_OK, engine.freezeTick(TOKEN, 0).code);
		assertEquals(13, root.rootMatch);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearFreeze(TOKEN, new long[]{id}, 0).code);

		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearSearchResult(TOKEN, 0L, 0L).code);
		assertEquals(1, engine.watchPage(TOKEN).ids.length);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.edit(TOKEN, 0L, new long[]{id}, 20, true, 0L).code);
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
				engine.refine(TOKEN, search.revision, MemoryEngineContract.TYPE_INT,
						MemoryEngineContract.PREDICATE_EQUAL, MemoryEngineContract.COMPARE_PREVIOUS,
						7L, 0L, 0L).code);
	}

	@Test
	public void candidateBufferGrowsToNonPowerOfTwoLimitAndRejectsTheNextRow() {
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(CapacityRoot.class);
		CapacityRoot capacityRoot = new CapacityRoot(30);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, capacityRoot);
		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, 30, 100, 100));

		ManagedJavaMemoryEngine.ManagedOperationResult accepted = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, accepted.code);
		assertEquals(30L, accepted.resultCount);

		capacityRoot.values = new int[31];
		Arrays.fill(capacityRoot.values, 7);
		ManagedJavaMemoryEngine.ManagedOperationResult rejected = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
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
		assertEquals(MemoryEngineContract.RESULT_OK, engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L).code);

		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, 0, 100, 100));
		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
				engine.startExact(TOKEN, MemoryEngineContract.TYPE_INT,
						MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L).code);

		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, -1, 100, 100));
		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
				engine.startExact(TOKEN, MemoryEngineContract.TYPE_INT,
						MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L).code);
	}

	@Test
	public void resultStorageBudgetCountsRetainedStagedAndFinishCopies() {
		MemoryDiscoveryBridge.install(TOKEN, getClass().getClassLoader());
		MemoryDiscoveryBridge.tailSeen(CapacityRoot.class);
		CapacityRoot capacityRoot = new CapacityRoot(30);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN, capacityRoot);
		engine = new ManagedJavaMemoryEngine(new ManagedJavaMemoryEngine.Limits(
				100, 100, 100, 30, 100, 100, 2_000L));

		ManagedJavaMemoryEngine.ManagedOperationResult first = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, first.code);
		ManagedJavaMemoryEngine.ManagedOperationResult rejected = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
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
				engine.edit(TOKEN, search.revision, new long[]{fabricated}, 1, false, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				engine.edit(TOKEN, search.revision, new long[]{1L}, 1, false, 0L).code);
	}


	@Test
	public void incrementalFollowsCallerOrderAndSupportsBothStepDirections() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long changed = idForAddress(search.revision, "rootChanged");
		long match = idForAddress(search.revision, "rootMatch");
		ManagedJavaMemoryEngine.ManagedOperationResult decrement = engine.editIncremental(TOKEN,
				search.revision, new long[]{changed, match},
				MemoryEngineContract.TYPE_INT, "100", "-3", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, decrement.code);
		assertEquals(2, decrement.written);
		assertEquals(100, root.rootChanged);
		assertEquals(97, root.rootMatch);
		ManagedJavaMemoryEngine.ManagedOperationResult increment = engine.editIncremental(TOKEN,
				search.revision, new long[]{match, changed},
				MemoryEngineContract.TYPE_INT, "200", "2", 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, increment.code);
		assertEquals(200, root.rootMatch);
		assertEquals(202, root.rootChanged);
	}

	@Test
	public void incrementalRejectsOverflowAndMixedTypesBeforeWriting() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long match = idForAddress(search.revision, "rootMatch");
		long changed = idForAddress(search.revision, "rootChanged");
		assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST,
				engine.editIncremental(TOKEN, search.revision, new long[]{match, changed},
						MemoryEngineContract.TYPE_INT, "2147483647", "1", 0L).code);
		assertEquals(7, root.rootMatch);
		assertEquals(7, root.rootChanged);

		ManagedJavaMemoryEngine.ManagedOperationResult mixed = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_AUTO, MemoryEngineContract.PREDICATE_EQUAL,
				"7", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_INVALID_REQUEST,
				engine.editIncremental(TOKEN, mixed.revision,
						new long[]{idForAddress(mixed.revision, "rootMatch"),
								idForAddress(mixed.revision, "byteMatch")},
						MemoryEngineContract.TYPE_INT, "50", "1", 0L).code);
		assertEquals(7, root.rootMatch);
		assertEquals(7, root.byteMatch);
	}

	@Test
	public void revertRestoresValueImmediatelyBeforeLastEditAndRemovesOnlyOnSuccess() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		root.rootMatch = 120;
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "999", false, 0L).code);
		root.rootMatch = 500;
		ManagedJavaMemoryEngine.ManagedOperationResult conflict = engine.revertAndRemove(TOKEN,
				search.revision, new long[]{id}, 0L);
		assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE, conflict.code);
		assertEquals(500, root.rootMatch);
		assertTrue(hasId(engine.resultPage(TOKEN, search.revision, 0, 100), id));
		root.rootMatch = 999;
		ManagedJavaMemoryEngine.ManagedOperationResult restored = engine.revertAndRemove(TOKEN,
				search.revision, new long[]{id}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, restored.code);
		assertEquals(1, restored.written);
		assertEquals(120, root.rootMatch);
		assertFalse(hasId(engine.resultPage(TOKEN, restored.revision, 0, 100), id));
	}

	@Test
	public void retryAfterConfirmedRestoreOnlyRemovesIfRestoredValueStillMatches()
			throws ReflectiveOperationException {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "900", false, 0L).code);
		Field journalField = ManagedJavaMemoryEngine.class.getDeclaredField("editJournal");
		journalField.setAccessible(true);
		ManagedMemoryMutationJournal journal = (ManagedMemoryMutationJournal) journalField.get(engine);
		journal.markRestored(id, journal.get(id));
		root.rootMatch = 42;
		assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE,
				engine.revertAndRemove(TOKEN, search.revision, new long[]{id}, 0L).code);
		assertEquals(42, root.rootMatch);
		assertTrue(hasId(engine.resultPage(TOKEN, search.revision, 0, 100), id));

		root.rootMatch = 7;
		ManagedJavaMemoryEngine.ManagedOperationResult retry = engine.revertAndRemove(TOKEN,
				search.revision, new long[]{id}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, retry.code);
		assertFalse(hasId(engine.resultPage(TOKEN, retry.revision, 0, 100), id));
		assertEquals(null, journal.get(id));
	}

	@Test
	public void revertAndRemoveDoesNotSilentlyDropAnActiveFreezeTarget() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.setFreezeLock(TOKEN, search.revision, new long[]{id}, 900, 0L).code);

		ManagedJavaMemoryEngine.ManagedOperationResult blocked = engine.revertAndRemove(TOKEN,
				search.revision, new long[]{id}, 0L);
		assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE, blocked.code);
		assertTrue(hasId(engine.resultPage(TOKEN, search.revision, 0, 100), id));
		assertEquals(7, root.rootMatch);

		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearFreeze(TOKEN, new long[]{id}, 0L).code);
		ManagedJavaMemoryEngine.ManagedOperationResult removed = engine.revertAndRemove(TOKEN,
				search.revision, new long[]{id}, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, removed.code);
		assertFalse(hasId(engine.resultPage(TOKEN, removed.revision, 0, 100), id));
	}

	@Test
	public void clearSearchReleasesJournalHistoryForUnretainedOwners()
			throws ReflectiveOperationException {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "901", false, 0L).code);

		Field journalField = ManagedJavaMemoryEngine.class.getDeclaredField("editJournal");
		journalField.setAccessible(true);
		ManagedMemoryMutationJournal journal = (ManagedMemoryMutationJournal) journalField.get(engine);
		assertTrue(journal.get(id) != null);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearSearchResult(TOKEN, search.revision, 0L).code);
		assertEquals(null, journal.get(id));
	}

	@Test
	public void noOpBatchCanRunWhenEditHistoryIsFullButAWriteCannot()
			throws ReflectiveOperationException {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long unchanged = idForAddress(search.revision, "rootMatch");
		long changing = idForAddress(search.revision, "rootChanged");
		ManagedMemoryMutationJournal journal = new ManagedMemoryMutationJournal();
		for (int index = 0; index < 1024; index++) {
			long fakeId = ManagedJavaMemoryIds.encode(ManagedJavaMemoryEngine.KIND_ARRAY,
					index + 5000, 0);
			journal.record(fakeId, MemoryEngineContract.TYPE_INT, 0, 1);
		}
		Field field = ManagedJavaMemoryEngine.class.getDeclaredField("editJournal");
		field.setAccessible(true);
		field.set(engine, journal);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{unchanged, changing},
						MemoryEngineContract.TYPE_INT, "7", false, 0L).code);
		assertEquals(7, root.rootMatch);
		assertEquals(7, root.rootChanged);
		assertEquals(null, journal.get(unchanged));
		root.rootChanged = 4;
		assertEquals(MemoryEngineContract.RESULT_RESOURCE_LIMIT,
				engine.editTyped(TOKEN, search.revision, new long[]{unchanged, changing},
						MemoryEngineContract.TYPE_INT, "7", false, 0L).code);
		assertEquals(4, root.rootChanged);
	}

	@Test
	public void candidateHistoryPrunesUnwatchedArraySlotAfterClear()
			throws ReflectiveOperationException {
		root.array = new int[]{7, 7};
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
		long first = 0L;
		long second = 0L;
		for (int index = 0; index < page.ids.length; index++) {
			if (!"array".equals(page.sourceFields[index])) continue;
			if (ManagedJavaMemoryIds.slot(page.ids[index]) == 0) first = page.ids[index];
			if (ManagedJavaMemoryIds.slot(page.ids[index]) == 1) second = page.ids[index];
		}
		assertTrue(first != 0L && second != 0L);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.addWatch(TOKEN, search.revision, new long[]{first}, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{first, second},
						MemoryEngineContract.TYPE_INT, "90", false, 0L).code);
		Field field = ManagedJavaMemoryEngine.class.getDeclaredField("editJournal");
		field.setAccessible(true);
		ManagedMemoryMutationJournal journal = (ManagedMemoryMutationJournal) field.get(engine);
		assertTrue(journal.get(first) != null && journal.get(second) != null);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.clearSearchResult(TOKEN, search.revision, 0L).code);
		assertTrue(journal.get(first) != null);
		assertEquals(null, journal.get(second));
	}

	@Test
	public void repeatedEditsRevertOnlyTheMostRecentConfirmedMutation() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "800", false, 0L).code);
		root.rootMatch = 120;
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "900", false, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.revertAndRemove(TOKEN, search.revision, new long[]{id}, 0L).code);
		assertEquals(120, root.rootMatch);
	}

	@Test
	public void noOpEditDoesNotEraseLastActualMutation() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "900", false, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "900", false, 0L).code);
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.revertAndRemove(TOKEN, search.revision, new long[]{id}, 0L).code);
		assertEquals(7, root.rootMatch);
	}

	@Test
	public void partialRestoreKeepsConflictingResultAndRemovesUneditedResult() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long first = idForAddress(search.revision, "rootMatch");
		long second = idForAddress(search.revision, "rootChanged");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{first},
						MemoryEngineContract.TYPE_INT, "900", false, 0L).code);
		root.rootMatch = 42;
		ManagedJavaMemoryEngine.ManagedOperationResult result = engine.revertAndRemove(TOKEN,
				search.revision, new long[]{first, second}, 0L);
		assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE, result.code);
		assertTrue(hasId(engine.resultPage(TOKEN, result.revision, 0, 100), first));
		assertFalse(hasId(engine.resultPage(TOKEN, result.revision, 0, 100), second));
		assertEquals(42, root.rootMatch);
	}

	@Test
	public void revertedCandidatesRespectOriginalRevisionAndStaleIds() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		ManagedJavaMemoryEngine.ManagedOperationResult refined = engine.refine(TOKEN,
				search.revision, MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				MemoryEngineContract.COMPARE_PREVIOUS, "7", "", 0L);
		assertEquals(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				engine.revertAndRemove(TOKEN, search.revision, new long[]{id}, 0L).code);
		assertEquals(7, root.rootMatch);
	}
	@Test
	@SuppressWarnings("unchecked")
	public void collectedWeakOwnerNeverRebindsDuringRevert() throws ReflectiveOperationException {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long id = idForAddress(search.revision, "rootMatch");
		assertEquals(MemoryEngineContract.RESULT_OK,
				engine.editTyped(TOKEN, search.revision, new long[]{id},
						MemoryEngineContract.TYPE_INT, "900", false, 0L).code);
		// Clearing this exact weak handle models collection without relying on nondeterministic GC.
		Field ownersField = ManagedJavaMemoryEngine.class.getDeclaredField("owners");
		ownersField.setAccessible(true);
		Map<Long, Object> owners = (Map<Long, Object>) ownersField.get(engine);
		Object owner = owners.get(ManagedJavaMemoryIds.ownerHandle(id));
		Field weakField = owner.getClass().getDeclaredField("weakOwner");
		weakField.setAccessible(true);
		((WeakReference<?>) weakField.get(owner)).clear();

		ManagedJavaMemoryEngine.ManagedOperationResult rejected = engine.revertAndRemove(TOKEN,
				search.revision, new long[]{id}, 0L);
		assertEquals(MemoryEngineContract.RESULT_PARTIAL_WRITE, rejected.code);
		assertEquals(900, root.rootMatch);
		assertTrue(hasId(engine.resultPage(TOKEN, search.revision, 0, 100), id));
	}

	@Test
	public void arraysWithEqualValuesRetainDistinctRuntimeIdentities() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		long first = idForAddress(search.revision, "FixtureRoot.staticArray");
		long second = idForAddress(search.revision, "FixtureRoot.array [instance]");
		assertFalse(first == second);
		assertFalse(ManagedJavaMemoryIds.ownerHandle(first)
				== ManagedJavaMemoryIds.ownerHandle(second));
		assertEquals(7, root.array[0]);
		assertEquals(7, FixtureRoot.staticArray[0]);
	}

	@Test
	public void primitiveFieldSourceUsesExactDeclaringClassNameAndType() {
		ManagedJavaMemoryEngine.ManagedOperationResult search = startExact();
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
		boolean foundStatic = false;
		boolean foundInstance = false;
		for (int index = 0; index < page.ids.length; index++) {
			if ("staticMatch".equals(page.sourceFields[index])) {
				foundStatic = true;
				assertEquals(MemoryEngineContract.SOURCE_STATIC_FIELD, page.sourceKinds[index]);
				assertEquals(FixtureRoot.class.getName(), page.sourceClasses[index]);
				assertEquals("int", page.sourceTypes[index]);
			}
			if ("rootMatch".equals(page.sourceFields[index])) {
				foundInstance = true;
				assertEquals(MemoryEngineContract.SOURCE_INSTANCE_FIELD, page.sourceKinds[index]);
				assertEquals(FixtureRoot.class.getName(), page.sourceClasses[index]);
				assertEquals("int", page.sourceTypes[index]);
			}
		}
		assertTrue(foundStatic);
		assertTrue(foundInstance);
	}

	@Test
	public void inspectorVerifiesSharedReferencePathsAndDetectsReplacement() {
		int[] originalStatic = FixtureRoot.staticArray;
		int[] original = root.array;
		try {
			FixtureRoot.staticArray = new int[]{234567};
			root.array = new int[]{234567};
			ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
					MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
					234567L, 0L, 0L);
			assertEquals(MemoryEngineContract.RESULT_OK, search.code);
			long staticId = idForAddress(search.revision, "FixtureRoot.staticArray");
			long instanceId = idForAddress(search.revision, "FixtureRoot.array [instance]");
			ManagedJavaMemoryEngine.ManagedInspection staticPath =
					engine.inspect(TOKEN, search.revision, staticId, 1);
			assertEquals(MemoryEngineContract.PATH_VERIFIED, staticPath.path.status);
			assertTrue(staticPath.path.path.contains("FixtureRoot.staticArray[0]"));
			ManagedJavaMemoryEngine.ManagedInspection instancePath =
					engine.inspect(TOKEN, search.revision, instanceId, 1);
			assertEquals(MemoryEngineContract.PATH_VERIFIED, instancePath.path.status);
			assertTrue(instancePath.path.path.contains("MIDlet"));
			assertTrue(instancePath.path.path.contains("FixtureRoot.array[0]"));

			int[] priorSelectedArray = root.array;
			root.array = new int[]{234567};
			assertEquals(MemoryEngineContract.PATH_CHANGED,
					engine.inspect(TOKEN, search.revision, instanceId, 1).path.status);
			assertEquals(234567, priorSelectedArray[0]);
		} finally {
			FixtureRoot.staticArray = originalStatic;
			root.array = original;
		}
	}

	@Test
	public void objectArrayIndexesHaveVerifiedPathsButExactContainersDoNot() {
		int[] nested = new int[]{876543};
		root.cycle[1] = nested;
		root.vector.add(new int[]{876543});
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				876543L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
		assertEquals(2, page.ids.length);
		int verified = 0;
		int untraced = 0;
		for (long id : page.ids) {
			ManagedJavaMemoryEngine.ManagedInspection inspected = engine.inspect(TOKEN,
					search.revision, id, 1);
			if (inspected.path.status == MemoryEngineContract.PATH_VERIFIED) {
				verified++;
				assertTrue(inspected.path.path.contains("[1][0]"));
			} else {
				assertEquals(MemoryEngineContract.PATH_UNKNOWN, inspected.path.status);
				untraced++;
			}
		}
		assertEquals(1, verified);
		assertEquals(1, untraced);
		root.cycle[1] = new int[]{876543};
		for (long id : page.ids) {
			ManagedJavaMemoryEngine.ManagedInspection inspected = engine.inspect(TOKEN,
					search.revision, id, 1);
			if (inspected.path.status == MemoryEngineContract.PATH_CHANGED) {
				assertTrue(inspected.path.path.isEmpty());
				return;
			}
		}
		throw new AssertionError("Replaced Object[] element did not invalidate its old path");
	}

	@Test
	public void rootedAliasUpgradesUntracedPendingArrayWithoutRebinding() {
		int[] shared = new int[]{486257};
		Vector<Object> container = new Vector<>();
		container.add(shared);
		MemoryDiscoveryBridge.setMidletRoot(TOKEN,
				new AliasRoot(container, new AliasCarrier(shared)));

		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				486257L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		assertEquals(1L, search.resultCount);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
		ManagedJavaMemoryEngine.ManagedInspection inspection =
				engine.inspect(TOKEN, search.revision, page.ids[0], 1);
		assertEquals(MemoryEngineContract.PATH_VERIFIED, inspection.path.status);
		assertTrue(inspection.path.path.contains("AliasRoot.contents[1]"));
		assertTrue(inspection.path.path.contains("AliasCarrier.values[0]"));
	}

	@Test
	public void unsupportedContainerRouteDoesNotPretendToBeAChangedRoot() {
		root.vector.add(new DetachedLink(808081));
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				808081L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
		assertEquals(1, page.ids.length);
		ManagedJavaMemoryEngine.ManagedInspection inspected = engine.inspect(TOKEN,
				search.revision, page.ids[0], 1);
		assertEquals(MemoryEngineContract.RESULT_OK, inspected.code);
		assertEquals(MemoryEngineContract.PATH_UNKNOWN, inspected.path.status);
		assertTrue(inspected.path.path.isEmpty());
	}

	@Test
	public void sharedProvenanceDoesNotDuplicateForArrayElements() {
		root.array = new int[]{707707, 707707, 707707};
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				707707L, 0L, 0L);
		assertEquals(3, search.resultCount);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
		for (long id : page.ids) {
			assertEquals(MemoryEngineContract.PATH_VERIFIED,
					engine.inspect(TOKEN, search.revision, id, 1).path.status);
		}
	}

	@Test
	public void arrayLabelsShowOnlyVerifiedDirectReferencePaths() {
		int[] originalStatic = FixtureRoot.staticArray;
		try {
			FixtureRoot.staticArray = new int[]{123457};
			root.array = new int[]{123457};
			ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
					MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
					123457L, 0L, 0L);
			assertEquals(MemoryEngineContract.RESULT_OK, search.code);
			ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
			assertEquals(Arrays.toString(page.addresses), 2, page.ids.length);
			assertTrue(Arrays.toString(page.addresses),
					hasAddress(page, "FixtureRoot.staticArray [static]"));
			assertTrue(Arrays.toString(page.addresses),
					hasAddress(page, "FixtureRoot.array [instance]"));
			int staticIndex = -1;
			int instanceIndex = -1;
			for (int index = 0; index < page.ids.length; index++) {
				if (page.addresses[index].contains("FixtureRoot.staticArray")) staticIndex = index;
				if (page.addresses[index].contains("FixtureRoot.array [instance]")) instanceIndex = index;
			}
			assertTrue(staticIndex >= 0 && instanceIndex >= 0);
			assertEquals(MemoryEngineContract.SOURCE_STATIC_FIELD, page.sourceKinds[staticIndex]);
			assertEquals(MemoryEngineContract.SOURCE_INSTANCE_FIELD, page.sourceKinds[instanceIndex]);
			assertEquals(FixtureRoot.class.getName(), page.sourceClasses[staticIndex]);
			assertEquals(FixtureRoot.class.getName(), page.sourceClasses[instanceIndex]);
			assertEquals("staticArray", page.sourceFields[staticIndex]);
			assertEquals("array", page.sourceFields[instanceIndex]);
			assertEquals("[I", page.sourceTypes[staticIndex]);
			assertEquals("[I", page.sourceTypes[instanceIndex]);

			assertEquals(MemoryEngineContract.RESULT_OK,
					engine.addWatch(TOKEN, search.revision, new long[]{page.ids[staticIndex]}, 0L).code);
			ManagedJavaMemoryEngine.ManagedPage watched = engine.watchPage(TOKEN);
			assertEquals(1, watched.ids.length);
			assertEquals(page.sourceKinds[staticIndex], watched.sourceKinds[0]);
			assertEquals(page.sourceClasses[staticIndex], watched.sourceClasses[0]);
			assertEquals(page.sourceFields[staticIndex], watched.sourceFields[0]);
			ManagedJavaMemoryEngine.ManagedInspection inspected = engine.inspect(TOKEN,
					search.revision, page.ids[staticIndex], 1);
			assertEquals(MemoryEngineContract.RESULT_OK, inspected.code);
			assertEquals(page.sourceFields[staticIndex], inspected.source.fieldName);
			assertEquals(page.sourceTypes[staticIndex], inspected.source.fieldType);

			root.array = new int[]{123457};
			ManagedJavaMemoryEngine.ManagedPage afterReferenceChange = engine.resultPage(TOKEN,
					search.revision, 0, 100);
			assertFalse(hasAddress(afterReferenceChange, "FixtureRoot.array [instance]"));
			for (int index = 0; index < afterReferenceChange.ids.length; index++) {
				if (afterReferenceChange.ids[index] == page.ids[instanceIndex]) {
					assertEquals(MemoryEngineContract.SOURCE_UNKNOWN,
							afterReferenceChange.sourceKinds[index]);
				}
			}
		} finally {
			FixtureRoot.staticArray = originalStatic;
		}
	}

	@Test
	public void arrayReachedThroughObjectArrayDoesNotInventFieldOwnership() {
		root.cycle[1] = new int[]{123456};
		ManagedJavaMemoryEngine.ManagedOperationResult search = engine.startExact(TOKEN,
				MemoryEngineContract.TYPE_INT, MemoryEngineContract.PREDICATE_EQUAL,
				123456L, 0L, 0L);
		assertEquals(MemoryEngineContract.RESULT_OK, search.code);
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, search.revision, 0, 100);
		assertEquals(1, page.ids.length);
		assertTrue(page.addresses[0].startsWith("int[]#"));
		assertFalse(page.addresses[0].contains(" → "));
		assertEquals(MemoryEngineContract.SOURCE_UNKNOWN, page.sourceKinds[0]);
		assertEquals("", page.sourceClasses[0]);
		assertEquals("", page.sourceFields[0]);
	}

	private ManagedJavaMemoryEngine.ManagedOperationResult startExact() {
		return engine.startExact(TOKEN, MemoryEngineContract.TYPE_INT,
				MemoryEngineContract.PREDICATE_EQUAL, 7L, 0L, 0L);
	}

	private long idForAddress(long revision, String fragment) {
		ManagedJavaMemoryEngine.ManagedPage page = engine.resultPage(TOKEN, revision, 0,
				MemoryEngineContract.MAX_RESULT_PAGE_SIZE);
		for (int index = 0; index < page.ids.length; index++) {
			if (page.addresses[index].contains(fragment)) return page.ids[index];
		}
		throw new AssertionError("Missing managed row containing " + fragment);
	}

	private static String valueForAddress(ManagedJavaMemoryEngine.ManagedPage page, String fragment) {
		for (int index = 0; index < page.addresses.length; index++) {
			if (page.addresses[index].contains(fragment)) return page.values[index];
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
		static int staticGroupFirst = 303;
		static int staticGroupSecond = 404;
		static int[] staticArray = {7, 3, 7};
		static FixtureChild staticChild = new FixtureChild("static", 7);

		int rootMatch = 7;
		int groupFirst = 101;
		int groupSecond = 202;
		int rootChanged = 7;
		byte byteMatch = 7;
		short shortMatch = 7;
		char charMatch = 0xffff;
		long longMatch = 9_007_199_254_740_993L;
		float floatMatch = 1.5f;
		double doubleMatch = 1.5d;
		final int finalMatch = 7;
		FixtureChild child = new FixtureChild("direct", 7);
		SplitFields splitFirst = new SplitFields(505, 0);
		SplitFields splitSecond = new SplitFields(0, 606);
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

	private static final class SplitFields {
		private int first;
		private int second;

		SplitFields(int first, int second) {
			this.first = first;
			this.second = second;
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

	private static final class DetachedDisplayRoot {
		private int escapedMatch = 424_242;
	}

	private static final class AliasRoot {
		final Object[] contents;

		AliasRoot(Object first, Object second) {
			contents = new Object[]{first, second};
		}
	}

	private static final class AliasCarrier {
		final int[] values;

		AliasCarrier(int[] values) {
			this.values = values;
		}
	}

	private static final class DetachedLink {
		final int[] values;

		DetachedLink(int value) {
			values = new int[]{value};
		}
	}

	private static final class CapacityRoot {
		int[] values;

		CapacityRoot(int count) {
			values = new int[count];
			Arrays.fill(values, 7);
		}
	}
}
