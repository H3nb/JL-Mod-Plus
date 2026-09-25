/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class InstalledAppWriteGuardTest {
	@Test
	public void validatesIdentityInsidePermitBeforeWrite() {
		List<String> events = new ArrayList<>();
		InstalledAppWriteGuard guard = new InstalledAppWriteGuard(
				() -> {
					events.add("acquire");
					return () -> events.add("release");
				},
				path -> {
					events.add("resolve:" + path);
					return 17L;
				});

		InstalledAppWriteGuard.Result result = guard.run("/work/converted/Bounce", 17L, () -> {
			events.add("write");
			return true;
		});

		assertEquals(InstalledAppWriteGuard.Result.SUCCESS, result);
		assertEquals(Arrays.asList(
				"acquire", "resolve:/work/converted/Bounce", "write", "release"), events);
	}

	@Test
	public void replacementIdentityIsRejectedWithoutWrite() {
		AtomicBoolean permitHeld = new AtomicBoolean();
		AtomicBoolean wrote = new AtomicBoolean();
		InstalledAppWriteGuard guard = new InstalledAppWriteGuard(
				() -> {
					permitHeld.set(true);
					return () -> permitHeld.set(false);
				},
				path -> {
					assertTrue(permitHeld.get());
					return 42L;
				});

		InstalledAppWriteGuard.Result result = guard.run("path", 17L, () -> {
			wrote.set(true);
			return true;
		});

		assertEquals(InstalledAppWriteGuard.Result.STALE, result);
		assertFalse(wrote.get());
		assertFalse(permitHeld.get());
	}

	@Test
	public void operationFailureIsNotReportedAsStale() {
		InstalledAppWriteGuard guard = new InstalledAppWriteGuard(
				() -> () -> { }, path -> 17L);

		assertEquals(
				InstalledAppWriteGuard.Result.FAILED,
				guard.run("path", 17L, () -> false));
		assertEquals(
				InstalledAppWriteGuard.Result.FAILED,
				guard.run("path", 17L, () -> { throw new IllegalStateException("failed"); }));
	}

	@Test
	public void initialIdentityResolutionAlsoRunsInsidePermit() {
		AtomicBoolean permitHeld = new AtomicBoolean();
		InstalledAppWriteGuard guard = new InstalledAppWriteGuard(
				() -> {
					permitHeld.set(true);
					return () -> permitHeld.set(false);
				},
				path -> {
					assertTrue(permitHeld.get());
					return 17L;
				});

		assertEquals(17L, guard.resolveCurrentAppId("path"));
		assertFalse(permitHeld.get());
	}
}
