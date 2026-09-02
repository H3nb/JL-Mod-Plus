/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

/** Stable keys for debug-only Memory Editor benchmark snapshots. */
public final class MemoryEngineDiagnosticsContract {
	public static final int SCHEMA_VERSION = 1;

	public static final String KEY_SCHEMA_VERSION = "schemaVersion";
	public static final String KEY_CAPTURE_ELAPSED_REALTIME_NS = "captureElapsedRealtimeNs";
	public static final String KEY_SCAN_BYTES_SCANNED = "scanBytesScanned";
	public static final String KEY_SCAN_BYTES_TOTAL = "scanBytesTotal";
	public static final String KEY_LOGICAL_RESULT_COUNT = "logicalResultCount";
	public static final String KEY_HISTORY_DEPTH = "historyDepth";
	public static final String KEY_TOTAL_PSS_KB = "totalPssKb";
	public static final String KEY_RSS_KB = "rssKb";
	public static final String KEY_JAVA_HEAP_PSS_KB = "javaHeapPssKb";
	public static final String KEY_NATIVE_HEAP_PSS_KB = "nativeHeapPssKb";
	public static final String KEY_PRIVATE_OTHER_PSS_KB = "privateOtherPssKb";
	public static final String KEY_SYSTEM_PSS_KB = "systemPssKb";
	public static final String KEY_TOTAL_SWAP_KB = "totalSwapKb";
	public static final String KEY_NATIVE_HEAP_ALLOCATED_BYTES = "nativeHeapAllocatedBytes";
	public static final String KEY_RUNTIME_JAVA_USED_BYTES = "runtimeJavaUsedBytes";

	private MemoryEngineDiagnosticsContract() {
	}
}
