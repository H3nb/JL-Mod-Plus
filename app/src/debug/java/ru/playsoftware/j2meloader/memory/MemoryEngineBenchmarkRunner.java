/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Debug-only helper for repeatable end-to-end Memory Editor benchmark samples.
 *
 * <p>The caller supplies the already-bound engine and diagnostics interfaces and an operation
 * starter such as {@code () -> engine.startKnownSearch(...)}. The measured interval starts
 * immediately before the Binder start call and ends when the matching non-passive completion
 * callback arrives. The diagnostics snapshot is pulled only after completion.</p>
 */
public final class MemoryEngineBenchmarkRunner {
	private MemoryEngineBenchmarkRunner() {
	}

	@FunctionalInterface
	public interface Operation {
		long start() throws RemoteException;
	}

	public static final class Sample {
		public final long operationId;
		public final int resultCode;
		public final long resultCount;
		public final String message;
		public final long elapsedNs;
		public final long maxProgressScannedBytes;
		public final long maxProgressTotalBytes;
		public final Bundle diagnostics;

		Sample(long operationId, int resultCode, long resultCount, String message,
		       long elapsedNs, long maxProgressScannedBytes, long maxProgressTotalBytes,
		       Bundle diagnostics) {
			this.operationId = operationId;
			this.resultCode = resultCode;
			this.resultCount = resultCount;
			this.message = message;
			this.elapsedNs = elapsedNs;
			this.maxProgressScannedBytes = maxProgressScannedBytes;
			this.maxProgressTotalBytes = maxProgressTotalBytes;
			this.diagnostics = diagnostics;
		}
	}

	/** One explicit-type Known first-search case for physical v2 parity validation. */
	public static final class KnownParityCase {
		public final int valueType;
		public final int predicate;
		public final String first;
		public final String second;

		public KnownParityCase(int valueType, int predicate, String first, String second) {
			if (!MemoryEngineContract.isCandidateType(valueType) ||
					predicate < MemoryEngineContract.PREDICATE_EQUAL ||
					predicate > MemoryEngineContract.PREDICATE_BETWEEN || first == null ||
					second == null) {
				throw new IllegalArgumentException("Invalid explicit-type Known parity case");
			}
			this.valueType = valueType;
			this.predicate = predicate;
			this.first = first;
			this.second = second;
		}
	}

	/**
	 * One complete physical parity sample. A GC count change is reported separately from an actual
	 * shadow mismatch because target mutation/relocation during the two sequential scans makes the
	 * sample inconclusive rather than proving a scanner defect.
	 */
	public static final class KnownParityResult {
		public final KnownParityCase testCase;
		public final Sample production;
		public final Bundle shadow;
		public final long gcCountBefore;
		public final long gcCountAfter;
		public final boolean gcRaceObserved;
		public final boolean shadowParity;

		KnownParityResult(KnownParityCase testCase, Sample production, Bundle shadow,
		                  long gcCountBefore, long gcCountAfter, boolean gcRaceObserved,
		                  boolean shadowParity) {
			this.testCase = testCase;
			this.production = production;
			this.shadow = shadow;
			this.gcCountBefore = gcCountBefore;
			this.gcCountAfter = gcCountAfter;
			this.gcRaceObserved = gcRaceObserved;
			this.shadowParity = shadowParity;
		}

		/** True only when production succeeded, parity matched, and no known moving-GC race occurred. */
		public boolean passed() {
			return production.resultCode == MemoryEngineContract.RESULT_OK &&
					shadowParity && !gcRaceObserved;
		}
	}

	public static Sample run(IMemoryEngineService engine,
	                         IMemoryEngineDiagnostics diagnostics,
	                         Operation operation,
	                         long timeoutMs) throws RemoteException, InterruptedException {
		if (engine == null || diagnostics == null || operation == null || timeoutMs <= 0L) {
			throw new IllegalArgumentException("Benchmark runner requires bound services and timeout");
		}

		CountDownLatch finished = new CountDownLatch(1);
		AtomicLong expectedOperationId = new AtomicLong(0L);
		AtomicLong earlyOperationId = new AtomicLong(0L);
		AtomicLong resultCount = new AtomicLong(0L);
		AtomicLong maxScanned = new AtomicLong(0L);
		AtomicLong maxTotal = new AtomicLong(0L);
		AtomicReference<Integer> resultCode = new AtomicReference<>();
		AtomicReference<String> message = new AtomicReference<>();

		IMemoryEngineCallback callback = new IMemoryEngineCallback.Stub() {
			@Override
			public void onOperationProgress(long operationId, long scannedBytes, long totalBytes,
			                                boolean searchOperation) {
				long expected = expectedOperationId.get();
				if (expected != 0L && operationId != expected) return;
				updateMax(maxScanned, scannedBytes);
				updateMax(maxTotal, totalBytes);
			}

			@Override
			public void onOperationFinished(long operationId, int code, long count,
			                                String nativeMessage, boolean passiveRefresh,
			                                boolean searchOperation) {
				if (passiveRefresh) return;
				long expected = expectedOperationId.get();
				if (expected == 0L) {
					earlyOperationId.compareAndSet(0L, operationId);
				} else if (operationId != expected) {
					return;
				}
				resultCode.set(code);
				resultCount.set(Math.max(0L, count));
				message.set(nativeMessage);
				finished.countDown();
			}
		};

		engine.registerCallback(callback);
		long startedNs = SystemClock.elapsedRealtimeNanos();
		long operationId;
		try {
			operationId = operation.start();
			expectedOperationId.set(operationId);
			long early = earlyOperationId.get();
			if (early != 0L && early != operationId) {
				throw new IllegalStateException("Another engine operation completed during benchmark");
			}
			if (!finished.await(timeoutMs, TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("Memory Editor benchmark operation timed out");
			}
		} finally {
			engine.unregisterCallback(callback);
		}
		long elapsedNs = Math.max(0L, SystemClock.elapsedRealtimeNanos() - startedNs);
		Integer completedCode = resultCode.get();
		if (completedCode == null) {
			throw new IllegalStateException("Benchmark completion did not include a result code");
		}
		Bundle snapshot = diagnostics.snapshot();
		return new Sample(
				operationId,
				completedCode,
				resultCount.get(),
				message.get(),
				elapsedNs,
				maxScanned.get(),
				maxTotal.get(),
				snapshot == null ? Bundle.EMPTY : snapshot);
	}

	/**
	 * Validate the current legacy explicit-type Known result using the exact native parser that the
	 * production engine uses. Query strings stop here: the diagnostics AIDL and v2 shadow receive
	 * only canonical primitive bits, so there is no second Java/shadow parser to drift over time.
	 */
	public static Bundle validateKnownShadowQuery(IMemoryEngineDiagnostics diagnostics,
	                                              int valueType,
	                                              int predicate,
	                                              String first,
	                                              String second) throws RemoteException {
		if (diagnostics == null) {
			throw new IllegalArgumentException("Diagnostics service is required");
		}
		long[] plan = NativeMemoryEngine.canonicalKnownPlan(
				valueType, predicate, first, second);
		if (plan == null || plan.length != 4 ||
				plan[0] < Integer.MIN_VALUE || plan[0] > Integer.MAX_VALUE ||
				plan[1] < Integer.MIN_VALUE || plan[1] > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(
					"Known query could not be canonicalized by the production native parser");
		}
		Bundle result = diagnostics.validateKnownShadowPlan(
				(int) plan[0], (int) plan[1], plan[2], plan[3]);
		return result == null ? Bundle.EMPTY : result;
	}

	/**
	 * Run one fresh production Known search followed by its v2 shadow first-scan parity check.
	 * Fresh-search semantics are important: NativeMemoryEngine.startKnown() resets the retained
	 * shadow revision, so two adjacent matrix cases can never accidentally become a bitmap refine.
	 */
	public static KnownParityResult runKnownParityCase(IMemoryEngineService engine,
	                                                   IMemoryEngineDiagnostics diagnostics,
	                                                   long token,
	                                                   int scope,
	                                                   KnownParityCase testCase,
	                                                   long timeoutMs)
			throws RemoteException, InterruptedException {
		if (engine == null || diagnostics == null || testCase == null || token == 0L ||
				!MemoryEngineContract.isScope(scope)) {
			throw new IllegalArgumentException("Known parity case requires a live explicit target");
		}

		long gcBefore = gcCount(engine.getCapabilities());
		Sample production = run(
				engine,
				diagnostics,
				() -> engine.startKnownSearch(
						token, scope, testCase.valueType, testCase.predicate,
						testCase.first, testCase.second),
				timeoutMs);
		Bundle shadow = Bundle.EMPTY;
		if (production.resultCode == MemoryEngineContract.RESULT_OK) {
			shadow = validateKnownShadowQuery(
					diagnostics, testCase.valueType, testCase.predicate,
					testCase.first, testCase.second);
		}
		long gcAfter = gcCount(engine.getCapabilities());
		boolean gcRace = MemoryEngineContract.didGcCountChange(gcBefore, gcAfter);
		boolean parity = production.resultCode == MemoryEngineContract.RESULT_OK &&
				shadow.getInt(
						MemoryEngineDiagnosticsContract.KEY_SHADOW_STATUS,
						MemoryEngineContract.RESULT_INVALID_REQUEST) ==
						MemoryEngineContract.RESULT_OK &&
				shadow.getInt(
						MemoryEngineDiagnosticsContract.KEY_SHADOW_OPERATION,
						-1) == MemoryEngineDiagnosticsContract.SHADOW_OPERATION_SCAN &&
				shadow.getBoolean(MemoryEngineDiagnosticsContract.KEY_SHADOW_COUNT_MATCH, false) &&
				shadow.getBoolean(MemoryEngineDiagnosticsContract.KEY_SHADOW_ADDRESS_MATCH, false);
		return new KnownParityResult(
				testCase, production, shadow, gcBefore, gcAfter, gcRace, parity);
	}

	/**
	 * Sequentially run a caller-supplied all-predicate matrix. Cases stop only for thrown transport/
	 * timeout errors; a semantic mismatch is retained in the returned list so the full matrix can
	 * reveal whether a defect is type- or predicate-specific.
	 */
	public static List<KnownParityResult> runKnownParityMatrix(
			IMemoryEngineService engine,
			IMemoryEngineDiagnostics diagnostics,
			long token,
			int scope,
			List<KnownParityCase> cases,
			long timeoutMs) throws RemoteException, InterruptedException {
		if (cases == null || cases.isEmpty()) {
			throw new IllegalArgumentException("Known parity matrix requires at least one case");
		}
		ArrayList<KnownParityResult> results = new ArrayList<>(cases.size());
		for (KnownParityCase testCase : cases) {
			if (testCase == null) {
				throw new IllegalArgumentException("Known parity matrix contains a null case");
			}
			results.add(runKnownParityCase(
					engine, diagnostics, token, scope, testCase, timeoutMs));
		}
		return Collections.unmodifiableList(results);
	}

	private static long gcCount(Bundle capabilities) {
		if (capabilities == null) {
			return MemoryEngineContract.GC_COUNT_UNKNOWN;
		}
		return capabilities.getLong(
				MemoryEngineContract.KEY_GC_COUNT,
				MemoryEngineContract.GC_COUNT_UNKNOWN);
	}

	private static void updateMax(AtomicLong target, long value) {
		long bounded = Math.max(0L, value);
		while (true) {
			long current = target.get();
			if (bounded <= current || target.compareAndSet(current, bounded)) {
				return;
			}
		}
	}
}
