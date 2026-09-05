/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ru.playsoftware.j2meloader.memory;

/** Debug-only differential checks for the Candidate -> OrdinaryResultStore migration. */
final class MemoryV2OrdinaryDiagnostics {
	private MemoryV2OrdinaryDiagnostics() {
	}

	/** [status, typedCount, ordinaryRecordBytes, candidateRecordBytes, ordinaryRetained,
	 * candidateRetained, identityValidCount, orderedAddressTypeFingerprint]. */
	static native long[] parityStats();
}
