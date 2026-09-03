/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.playsoftware.j2meloader.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MemoryGcPolicyTest {
	@Test
	public void knownSearchPublishesAcrossGcButKeepsStaleEpoch() {
		assertFalse(MemoryGcPolicy.firstSearchRequiresStableEpoch(false, 10L, 11L));
		assertEquals(10L, MemoryGcPolicy.publishedSearchEpoch(false, 10L, 11L));
	}

	@Test
	public void unknownBaselineStillRequiresStableEpoch() {
		assertTrue(MemoryGcPolicy.firstSearchRequiresStableEpoch(true, 10L, 11L));
		assertEquals(11L, MemoryGcPolicy.publishedSearchEpoch(true, 10L, 11L));
	}

	@Test
	public void stableSearchPublishesLatestKnownEpoch() {
		assertFalse(MemoryGcPolicy.firstSearchRequiresStableEpoch(false, 7L, 7L));
		assertEquals(7L, MemoryGcPolicy.publishedSearchEpoch(false, 7L, 7L));
	}

	@Test
	public void successfulIdentityAwareRefreshDoesNotNeedFreshRangesForOldGcAlone() {
		assertFalse(MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				MemoryEngineContract.RESULT_OK,
				MemoryEngineContract.RESULT_OK,
				false));
	}

	@Test
	public void identityOrRangeFailureRetriesEvenWithoutGcStatistics() {
		assertTrue(MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				MemoryEngineContract.RESULT_OK,
				false));
		assertTrue(MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				MemoryEngineContract.RESULT_TARGET_LOST,
				MemoryEngineContract.RESULT_OK,
				false));
		assertTrue(MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				MemoryEngineContract.RESULT_OK,
				MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
				false));
	}

	@Test
	public void gcMovingDuringFastVerificationForcesFreshRangeRetry() {
		assertTrue(MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				MemoryEngineContract.RESULT_OK,
				MemoryEngineContract.RESULT_OK,
				true));
	}

	@Test
	public void unrelatedErrorsDoNotTriggerExpensiveRangeRetry() {
		assertFalse(MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				MemoryEngineContract.RESULT_INVALID_REQUEST,
				MemoryEngineContract.RESULT_OK,
				false));
		assertFalse(MemoryGcPolicy.shouldRetryReadWithFreshRanges(
				MemoryEngineContract.RESULT_RESOURCE_LIMIT,
				MemoryEngineContract.RESULT_OK,
				false));
	}
}
