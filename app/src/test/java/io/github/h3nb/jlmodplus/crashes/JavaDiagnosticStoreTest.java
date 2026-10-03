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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class JavaDiagnosticStoreTest {
	@Rule
	public final TemporaryFolder temporary = new TemporaryFolder();

	@Test
	public void liveThrowableStructurePreservesPrimaryAndUnderlyingCause() {
		ClassNotFoundException underlying =
				new ClassNotFoundException("Didn't find class \"com.skt.m.AudioSystem\"");
		underlying.setStackTrace(new StackTraceElement[]{
				new StackTraceElement("dalvik.system.BaseDexClassLoader", "findClass",
						"BaseDexClassLoader.java", 259)
		});
		NoClassDefFoundError primary =
				new NoClassDefFoundError("Failed resolution of Lcom/skt/m/AudioSystem;");
		primary.initCause(underlying);
		primary.setStackTrace(new StackTraceElement[]{
				new StackTraceElement("GloftOTSP", "startApp", "SourceFile", 42)
		});

		JavaDiagnosticStore.ThrowableCapture captured =
				JavaDiagnosticStore.captureThrowableChain(primary, primary);

		assertEquals(2, captured.throwables.size());
		assertEquals(0, captured.primaryIndex);
		assertEquals(NoClassDefFoundError.class.getName(),
				captured.throwables.get(captured.primaryIndex).className);
		assertEquals(ClassNotFoundException.class.getName(),
				captured.throwables.get(captured.primaryIndex + 1).className);
	}

	@Test
	public void reflectionWrapperCanRetainGuestConstructorFailureAsPrimary() {
		IllegalStateException primary = new IllegalStateException("constructor failure");
		InvocationTargetException reported = new InvocationTargetException(primary);

		JavaDiagnosticStore.ThrowableCapture captured =
				JavaDiagnosticStore.captureThrowableChain(reported, primary);

		assertEquals(2, captured.throwables.size());
		assertEquals(1, captured.primaryIndex);
		assertEquals(IllegalStateException.class.getName(),
				captured.throwables.get(captured.primaryIndex).className);
	}

	@Test
	public void frameBudgetCannotHideDeeperPrimaryThrowable() {
		RuntimeException primary = new RuntimeException("primary");
		Throwable current = primary;
		for (int i = 0; i < 4; i++) {
			RuntimeException wrapper = new RuntimeException("wrapper-" + i, current);
			StackTraceElement[] frames =
					new StackTraceElement[JavaDiagnosticStore.MAX_FRAMES_PER_THROWABLE];
			for (int frame = 0; frame < frames.length; frame++) {
				frames[frame] = new StackTraceElement(
						"wrapper." + i, "frame" + frame, "Wrapper.java", frame);
			}
			wrapper.setStackTrace(frames);
			current = wrapper;
		}

		JavaDiagnosticStore.ThrowableCapture captured =
				JavaDiagnosticStore.captureThrowableChain(current, primary);

		assertTrue(captured.primaryIndex >= 0);
		assertEquals("primary", captured.throwables.get(captured.primaryIndex).message);
	}

	@Test
	public void structuredEvidenceRoundTripKeepsReleaseSdkAndPrimaryIndex() throws Exception {
		JavaDiagnosticStore.ThrowableData primary = new JavaDiagnosticStore.ThrowableData(
				NoClassDefFoundError.class.getName(),
				"Failed resolution",
				List.of(new JavaDiagnosticStore.FrameData(
						"game.Main", "startApp", "Main.java", 42, false)));
		JavaDiagnosticStore.ThrowableData underlying = new JavaDiagnosticStore.ThrowableData(
				ClassNotFoundException.class.getName(),
				"Missing class",
				List.of(new JavaDiagnosticStore.FrameData(
						"dalvik.system.BaseDexClassLoader", "findClass",
						"BaseDexClassLoader.java", 259, false)));
		File file = new File(temporary.getRoot(), "roundtrip.java.properties");
		JavaDiagnosticStore.Snapshot original = new JavaDiagnosticStore.Snapshot(
				file,
				JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE,
				1234L,
				"io.github.h3nb.jlmodplus:midlet",
				"midlet",
				123,
				"MidletMain",
				17,
				5,
				"123e4567-e89b-12d3-a456-426614174000",
				"Game",
				"1.0",
				"game.Main",
				"abc123",
				"1.2.3",
				"16",
				36,
				"POCO",
				"F7",
				"arm64-v8a",
				null,
				"stack",
				List.of(primary, underlying),
				0,
				"223e4567-e89b-12d3-a456-426614174000",
				"LIFECYCLE_START",
				null);

		JavaDiagnosticStore.write(file, original);
		JavaDiagnosticStore.Snapshot restored = JavaDiagnosticStore.read(file);

		assertEquals(JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE, restored.kind);
		assertEquals("123e4567-e89b-12d3-a456-426614174000", restored.sessionId);
		assertEquals("223e4567-e89b-12d3-a456-426614174000", restored.legacyEventId);
		assertEquals("LIFECYCLE_START", restored.legacyBoundary);
		assertEquals("16", restored.androidRelease);
		assertEquals(36, restored.androidSdk);
		assertEquals(NoClassDefFoundError.class.getName(), restored.primaryThrowable().className);
		assertEquals(ClassNotFoundException.class.getName(), restored.underlyingCause().className);
		assertEquals("game.Main.startApp(Main.java:42)",
				restored.primaryThrowable().firstFrame().display());
	}

	@Test
	public void fatalCaptureFailureStillDelegatesToUpstreamHandler() {
		AtomicInteger captures = new AtomicInteger();
		AtomicInteger upstreamCalls = new AtomicInteger();
		AtomicInteger fallbacks = new AtomicInteger();
		Thread.UncaughtExceptionHandler upstream =
				(thread, error) -> upstreamCalls.incrementAndGet();
		JavaDiagnosticStore.FatalHandler handler = new JavaDiagnosticStore.FatalHandler(
				(thread, error) -> {
					captures.incrementAndGet();
					throw new OutOfMemoryError("synthetic reporter failure");
				},
				upstream,
				fallbacks::incrementAndGet);

		RuntimeException original = new RuntimeException("original");
		handler.uncaughtException(Thread.currentThread(), original);

		assertEquals(1, captures.get());
		assertEquals(1, upstreamCalls.get());
		assertEquals(0, fallbacks.get());
	}

	@Test
	public void recursiveReporterEntryCannotRecaptureIndefinitely() {
		AtomicInteger captures = new AtomicInteger();
		AtomicInteger upstreamCalls = new AtomicInteger();
		JavaDiagnosticStore.FatalHandler[] holder = new JavaDiagnosticStore.FatalHandler[1];
		holder[0] = new JavaDiagnosticStore.FatalHandler(
				(thread, error) -> {
					captures.incrementAndGet();
					holder[0].uncaughtException(thread, new RuntimeException("nested"));
				},
				(thread, error) -> upstreamCalls.incrementAndGet(),
				null);

		holder[0].uncaughtException(Thread.currentThread(), new RuntimeException("outer"));

		assertEquals(1, captures.get());
		assertEquals(2, upstreamCalls.get());
	}

	@Test
	public void terminalAndCaughtEvidenceKindsRemainDistinct() {
		assertTrue(JavaDiagnosticStore.Kind.FATAL_UNCAUGHT.fatal);
		assertTrue(JavaDiagnosticStore.Kind.MIDLET_SESSION_FAILURE.fatal);
		assertFalse(JavaDiagnosticStore.Kind.CAUGHT_INSTALLER.fatal);
		assertFalse(JavaDiagnosticStore.Kind.CAUGHT_APP_REPOSITORY.fatal);
	}

	@Test
	public void storedRecoverableFailuresCannotBecomeReportsOrCrowdOutFatalEvidence() throws Exception {
		for (JavaDiagnosticStore.Kind kind : JavaDiagnosticStore.Kind.values()) {
			File file = new File(temporary.getRoot(), kind.name() + ".java.properties");
			JavaDiagnosticStore.write(file, minimalSnapshot(file, kind));
		}
		// ACRA predates typed caught-report kinds. Its exact installer wrapper still proves that
		// this was a recoverable operation failure, including already-migrated legacy records.
		for (String host : new String[]{"io.github.h3nb.jlmodplus", "ru.playsoftware.j2meloader"}) {
			File file = new File(temporary.getRoot(), host + "-installer.java.properties");
			JavaDiagnosticStore.write(file, minimalSnapshot(file, JavaDiagnosticStore.Kind.LEGACY_ACRA,
					new JavaDiagnosticStore.ThrowableData(
							host + ".crashes.CrashReporter$InstallerFailureException",
							"Installer failure", List.of())));
		}
		List<JavaDiagnosticStore.Snapshot> visible = JavaDiagnosticStore.loadStored(temporary.getRoot());
		assertEquals(4, visible.size());
		for (JavaDiagnosticStore.Snapshot snapshot : visible) assertTrue(snapshot.kind.fatal);
		// Existing exports remain user-owned; admission changes do not remove their source files.
		assertTrue(new File(temporary.getRoot(), "CAUGHT_INSTALLER.java.properties").isFile());
		assertTrue(new File(temporary.getRoot(), "CAUGHT_APP_REPOSITORY.java.properties").isFile());
	}

	@Test
	public void boundedStructuredMessageDoesNotGrowWithoutLimit() {
		JavaDiagnosticStore.ThrowableData data = new JavaDiagnosticStore.ThrowableData(
				"example.Failure",
				"x".repeat(JavaDiagnosticStore.MAX_MESSAGE_CHARS + 100),
				List.of());

		assertEquals(JavaDiagnosticStore.MAX_MESSAGE_CHARS, data.message.length());
	}

	@Test
	public void successfulLegacyMigrationDeletesSourceOnlyAfterReadableDestination() throws Exception {
		File source = temporary.newFile("legacy.stacktrace");
		try (FileOutputStream output = new FileOutputStream(source)) {
			output.write("legacy".getBytes(StandardCharsets.UTF_8));
		}
		File destination = new File(temporary.getRoot(), "migrated.java.properties");
		JavaDiagnosticStore.Snapshot snapshot = minimalSnapshot(destination);

		assertTrue(JavaDiagnosticStore.commitMigration(source, destination, snapshot));
		assertFalse(source.exists());
		assertEquals(JavaDiagnosticStore.Kind.LEGACY_ACRA,
				JavaDiagnosticStore.read(destination).kind);
	}

	@Test
	public void legacyLogicalRecordIdentitySurvivesStoreRoundTrip() throws Exception {
		File file = new File(temporary.getRoot(), "identity.java.properties");
		JavaDiagnosticStore.Snapshot snapshot =
				minimalSnapshot(file).withLegacyRecordId("acra:legacy-report.stacktrace");

		JavaDiagnosticStore.write(file, snapshot);
		JavaDiagnosticStore.Snapshot restored = JavaDiagnosticStore.read(file);

		assertEquals("acra:legacy-report.stacktrace", restored.legacyRecordId);
	}

	@Test
	public void migrationRetryBackfillsLegacyIdentityBeforeDeletingSource() throws Exception {
		File source = temporary.newFile("retry.stacktrace");
		File destination = new File(temporary.getRoot(), "retry.java.properties");
		JavaDiagnosticStore.write(destination, minimalSnapshot(destination));
		JavaDiagnosticStore.Snapshot incoming =
				minimalSnapshot(destination).withLegacyRecordId("acra:retry.stacktrace");

		assertTrue(JavaDiagnosticStore.commitMigration(source, destination, incoming));

		assertFalse(source.exists());
		assertEquals("acra:retry.stacktrace",
				JavaDiagnosticStore.read(destination).legacyRecordId);
	}

	@Test
	public void interruptedAtomicReplacementRestoresCommittedBackup() throws Exception {
		File file = new File(temporary.getRoot(), "interrupted.java.properties");
		JavaDiagnosticStore.Snapshot committed =
				minimalSnapshot(file).withLegacyRecordId("acra:committed");
		JavaDiagnosticStore.write(file, committed);

		File backup = new File(file.getPath() + ".bak");
		copyFile(file, backup);
		try (FileOutputStream output = new FileOutputStream(file, false)) {
			output.write("incomplete".getBytes(StandardCharsets.UTF_8));
		}

		JavaDiagnosticStore.Snapshot restored = JavaDiagnosticStore.read(file);

		assertEquals("acra:committed", restored.legacyRecordId);
		assertFalse(backup.exists());
	}

	@Test
	public void failedLegacyMigrationLeavesSourceIntact() throws Exception {
		File source = temporary.newFile("legacy-failed.stacktrace");
		File parentFile = temporary.newFile("not-a-directory");
		File impossibleDestination = new File(parentFile, "migrated.java.properties");

		try {
			JavaDiagnosticStore.commitMigration(
					source, impossibleDestination, minimalSnapshot(impossibleDestination));
			fail("Expected migration write to fail");
		} catch (Exception expected) {
			assertTrue(source.isFile());
		}
	}

	private static void copyFile(File source, File destination) throws Exception {
		try (java.io.FileInputStream input = new java.io.FileInputStream(source);
				FileOutputStream output = new FileOutputStream(destination, false)) {
			byte[] buffer = new byte[1024];
			int count;
			while ((count = input.read(buffer)) != -1) {
				output.write(buffer, 0, count);
			}
		}
	}

	private static JavaDiagnosticStore.Snapshot minimalSnapshot(File file) {
		return minimalSnapshot(file, JavaDiagnosticStore.Kind.LEGACY_ACRA);
	}

	private static JavaDiagnosticStore.Snapshot minimalSnapshot(File file, JavaDiagnosticStore.Kind kind) {
		JavaDiagnosticStore.ThrowableData failure = new JavaDiagnosticStore.ThrowableData(
				"java.lang.IllegalStateException", "legacy",
				List.of(new JavaDiagnosticStore.FrameData(
						"example.Game", "run", "Game.java", 1, false)));
		return minimalSnapshot(file, kind, failure);
	}

	private static JavaDiagnosticStore.Snapshot minimalSnapshot(File file, JavaDiagnosticStore.Kind kind,
			JavaDiagnosticStore.ThrowableData failure) {
		return new JavaDiagnosticStore.Snapshot(
				file,
				kind,
				1L,
				null,
				"midlet",
				-1,
				"MidletMain",
				-1,
				-1,
				null,
				"Game",
				null,
				"example.Game",
				null,
				"1.0",
				"15",
				35,
				null,
				null,
				null,
				null,
				"java.lang.IllegalStateException: legacy",
				List.of(failure),
				0,
				null,
				null,
				null);
	}
}
