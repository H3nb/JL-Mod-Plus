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

	private final IMemoryEngineDiagnostics.Stub binder = new IMemoryEngineDiagnostics.Stub() {
		@Override
		public Bundle snapshot() {
			return collectSnapshot();
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
