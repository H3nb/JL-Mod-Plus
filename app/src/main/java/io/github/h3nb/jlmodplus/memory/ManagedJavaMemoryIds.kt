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

// Modification: migrated from the Java helper while preserving behavior and Java static ABI.
package io.github.h3nb.jlmodplus.memory

/** Checked logical-id codec for the managed Java backend. */
internal object ManagedJavaMemoryIds {
    // Keep these fields and methods as Java statics for the existing engine/service callers.
    @JvmField
    val MANAGED_BIT: Long = 1L shl 62

    @JvmField
    val OWNER_BITS: Int = 28

    @JvmField
    val OWNER_MAX: Long = (1L shl OWNER_BITS) - 1L

    @JvmField
    val SLOT_MAX: Long = 0xffff_ffffL

    @JvmStatic
    fun encode(kind: Int, ownerHandle: Long, slot: Int): Long {
        require(kind in 0..3 && ownerHandle > 0L && ownerHandle <= OWNER_MAX && slot >= 0) {
            "Managed logical id fields are out of range"
        }
        return MANAGED_BIT or
            (kind.toLong() shl 60) or
            (ownerHandle shl 32) or
            (slot.toLong() and SLOT_MAX)
    }

    @JvmStatic
    fun isManaged(id: Long): Boolean = id > 0L && (id and MANAGED_BIT) != 0L

    @JvmStatic
    fun hasValidNamespace(id: Long): Boolean =
        isManaged(id) && ownerHandle(id) > 0L && ownerHandle(id) <= OWNER_MAX &&
            kind(id) in 0..3 && slot(id) >= 0

    @JvmStatic
    fun kind(id: Long): Int = ((id ushr 60) and 0x3L).toInt()

    @JvmStatic
    fun ownerHandle(id: Long): Long = (id ushr 32) and OWNER_MAX

    @JvmStatic
    fun slot(id: Long): Int = id.toInt()
}
