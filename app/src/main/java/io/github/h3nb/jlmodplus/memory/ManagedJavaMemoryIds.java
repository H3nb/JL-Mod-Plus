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

/** Checked logical-id codec for the managed Java backend. */
final class ManagedJavaMemoryIds {
	static final long MANAGED_BIT = 1L << 62;
	static final int OWNER_BITS = 28;
	static final long OWNER_MAX = (1L << OWNER_BITS) - 1L;
	static final long SLOT_MAX = 0xffff_ffffL;

	private ManagedJavaMemoryIds() {
	}

	static long encode(int kind, long ownerHandle, int slot) {
		if (kind < 0 || kind > 3 || ownerHandle <= 0L || ownerHandle > OWNER_MAX || slot < 0) {
			throw new IllegalArgumentException("Managed logical id fields are out of range");
		}
		return MANAGED_BIT
				| ((long) kind << 60)
				| (ownerHandle << 32)
				| (slot & SLOT_MAX);
	}

	static boolean isManaged(long id) {
		return id > 0L && (id & MANAGED_BIT) != 0L;
	}

	static boolean hasValidNamespace(long id) {
		return isManaged(id) && ownerHandle(id) > 0L && ownerHandle(id) <= OWNER_MAX
				&& kind(id) >= 0 && kind(id) <= 3 && slot(id) >= 0;
	}

	static int kind(long id) {
		return (int) ((id >>> 60) & 0x3L);
	}

	static long ownerHandle(long id) {
		return (id >>> 32) & OWNER_MAX;
	}

	static int slot(long id) {
		return (int) id;
	}
}
