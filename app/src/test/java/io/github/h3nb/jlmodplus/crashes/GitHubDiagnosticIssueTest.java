/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.h3nb.jlmodplus.crashes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public class GitHubDiagnosticIssueTest {
	@Test
	public void nativeTitleDoesNotPromoteFirstProjectFrameToCrashLocation() {
		NativeTombstoneSummary.Summary nativeSummary = new NativeTombstoneSummary.Summary();
		nativeSummary.signalName = "SIGSEGV";
		nativeSummary.signalNumber = 11;
		nativeSummary.frames.add(new NativeTombstoneSummary.Frame(
				0, "__memcpy_aarch64_simd", "/apex/com.android.runtime/lib64/bionic/libc.so"));
		nativeSummary.frames.add(new NativeTombstoneSummary.Frame(
				1, "io.github.h3nb.jlmodplus.mmapi.synth.tsf.LibTSF.render", "libtsf.so"));

		IncidentSummary incident = new IncidentSummary(
				IncidentSummary.Category.NATIVE_CRASH,
				IncidentSummary.FailureOrigin.UNKNOWN,
				1234L,
				"Example MIDlet",
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				"1.0",
				"example.Main",
				"abc123",
				"deadbeef · emulatorDebug",
				"16",
				36,
				"POCO F7",
				"arm64-v8a",
				"midlet",
				null,
				null,
				Collections.emptyList(),
				null,
				Collections.emptyList());

		String title = GitHubDiagnosticIssue.title(incident, nativeSummary);
		String description = GitHubDiagnosticIssue.description(incident, nativeSummary);

		assertTrue(title.contains("SIGSEGV"));
		assertFalse(title.contains("LibTSF"));
		assertTrue(description.contains("first JL-Mod Plus frame in the captured backtrace"));
		assertTrue(description.contains("LibTSF.render()"));
	}
}
