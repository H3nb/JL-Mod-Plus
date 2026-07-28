/*
 * Copyright 2026 H3NB
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

package javax.microedition.shell.memory;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MemoryEditorRuntimeTest {
	private static final long SITE = 17L;

	@After
	public void tearDown() {
		MemoryEditorRuntime.clear();
	}

	@Test
	public void unknownSearchCanRefineEditFreezeAndUndoField() {
		Fixture fixture = new Fixture();
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", SITE, -1, 7);
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);

		MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.EXACT, "7", null);
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
		assertEquals(1, MemoryEditorRuntime.editAll("42"));
		assertEquals(42, fixture.value);
		assertTrue(MemoryEditorRuntime.undo());
		assertEquals(7, fixture.value);
		assertEquals(1, MemoryEditorRuntime.freezeAll("99"));
		assertEquals(99, MemoryEditorBridge.onWriteInt(fixture, Fixture.class,
				"valueI", SITE, -1, 11));
		assertEquals(99, fixture.value);
	}

	@Test
	public void arrayIndexIsPartOfCandidateIdentity() {
		int[] values = {3, 4};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 0, values[0]);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 1, values[1]);
		assertEquals(2, MemoryEditorRuntime.snapshot().candidates);
		MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.EXACT, "4", null);
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
		assertEquals(1, MemoryEditorRuntime.editAll("8"));
		assertEquals(8, values[1]);
		assertEquals(3, values[0]);
	}

	@Test
	public void sameFieldObservedFromDifferentSitesIsOneLogicalCandidate() {
		Fixture fixture = new Fixture();
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);

		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", 10L, -1, 7);
		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", 20L, -1, 7);

		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
	}

	@Test
	public void changedRefineReadsStorageInsteadOfOnlyTheLastHook() {
		Fixture fixture = new Fixture();
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", SITE, -1, 7);

		fixture.value = 8;
		MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.CHANGED, null, null);

		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
		MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.UNCHANGED, null, null);
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
	}

	@Test
	public void undoRestoresTheWholeEditBatch() {
		int[] values = {3, 4};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 0, values[0]);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 1, values[1]);

		assertEquals(2, MemoryEditorRuntime.editAll("9"));
		assertEquals(9, values[0]);
		assertEquals(9, values[1]);
		assertTrue(MemoryEditorRuntime.undo());
		assertEquals(3, values[0]);
		assertEquals(4, values[1]);
		assertFalse(MemoryEditorRuntime.undo());
	}

	@Test
	public void undoKeepsOnlyTheMostRecentEdit() {
		Fixture fixture = new Fixture();
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", SITE, -1, 7);

		assertEquals(1, MemoryEditorRuntime.editAll("8"));
		assertEquals(1, MemoryEditorRuntime.editAll("9"));
		assertTrue(MemoryEditorRuntime.undo());
		assertEquals(8, fixture.value);
		assertFalse(MemoryEditorRuntime.undo());
	}

	@Test
	public void gameGenerationSearchSessionAndOperationIdsHaveSeparateLifecycles() {
		long generation = MemoryEditorRuntime.beginGame();
		long firstSession = MemoryEditorRuntime.begin(
				MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		Fixture fixture = new Fixture();
		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", SITE, -1, 7);

		MemoryEditorRuntime.OperationResult edit =
				MemoryEditorRuntime.editCandidates(firstSession, null, "8");
		assertEquals(MemoryEditorRuntime.OperationStatus.SUCCESS, edit.status);
		assertEquals(firstSession, edit.searchSessionId);
		assertTrue(edit.operationId > 0);

		MemoryEditorRuntime.resetSearch();
		assertEquals(generation, MemoryEditorRuntime.snapshot().gameGeneration);
		assertEquals(0, MemoryEditorRuntime.snapshot().searchSessionId);

		long secondSession = MemoryEditorRuntime.begin(
				MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		assertTrue(secondSession > firstSession);
		MemoryEditorRuntime.endGame();
		assertTrue(MemoryEditorRuntime.snapshot().gameGeneration > generation);
		assertEquals(0, MemoryEditorRuntime.snapshot().searchSessionId);
	}

	@Test
	public void oldSessionOperationsAreRejectedWithoutChangingTheNewSearch() {
		long oldSession = MemoryEditorRuntime.begin(
				MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		long currentSession = MemoryEditorRuntime.begin(
				MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		Fixture fixture = new Fixture();
		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", SITE, -1, 7);

		MemoryEditorRuntime.OperationResult stale =
				MemoryEditorRuntime.editCandidates(oldSession, null, "99");

		assertEquals(MemoryEditorRuntime.OperationStatus.STALE_SESSION, stale.status);
		assertEquals(7, fixture.value);
		assertEquals(currentSession, MemoryEditorRuntime.snapshot().searchSessionId);
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
	}

	@Test
	public void snapshotReportsBoundedCandidateMemoryAndStructuredErrors() {
		int[] values = {1, 2, 3};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		for (int index = 0; index < values.length; index++) {
			MemoryEditorBridge.onReadInt(values, null, "#array", SITE, index, values[index]);
		}

		MemoryEditorRuntime.Snapshot snapshot = MemoryEditorRuntime.snapshot();
		assertTrue(snapshot.candidateBytes > 0);
		assertTrue(snapshot.candidateBytes <= snapshot.candidateByteBudget);

		try {
			MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.EXACT, "", null);
			fail("Expected a structured invalid-value error");
		} catch (MemoryEditorRuntime.MemoryEditorException error) {
			assertEquals(MemoryEditorRuntime.ErrorCode.INVALID_VALUE, error.code);
		}
	}

	@Test
	public void candidateByteBudgetStopsCollectionWithPartialResults() {
		int[] values = new int[45_000];
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		for (int index = 0; index < values.length; index++) {
			MemoryEditorBridge.onReadInt(values, null, "#array", SITE, index, index);
		}

		MemoryEditorRuntime.Snapshot snapshot = MemoryEditorRuntime.snapshot();
		assertTrue(snapshot.limitReached);
		assertFalse(snapshot.collecting);
		assertTrue(snapshot.candidates < values.length);
		assertTrue(snapshot.candidateBytes <= snapshot.candidateByteBudget);
		assertFalse(MemoryEditorBridge.isKindActive(1));
	}

	@Test
	public void byteAndBooleanArraysCanBeEditedThroughIntSearch() {
		byte[] bytes = {1};
		boolean[] booleans = {false};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(bytes, null, "#array", SITE, 0, 1);
		MemoryEditorBridge.onReadInt(booleans, null, "#array", SITE, 0, 0);

		assertEquals(2, MemoryEditorRuntime.editAll("1"));
		assertEquals(1, bytes[0]);
		assertTrue(booleans[0]);
	}

	@Test
	public void exactAndRangeCriteriaAreValidatedBeforeCandidatesAreDiscarded() {
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		assertInvalid(() -> MemoryEditorRuntime.refine(
				MemoryEditorRuntime.SearchMode.EXACT, "", null));
		assertInvalid(() -> MemoryEditorRuntime.refine(
				MemoryEditorRuntime.SearchMode.RANGE, "10", "2"));
		assertInvalid(() -> MemoryEditorRuntime.begin(
				MemoryEditorRuntime.ValueKind.FLOAT,
				MemoryEditorRuntime.SearchMode.RANGE, "NaN", "10"));
	}

	@Test
	public void refineDoesNotAdmitNewLocationsIntoTheExistingResultSet() {
		Fixture first = new Fixture();
		Fixture second = new Fixture();
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(first, Fixture.class, "valueI", SITE, -1, 7);
		MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.UNCHANGED, null, null);

		MemoryEditorBridge.onReadInt(second, Fixture.class, "valueI", SITE, -1, 7);

		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
	}

	@Test
	public void staticFieldUsesASeparateLogicalIdentityAndCanBeEdited() {
		StaticFixture.value = 12L;
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.LONG,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadLong(null, StaticFixture.class, "valueJ", SITE, -2,
				StaticFixture.value);

		assertEquals(1, MemoryEditorRuntime.editAll("44"));
		assertEquals(44L, StaticFixture.value);
	}

	@Test
	public void resultsExposeStorageAndAllowEditingOnlySelectedCandidates() {
		int[] values = {3, 4};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 0, values[0]);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 1, values[1]);
		MemoryEditorRuntime.finishCollection();

		List<MemoryEditorRuntime.CandidateView> results =
				MemoryEditorRuntime.results(0, 200);
		assertEquals(2, results.size());
		assertEquals("int", results.get(0).storageType);
		assertEquals("3", results.get(0).value);
		assertEquals("#1 · int[][0]", results.get(0).location);
		assertTrue(results.get(0).editable);

		MemoryEditorRuntime.OperationResult edit = MemoryEditorRuntime.editCandidates(
				new long[]{results.get(1).id}, "9");
		assertEquals(1, edit.requested);
		assertEquals(1, edit.succeeded);
		assertEquals(0, edit.failed);
		assertEquals(3, values[0]);
		assertEquals(9, values[1]);
	}

	@Test
	public void smallIntegerStorageRejectsOverflowInsteadOfSilentlyNarrowing() {
		byte[] bytes = {1};
		char[] chars = {2};
		boolean[] booleans = {false};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(bytes, null, "#array", SITE, 0, bytes[0]);
		MemoryEditorBridge.onReadInt(chars, null, "#array", SITE, 0, chars[0]);
		MemoryEditorBridge.onReadInt(booleans, null, "#array", SITE, 0, 0);

		MemoryEditorRuntime.OperationResult edit =
				MemoryEditorRuntime.editCandidates(null, "256");
		assertEquals(3, edit.requested);
		assertEquals(1, edit.succeeded);
		assertEquals(2, edit.failed);
		assertEquals(1, bytes[0]);
		assertEquals(256, chars[0]);
		assertFalse(booleans[0]);
	}

	@Test
	public void freezeAndClearFreezeReportOnlyCandidatesActuallyChanged() {
		byte[] bytes = {1};
		boolean[] booleans = {false};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(bytes, null, "#array", SITE, 0, bytes[0]);
		MemoryEditorBridge.onReadInt(booleans, null, "#array", SITE, 0, 0);

		MemoryEditorRuntime.OperationResult freeze =
				MemoryEditorRuntime.freezeCandidates(null, "2");
		assertEquals(2, freeze.requested);
		assertEquals(1, freeze.succeeded);
		assertEquals(1, freeze.failed);
		assertEquals(1, MemoryEditorRuntime.snapshot().frozen);

		long frozenId = MemoryEditorRuntime.results(0, 200).stream()
				.filter(result -> result.frozen)
				.findFirst()
				.orElseThrow()
				.id;
		MemoryEditorRuntime.OperationResult clear =
				MemoryEditorRuntime.clearFreeze(new long[]{frozenId});
		assertEquals(1, clear.succeeded);
		assertEquals(0, MemoryEditorRuntime.snapshot().frozen);
	}

	@Test
	public void freezeSavesUnfreezeKeepsAndDeleteRemovesTheCandidate() {
		Fixture fixture = new Fixture();
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(fixture, Fixture.class, "valueI", SITE, -1, 7);

		assertEquals(1, MemoryEditorRuntime.freezeAll("12"));
		MemoryEditorRuntime.CandidateView saved =
				MemoryEditorRuntime.savedResults(0, 200).get(0);
		assertTrue(saved.frozen);
		assertTrue(saved.saved);
		assertEquals(1, MemoryEditorRuntime.snapshot().saved);

		assertEquals(1, MemoryEditorRuntime.clearFreeze(new long[]{saved.id}).succeeded);
		assertFalse(MemoryEditorRuntime.savedResults(0, 200).get(0).frozen);
		assertEquals(1, MemoryEditorRuntime.snapshot().saved);

		assertEquals(1, MemoryEditorRuntime.deleteSaved(new long[]{saved.id}).succeeded);
		assertEquals(0, MemoryEditorRuntime.snapshot().saved);
		assertEquals(0, MemoryEditorRuntime.snapshot().frozen);
		assertTrue(MemoryEditorRuntime.savedResults(0, 200).isEmpty());
	}

	@Test
	public void savedCandidateSurvivesRefineAndUndoFreezeRestoresPreviousState() {
		int[] values = {3, 4};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 0, values[0]);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 1, values[1]);

		long firstId = MemoryEditorRuntime.results(0, 200).get(0).id;
		assertEquals(1,
				MemoryEditorRuntime.freezeCandidates(new long[]{firstId}, "9").succeeded);
		assertTrue(MemoryEditorRuntime.undo());
		assertEquals(3, values[0]);
		assertEquals(0, MemoryEditorRuntime.snapshot().saved);
		assertEquals(0, MemoryEditorRuntime.snapshot().frozen);

		assertEquals(1,
				MemoryEditorRuntime.freezeCandidates(new long[]{firstId}, "9").succeeded);
		MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.EXACT, "4", null);
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
		assertEquals(1, MemoryEditorRuntime.snapshot().saved);
		assertEquals(1, MemoryEditorRuntime.snapshot().frozen);
		assertEquals(firstId, MemoryEditorRuntime.savedResults(0, 200).get(0).id);
	}

	@Test
	public void snapshotDiagnosesObservedTypesAndAccessPaths() {
		int[] values = {3};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.LONG,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(values, null, "#array", SITE, 0, values[0]);
		MemoryEditorBridge.onWriteInt(values, null, "#array", SITE, 0, 4);
		MemoryEditorBridge.onReadLong(null, StaticFixture.class, "valueJ", SITE, -2,
				StaticFixture.value);

		MemoryEditorRuntime.Snapshot snapshot = MemoryEditorRuntime.snapshot();
		assertEquals(0, snapshot.intObservations);
		assertEquals(1, snapshot.longObservations);
		assertEquals(0, snapshot.arrayObservations);
		assertEquals(1, snapshot.fieldObservations);
		assertEquals(1, snapshot.readObservations);
		assertEquals(0, snapshot.writeObservations);
		assertEquals(1, snapshot.candidates);
	}

	@Test
	public void hookGateTracksCollectingResultsFreezeAndSessionLifecycle() {
		StaticFixture.value = 12L;
		assertFalse(MemoryEditorBridge.isKindActive(1));
		assertFalse(MemoryEditorBridge.isKindActive(2));

		long sessionId = MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.LONG,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		assertFalse(MemoryEditorBridge.isKindActive(1));
		assertTrue(MemoryEditorBridge.isKindActive(2));
		MemoryEditorBridge.onReadLong(null, StaticFixture.class, "valueJ", SITE, -2,
				StaticFixture.value);

		assertFalse(MemoryEditorRuntime.finishCollection(sessionId + 1));
		assertTrue(MemoryEditorBridge.isKindActive(2));
		assertTrue(MemoryEditorRuntime.finishCollection(sessionId));
		assertFalse(MemoryEditorBridge.isKindActive(2));

		assertTrue(MemoryEditorRuntime.resumeCollection(sessionId));
		assertTrue(MemoryEditorBridge.isKindActive(2));
		MemoryEditorRuntime.finishCollection();
		assertEquals(1, MemoryEditorRuntime.freezeAll("44"));
		assertTrue(MemoryEditorBridge.isKindActive(2));

		MemoryEditorRuntime.clearFreeze();
		assertFalse(MemoryEditorBridge.isKindActive(2));
		MemoryEditorRuntime.resetSearch();
		assertFalse(MemoryEditorBridge.isKindActive(2));
	}

	@Test
	public void sessionChurnDoesNotRecurseOrDeadlockResultReaders() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread writer = new Thread(() -> {
			await(start, failure);
			for (int iteration = 0; iteration < 2_000 && failure.get() == null; iteration++) {
				try {
					MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
							MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
					MemoryEditorRuntime.resetSearch();
				} catch (Throwable error) {
					failure.compareAndSet(null, error);
				}
			}
		}, "memory-editor-session-writer");
		Thread reader = new Thread(() -> {
			await(start, failure);
			for (int iteration = 0; iteration < 2_000 && failure.get() == null; iteration++) {
				try {
					MemoryEditorRuntime.snapshot();
					MemoryEditorRuntime.results(0, 1);
					MemoryEditorRuntime.savedResults(0, 1);
				} catch (Throwable error) {
					failure.compareAndSet(null, error);
				}
			}
		}, "memory-editor-session-reader");

		writer.start();
		reader.start();
		start.countDown();
		writer.join(10_000);
		reader.join(10_000);

		assertFalse("Session writer deadlocked", writer.isAlive());
		assertFalse("Result reader deadlocked", reader.isAlive());
		if (failure.get() != null) {
			throw new AssertionError("Concurrent session access failed", failure.get());
		}
	}

	@Test
	public void finishingCollectionPreventsNewCandidatesAndChangeInitialModeIsRejected() {
		Fixture first = new Fixture();
		Fixture second = new Fixture();
		assertInvalid(() -> MemoryEditorRuntime.begin(
				MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.DECREASED, null, null));

		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(first, Fixture.class, "valueI", SITE, -1, 7);
		MemoryEditorRuntime.finishCollection();
		MemoryEditorBridge.onReadInt(second, Fixture.class, "valueI", SITE, -1, 7);

		MemoryEditorRuntime.Snapshot snapshot = MemoryEditorRuntime.snapshot();
		assertFalse(snapshot.collecting);
		assertEquals(1, snapshot.candidates);
	}

	@Test
	public void collectionCanResumeWithoutDiscardingExistingCandidates() {
		Fixture first = new Fixture();
		Fixture second = new Fixture();
		long sessionId = MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadInt(first, Fixture.class, "valueI", SITE, -1, 7);
		MemoryEditorRuntime.finishCollection();

		assertTrue(MemoryEditorRuntime.resumeCollection(sessionId));
		assertTrue(MemoryEditorRuntime.snapshot().collecting);
		MemoryEditorBridge.onReadInt(second, Fixture.class, "valueI", SITE, -1, 8);
		MemoryEditorRuntime.finishCollection();

		assertEquals(2, MemoryEditorRuntime.snapshot().candidates);
		assertFalse(MemoryEditorRuntime.resumeCollection(sessionId + 1));
		MemoryEditorRuntime.refine(sessionId,
				MemoryEditorRuntime.SearchMode.CHANGED, null, null);
		assertFalse(MemoryEditorRuntime.resumeCollection(sessionId));
	}

	@Test
	public void comparisonModesUseStrictNumericBoundariesForInitialAndRefineSearches() {
		int[] values = {3, 4, 5};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.LESS_THAN, "5", null);
		for (int index = 0; index < values.length; index++) {
			MemoryEditorBridge.onReadInt(values, null, "#array", SITE, index, values[index]);
		}
		assertEquals(2, MemoryEditorRuntime.snapshot().candidates);

		MemoryEditorRuntime.refine(MemoryEditorRuntime.SearchMode.NOT_EQUAL, "3", null);
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
		assertEquals("4", MemoryEditorRuntime.results(0, 200).get(0).value);

		MemoryEditorRuntime.clear();
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.GREATER_THAN, "4", null);
		for (int index = 0; index < values.length; index++) {
			MemoryEditorBridge.onReadInt(values, null, "#array", SITE, index, values[index]);
		}
		assertEquals(1, MemoryEditorRuntime.snapshot().candidates);
		assertEquals("5", MemoryEditorRuntime.results(0, 200).get(0).value);
	}

	@Test
	public void invalidArrayWriteDoesNotCreateAnUneditableCandidate() {
		int[] values = {3};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.INT,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);

		assertEquals(9, MemoryEditorBridge.onWriteInt(values, null, "#array",
				SITE, 5, 9));
		assertEquals(0, MemoryEditorRuntime.snapshot().candidates);
		assertEquals(1, MemoryEditorRuntime.snapshot().writeObservations);
	}

	@Test
	public void floatAndDoubleCandidatesPreserveTheirNumericTypes() {
		float[] floats = {1.5f};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.FLOAT,
				MemoryEditorRuntime.SearchMode.EXACT, "1.5", null);
		MemoryEditorBridge.onReadFloat(floats, null, "#array", SITE, 0, floats[0]);
		assertEquals(1, MemoryEditorRuntime.editAll("2.75"));
		assertEquals(2.75f, floats[0], 0.0f);

		MemoryEditorRuntime.clear();
		double[] doubles = {3.25d};
		MemoryEditorRuntime.begin(MemoryEditorRuntime.ValueKind.DOUBLE,
				MemoryEditorRuntime.SearchMode.UNKNOWN, null, null);
		MemoryEditorBridge.onReadDouble(doubles, null, "#array", SITE, 0, doubles[0]);
		assertEquals(1, MemoryEditorRuntime.freezeAll("8.5"));
		assertEquals(8.5d, MemoryEditorBridge.onWriteDouble(
				doubles, null, "#array", SITE, 0, 4.0d), 0.0d);
	}

	private static void assertInvalid(Runnable action) {
		try {
			action.run();
			fail("Expected invalid search criteria");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void await(CountDownLatch latch, AtomicReference<Throwable> failure) {
		try {
			latch.await();
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			failure.compareAndSet(null, error);
		}
	}

	private static final class Fixture {
		private int value = 7;
	}

	private static final class StaticFixture {
		private static long value;
	}
}
