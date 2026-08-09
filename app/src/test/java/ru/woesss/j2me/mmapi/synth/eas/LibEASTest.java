/*
 * Copyright 2026 H3NB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package ru.woesss.j2me.mmapi.synth.eas;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LibEASTest {
	@Test
	public void unknownRateFallsBackToCompatibilityRate() {
		assertEquals(LibEAS.SAMPLE_RATE_22050, LibEAS.normalizeSampleRate(null));
		assertEquals(LibEAS.SAMPLE_RATE_22050, LibEAS.normalizeSampleRate("22050"));
		assertEquals(LibEAS.SAMPLE_RATE_22050, LibEAS.normalizeSampleRate("48000"));
		assertEquals(LibEAS.SAMPLE_RATE_44100, LibEAS.normalizeSampleRate("44100"));
	}
}
