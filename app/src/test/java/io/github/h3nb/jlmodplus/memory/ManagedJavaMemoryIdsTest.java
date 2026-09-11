/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.h3nb.jlmodplus.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ManagedJavaMemoryIdsTest {
	@Test
	public void logicalIdsRoundTripWithinTheManagedNamespace() {
		long id = ManagedJavaMemoryIds.encode(ManagedJavaMemoryEngine.KIND_ARRAY, 37L, 1234);

		assertTrue(ManagedJavaMemoryIds.isManaged(id));
		assertTrue(ManagedJavaMemoryIds.hasValidNamespace(id));
		assertEquals(ManagedJavaMemoryEngine.KIND_ARRAY, ManagedJavaMemoryIds.kind(id));
		assertEquals(37L, ManagedJavaMemoryIds.ownerHandle(id));
		assertEquals(1234, ManagedJavaMemoryIds.slot(id));
	}

	@Test
	public void rawAndMalformedIdsAreRejectedBeforeLookup() {
		assertFalse(ManagedJavaMemoryIds.isManaged(1L));
		assertFalse(ManagedJavaMemoryIds.hasValidNamespace(1L));
		assertFalse(ManagedJavaMemoryIds.hasValidNamespace(
				ManagedJavaMemoryIds.MANAGED_BIT));
		assertFalse(ManagedJavaMemoryIds.hasValidNamespace(
				ManagedJavaMemoryIds.encode(0, 1L, Integer.MAX_VALUE) | (1L << 31)));
	}
}
