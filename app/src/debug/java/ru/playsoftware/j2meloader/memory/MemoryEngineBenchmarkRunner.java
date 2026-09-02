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

	public record Sample(
			long operationId,
			int resultCode,
			long resultCount,
			String message,
			long elapsedNs,
			long maxProgressScannedBytes,
			long maxProgressTotalBytes,
			Bundle diagnostics) {
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
			public void onOperationProgress(long operationId, long scannedBytes, long totalBytes) {
				long expected = expectedOperationId.get();
				if (expected != 0L && operationId != expected) return;
				maxScanned.accumulateAndGet(Math.max(0L, scannedBytes), Math::max);
				maxTotal.accumulateAndGet(Math.max(0L, totalBytes), Math::max);
			}

			@Override
			public void onOperationFinished(long operationId, int code, long count,
			                                String nativeMessage, boolean passiveRefresh) {
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
}
