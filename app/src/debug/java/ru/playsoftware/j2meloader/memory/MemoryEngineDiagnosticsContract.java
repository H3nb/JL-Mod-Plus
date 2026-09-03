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
	public static final int SHADOW_SCHEMA_VERSION = 2;
	public static final int SHADOW_OPERATION_SCAN = 0;
	public static final int SHADOW_OPERATION_REFINE = 1;

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

	public static final String KEY_SHADOW_SCHEMA_VERSION = "shadowSchemaVersion";
	public static final String KEY_SHADOW_STATUS = "shadowStatus";
	public static final String KEY_SHADOW_OPERATION = "shadowOperation";
	public static final String KEY_SHADOW_EXPECTED_BITS = "shadowExpectedBits";
	public static final String KEY_SHADOW_BYTES_SCANNED = "shadowBytesScanned";
	public static final String KEY_SHADOW_TYPED_MATCHES = "shadowTypedMatches";
	public static final String KEY_SHADOW_UNIQUE_ADDRESSES = "shadowUniqueAddresses";
	public static final String KEY_SHADOW_BLOCK_COUNT = "shadowBlockCount";
	public static final String KEY_SHADOW_RETAINED_BYTES = "shadowRetainedBytes";
	public static final String KEY_SHADOW_ADDRESS_FINGERPRINT = "shadowAddressFingerprint";
	public static final String KEY_LEGACY_RESULT_COUNT = "legacyResultCount";
	public static final String KEY_LEGACY_ADDRESS_FINGERPRINT = "legacyAddressFingerprint";
	public static final String KEY_SHADOW_COUNT_MATCH = "shadowCountMatch";
	public static final String KEY_SHADOW_ADDRESS_MATCH = "shadowAddressMatch";
	public static final String KEY_SHADOW_MESSAGE = "shadowMessage";

	private MemoryEngineDiagnosticsContract() {
	}
}
