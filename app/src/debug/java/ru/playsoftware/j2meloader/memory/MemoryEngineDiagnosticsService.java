/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Debug-only pull diagnostics that execute inside {@code :memory_engine}.
 *
 * <p>No polling is performed. A snapshot is intentionally a relatively expensive benchmark
 * probe: it reads smaps/proc accounting only when a debug benchmark explicitly asks for it.
 * The production Memory Editor hot path does not depend on this service.</p>
 */
public final class MemoryEngineDiagnosticsService extends Service {
	private static final long UNKNOWN = -1L;
	private static final long FNV_OFFSET_BASIS = 1469598103934665603L;
	private static final long FNV_PRIME = 1099511628211L;

	private final IMemoryEngineDiagnostics.Stub binder = new IMemoryEngineDiagnostics.Stub() {
		@Override
		public Bundle snapshot() {
			return collectSnapshot();
		}

		@Override
		public Bundle validateKnownEqualShadow(int valueType) {
			return collectKnownEqualShadow(valueType);
		}
	};

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	private static Bundle collectSnapshot() {
		Debug.MemoryInfo memory = new Debug.MemoryInfo();
		Debug.getMemoryInfo(memory);

		long[] progress = NativeMemoryEngine.scanProgress();
		long scanned = progress != null && progress.length == 2 ? progress[0] : 0L;
		long total = progress != null && progress.length == 2 ? progress[1] : 0L;

		Runtime runtime = Runtime.getRuntime();
		long javaUsedBytes = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());

		Bundle result = new Bundle();
		result.putInt(MemoryEngineDiagnosticsContract.KEY_SCHEMA_VERSION,
				MemoryEngineDiagnosticsContract.SCHEMA_VERSION);
		result.putLong(MemoryEngineDiagnosticsContract.KEY_CAPTURE_ELAPSED_REALTIME_NS,
				SystemClock.elapsedRealtimeNanos());
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SCAN_BYTES_SCANNED,
				Math.max(0L, scanned));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SCAN_BYTES_TOTAL,
				Math.max(0L, total));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_LOGICAL_RESULT_COUNT,
				Math.max(0L, NativeMemoryEngine.resultCount()));
		result.putInt(MemoryEngineDiagnosticsContract.KEY_HISTORY_DEPTH,
				Math.max(0, NativeMemoryEngine.historyDepth()));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_TOTAL_PSS_KB,
				Math.max(0L, memory.getTotalPss()));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_RSS_KB, readVmRssKb());
		result.putLong(MemoryEngineDiagnosticsContract.KEY_JAVA_HEAP_PSS_KB,
				memoryStatKb(memory, "summary.java-heap"));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_NATIVE_HEAP_PSS_KB,
				memoryStatKb(memory, "summary.native-heap"));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_PRIVATE_OTHER_PSS_KB,
				memoryStatKb(memory, "summary.private-other"));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SYSTEM_PSS_KB,
				memoryStatKb(memory, "summary.system"));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_TOTAL_SWAP_KB,
				memoryStatKb(memory, "summary.total-swap"));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_NATIVE_HEAP_ALLOCATED_BYTES,
				Math.max(0L, Debug.getNativeHeapAllocatedSize()));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_RUNTIME_JAVA_USED_BYTES,
				javaUsedBytes);
		return result;
	}

	private static Bundle collectKnownEqualShadow(int valueType) {
		Bundle result = new Bundle();
		result.putInt(MemoryEngineDiagnosticsContract.KEY_SHADOW_SCHEMA_VERSION,
				MemoryEngineDiagnosticsContract.SHADOW_SCHEMA_VERSION);
		if (!MemoryEngineContract.isCandidateType(valueType)) {
			return shadowFailure(result, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Shadow parity requires one explicit primitive type");
		}

		long legacyCount = NativeMemoryEngine.resultCount();
		result.putLong(MemoryEngineDiagnosticsContract.KEY_LEGACY_RESULT_COUNT,
				Math.max(0L, legacyCount));
		if (legacyCount <= 0L) {
			return shadowFailure(result, MemoryEngineContract.RESULT_NO_SESSION,
					"Run a non-empty explicit-type Equal search or Equal refine before the shadow probe");
		}

		long[] firstPage = NativeMemoryEngine.resultPage(0, 1);
		if (!validResultPage(firstPage) || firstPage[0] != 1L) {
			return shadowFailure(result, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Legacy first result could not be materialized for parity validation");
		}
		int firstBase = 1;
		if (firstPage[firstBase + 3] != valueType) {
			return shadowFailure(result, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Current legacy result is not the requested explicit type");
		}
		// The first shadow probe uses initialBits so passive presentation refresh cannot change the
		// original Equal predicate. Once a shadow revision exists, native uses currentBits so the
		// same probe can validate a subsequent legacy Next Scan Equal refinement.
		long initialBits = firstPage[firstBase + 6];
		long currentBits = firstPage[firstBase + 8];

		long legacyFingerprint = legacyFingerprint(valueType, legacyCount);
		if (legacyFingerprint == Long.MIN_VALUE) {
			return shadowFailure(result, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"Legacy result changed while its parity fingerprint was being collected");
		}
		result.putLong(MemoryEngineDiagnosticsContract.KEY_LEGACY_ADDRESS_FINGERPRINT,
				legacyFingerprint);

		long[] shadow = NativeMemoryEngine.v2ShadowKnownEqual(
				valueType, initialBits, currentBits);
		if (shadow == null || shadow.length != 9) {
			return shadowFailure(result, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"V2 shadow scanner did not return a valid diagnostics payload");
		}
		int status = shadow[0] < Integer.MIN_VALUE || shadow[0] > Integer.MAX_VALUE
				? MemoryEngineContract.RESULT_INVALID_REQUEST : (int) shadow[0];
		int operation = shadow[7] < Integer.MIN_VALUE || shadow[7] > Integer.MAX_VALUE
				? -1 : (int) shadow[7];
		if (operation != MemoryEngineDiagnosticsContract.SHADOW_OPERATION_SCAN &&
				operation != MemoryEngineDiagnosticsContract.SHADOW_OPERATION_REFINE) {
			return shadowFailure(result, MemoryEngineContract.RESULT_INVALID_REQUEST,
					"V2 shadow scanner returned an invalid operation kind");
		}
		result.putInt(MemoryEngineDiagnosticsContract.KEY_SHADOW_STATUS, status);
		result.putInt(MemoryEngineDiagnosticsContract.KEY_SHADOW_OPERATION, operation);
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SHADOW_EXPECTED_BITS, shadow[8]);
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SHADOW_BYTES_SCANNED,
				Math.max(0L, shadow[1]));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SHADOW_TYPED_MATCHES,
				Math.max(0L, shadow[2]));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SHADOW_UNIQUE_ADDRESSES,
				Math.max(0L, shadow[3]));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SHADOW_BLOCK_COUNT,
				Math.max(0L, shadow[4]));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SHADOW_RETAINED_BYTES,
				Math.max(0L, shadow[5]));
		result.putLong(MemoryEngineDiagnosticsContract.KEY_SHADOW_ADDRESS_FINGERPRINT,
				shadow[6]);

		boolean countMatch = status == MemoryEngineContract.RESULT_OK &&
				legacyCount == shadow[3] && shadow[2] == shadow[3];
		boolean addressMatch = countMatch && legacyFingerprint == shadow[6];
		result.putBoolean(MemoryEngineDiagnosticsContract.KEY_SHADOW_COUNT_MATCH, countMatch);
		result.putBoolean(MemoryEngineDiagnosticsContract.KEY_SHADOW_ADDRESS_MATCH, addressMatch);
		String operationName = operation == MemoryEngineDiagnosticsContract.SHADOW_OPERATION_REFINE
				? "bitmap refine" : "first scan";
		result.putString(MemoryEngineDiagnosticsContract.KEY_SHADOW_MESSAGE,
				addressMatch
						? "Legacy Candidate and v2 ResultStore " + operationName + " results match"
						: "Shadow parity mismatch after " + operationName +
								"; keep the legacy backend authoritative");
		return result;
	}

	private static long legacyFingerprint(int valueType, long expectedCount) {
		long fingerprint = FNV_OFFSET_BASIS;
		long offset = 0L;
		int planeTag = fingerprintPlaneTag(valueType);
		if (planeTag == 0) return Long.MIN_VALUE;
		while (offset < expectedCount) {
			int limit = (int) Math.min(MemoryEngineContract.MAX_RESULT_PAGE_SIZE,
					expectedCount - offset);
			if (offset > Integer.MAX_VALUE) return Long.MIN_VALUE;
			long[] rows = NativeMemoryEngine.resultPage((int) offset, limit);
			if (!validResultPage(rows)) return Long.MIN_VALUE;
			int count = (int) rows[0];
			if (count <= 0) return Long.MIN_VALUE;
			for (int index = 0; index < count; index++) {
				int base = 1 + index * MemoryEngineContract.RESULT_PAGE_STRIDE;
				long address = rows[base + 1];
				long rawType = rows[base + 3];
				if (address <= 0L || rawType != valueType) return Long.MIN_VALUE;
				fingerprint ^= address;
				fingerprint *= FNV_PRIME;
				fingerprint ^= planeTag;
				fingerprint *= FNV_PRIME;
			}
			offset += count;
		}
		return offset == expectedCount ? fingerprint : Long.MIN_VALUE;
	}

	private static int fingerprintPlaneTag(int valueType) {
		return switch (valueType) {
			case MemoryEngineContract.TYPE_BYTE -> 1;
			case MemoryEngineContract.TYPE_SHORT -> 2;
			case MemoryEngineContract.TYPE_CHAR -> 3;
			case MemoryEngineContract.TYPE_INT -> 4;
			case MemoryEngineContract.TYPE_FLOAT -> 5;
			case MemoryEngineContract.TYPE_LONG -> 6;
			case MemoryEngineContract.TYPE_DOUBLE -> 7;
			default -> 0;
		};
	}

	private static boolean validResultPage(long[] rows) {
		if (rows == null || rows.length == 0 || rows[0] < 0L ||
				rows[0] > (rows.length - 1L) / MemoryEngineContract.RESULT_PAGE_STRIDE) {
			return false;
		}
		return 1L + rows[0] * MemoryEngineContract.RESULT_PAGE_STRIDE == rows.length;
	}

	private static Bundle shadowFailure(Bundle result, int status, String message) {
		result.putInt(MemoryEngineDiagnosticsContract.KEY_SHADOW_STATUS, status);
		result.putBoolean(MemoryEngineDiagnosticsContract.KEY_SHADOW_COUNT_MATCH, false);
		result.putBoolean(MemoryEngineDiagnosticsContract.KEY_SHADOW_ADDRESS_MATCH, false);
		result.putString(MemoryEngineDiagnosticsContract.KEY_SHADOW_MESSAGE, message);
		return result;
	}

	static long memoryStatKb(Debug.MemoryInfo memory, String key) {
		if (memory == null || key == null) return UNKNOWN;
		String value = memory.getMemoryStat(key);
		if (value == null || value.isBlank()) return UNKNOWN;
		try {
			long parsed = Long.parseLong(value.trim());
			return parsed >= 0L ? parsed : UNKNOWN;
		} catch (NumberFormatException ignored) {
			return UNKNOWN;
		}
	}

	static long parseVmRssKb(String line) {
		if (line == null || !line.startsWith("VmRSS:")) return UNKNOWN;
		String value = line.substring("VmRSS:".length()).trim();
		int separator = value.indexOf(' ');
		String number = separator < 0 ? value : value.substring(0, separator);
		try {
			long parsed = Long.parseLong(number);
			return parsed >= 0L ? parsed : UNKNOWN;
		} catch (NumberFormatException ignored) {
			return UNKNOWN;
		}
	}

	private static long readVmRssKb() {
		try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith("VmRSS:")) return parseVmRssKb(line);
			}
		} catch (IOException | SecurityException ignored) {
			// A missing proc metric must not break the benchmark or engine process.
		}
		return UNKNOWN;
	}
}
