/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class ProcessStateSummaryTest {
	@Test
	public void v3RoundTripRetainsReleaseSdkAndReproductionContextWithinPlatformLimit() {
		byte[] state = ProcessStateSummary.build(
				"rabc123-9z",
				"5d609c102358286669b81468f1e0b61960703fa9",
				36,
				"16",
				null,
				"config.graphics",
				"open",
				"entering");

		assertTrue(state.length <= ProcessStateSummary.MAX_BYTES);
		ProcessStateSummary.Data parsed = ProcessStateSummary.parse(state);
		assertEquals("rabc123-9z", parsed.runId);
		assertEquals("5d609c10", parsed.buildCommit);
		assertEquals(36, parsed.sdk);
		assertEquals("16", parsed.androidRelease);
		assertEquals("config.graphics", parsed.location);
	}

	@Test
	public void v3PreservesMidletSessionBeforeOptionalContext() {
		String sessionId = "123e4567-e89b-12d3-a456-426614174000";
		byte[] state = ProcessStateSummary.build(
				"rabc123-9z", "deadbeef", 36, "16", sessionId,
				"activity.microactivity", "open", "active");

		assertTrue(state.length <= ProcessStateSummary.MAX_BYTES);
		assertEquals(sessionId, ProcessStateSummary.parse(state).sessionId);
	}

	@Test
	public void oversizedOptionalContextCannotEvictCorrelationIdentity() {
		String sessionId = "123e4567-e89b-12d3-a456-426614174000";
		byte[] state = ProcessStateSummary.build(
				"rabc123-9z", "deadbeef", 36, "16", sessionId,
				"x".repeat(120), "y".repeat(120), "executing");

		ProcessStateSummary.Data parsed = ProcessStateSummary.parse(state);
		assertTrue(state.length <= ProcessStateSummary.MAX_BYTES);
		assertEquals("rabc123-9z", parsed.runId);
		assertEquals(36, parsed.sdk);
		assertEquals("16", parsed.androidRelease);
		assertEquals(sessionId, parsed.sessionId);
	}

	@Test
	public void unknownBuildIdentityDoesNotConsumeStateBudget() {
		byte[] state = ProcessStateSummary.build(
				"rabc123-9z", "unknown", 36, "16", null,
				"config.graphics", "open", "entering");
		String encoded = new String(state, StandardCharsets.US_ASCII);

		assertFalse(encoded.contains("|b=unknown"));
		assertNull(ProcessStateSummary.parse(state).buildCommit);
	}

	@Test
	public void legacyV1RemainsReadable() {
		byte[] legacy = ("jlp1|r=main|vc=7|sdk=35|s="
				+ "123e4567-e89b-12d3-a456-426614174000").getBytes(StandardCharsets.US_ASCII);
		ProcessStateSummary.Data parsed = ProcessStateSummary.parse(legacy);

		assertEquals(7, parsed.versionCode);
		assertEquals(35, parsed.sdk);
		assertNull(parsed.androidRelease);
		assertEquals("123e4567-e89b-12d3-a456-426614174000", parsed.sessionId);
		assertNull(parsed.runId);
	}

	@Test
	public void invalidOrOversizedStateFailsClosedToEmptyContext() {
		assertNull(ProcessStateSummary.parse(
				"other|u=unsafe".getBytes(StandardCharsets.US_ASCII)).runId);
		byte[] oversized = new byte[ProcessStateSummary.MAX_BYTES + 1];
		assertNull(ProcessStateSummary.parse(oversized).runId);
	}
}
