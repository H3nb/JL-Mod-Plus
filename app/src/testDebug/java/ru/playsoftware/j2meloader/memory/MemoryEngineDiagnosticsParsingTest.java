/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MemoryEngineDiagnosticsParsingTest {
	@Test
	public void parsesVmRssKilobytesWithoutDependingOnSpacing() {
		assertEquals(12345L, MemoryEngineDiagnosticsService.parseVmRssKb("VmRSS:\t 12345 kB"));
		assertEquals(7L, MemoryEngineDiagnosticsService.parseVmRssKb("VmRSS: 7 kB"));
	}

	@Test
	public void rejectsMalformedVmRssLines() {
		assertEquals(-1L, MemoryEngineDiagnosticsService.parseVmRssKb(null));
		assertEquals(-1L, MemoryEngineDiagnosticsService.parseVmRssKb("VmSize: 100 kB"));
		assertEquals(-1L, MemoryEngineDiagnosticsService.parseVmRssKb("VmRSS: nope kB"));
		assertEquals(-1L, MemoryEngineDiagnosticsService.parseVmRssKb("VmRSS: -1 kB"));
	}
}
